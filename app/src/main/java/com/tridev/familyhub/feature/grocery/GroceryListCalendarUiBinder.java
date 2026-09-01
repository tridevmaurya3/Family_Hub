package com.tridev.familyhub.feature.grocery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presentation-only List / Calendar view switch for Grocery.
 *
 * Calendar rows are read from the existing Room cache and use
 * GroceryRecurrenceEngine.nextDueAt() for recurring dates. The RecyclerView,
 * repository, filters, search query, recurrence writes, Firebase paths and
 * purchase flow are never replaced or mutated.
 */
public final class GroceryListCalendarUiBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final String PREFS = "grocery_page_view";
    private static final String KEY_MODE = "mode";
    private static final String MODE_LIST = "LIST";
    private static final String MODE_CALENDAR = "CALENDAR";
    private static final ExecutorService READ_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final Map<Activity, ActivityProbe> probes = new WeakHashMap<>();

    private GroceryListCalendarUiBinder(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(
                new GroceryListCalendarUiBinder(application));
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity,
                                  @Nullable Bundle savedInstanceState) {
        ensureProbe(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        ensureProbe(activity);
    }

    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                      @NonNull Bundle outState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        ActivityProbe probe = probes.remove(activity);
        if (probe != null) probe.dispose();
    }

    private void ensureProbe(@NonNull Activity activity) {
        if (probes.containsKey(activity)) return;
        View root = activity.getWindow().getDecorView();
        ActivityProbe probe = new ActivityProbe(activity, root);
        probes.put(activity, probe);
        probe.install();
    }

    private final class ActivityProbe {
        private final Activity activity;
        private final View root;
        private final ViewTreeObserver.OnGlobalLayoutListener listener;
        @Nullable private PageController controller;

        ActivityProbe(@NonNull Activity activity, @NonNull View root) {
            this.activity = activity;
            this.root = root;
            listener = this::checkPage;
        }

        void install() {
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
            root.post(this::checkPage);
        }

        void checkPage() {
            RecyclerView recycler = root.findViewById(R.id.grocery_recycler_view);
            View actionScroll = root.findViewById(R.id.grocery_action_scroll);
            if (recycler == null || actionScroll == null || !recycler.isAttachedToWindow()) {
                if (controller != null) {
                    controller.dispose();
                    controller = null;
                }
                return;
            }
            if (controller != null && controller.recycler == recycler) {
                controller.ensureAdapterObserver();
                return;
            }
            if (controller != null) controller.dispose();
            controller = PageController.attach(activity, recycler, actionScroll);
        }

        void dispose() {
            if (controller != null) {
                controller.dispose();
                controller = null;
            }
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
        }
    }

    private static final class PageController {
        private final Activity activity;
        private final Context appContext;
        private final RecyclerView recycler;
        private final View emptyState;
        private final ConstraintLayout parent;
        private final MaterialButton toggle;
        private final LinearLayout calendarPanel;
        private final LinearLayout calendarRows;
        private final TextView calendarSubtitle;
        @Nullable private final EditText searchInput;

        @Nullable private RecyclerView.Adapter<?> observedAdapter;
        @Nullable private RecyclerView.AdapterDataObserver adapterObserver;
        private boolean active = true;
        private boolean calendarMode;
        private boolean forcedEmptyVisible;
        private float savedRecyclerAlpha = 1f;
        private boolean savedRecyclerEnabled = true;
        private int savedRecyclerAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        private float savedEmptyAlpha = 1f;
        private long renderGeneration;

        @Nullable
        static PageController attach(@NonNull Activity activity,
                                     @NonNull RecyclerView recycler,
                                     @NonNull View actionScroll) {
            View emptyState = activity.findViewById(R.id.grocery_empty_state);
            View dueButton = activity.findViewById(R.id.grocery_due_calendar_button);
            if (emptyState == null || dueButton == null
                    || !(recycler.getParent() instanceof ConstraintLayout)
                    || !(dueButton.getParent() instanceof LinearLayout)) {
                return null;
            }
            return new PageController(
                    activity, recycler, emptyState,
                    (ConstraintLayout) recycler.getParent(),
                    (LinearLayout) dueButton.getParent(), dueButton);
        }

        PageController(@NonNull Activity activity,
                       @NonNull RecyclerView recycler,
                       @NonNull View emptyState,
                       @NonNull ConstraintLayout parent,
                       @NonNull LinearLayout actionRow,
                       @NonNull View dueButton) {
            this.activity = activity;
            this.appContext = activity.getApplicationContext();
            this.recycler = recycler;
            this.emptyState = emptyState;
            this.parent = parent;
            searchInput = activity.findViewById(R.id.grocery_search_input);

            toggle = buildToggleButton(activity);
            int dueIndex = actionRow.indexOfChild(dueButton);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48));
            actionRow.addView(toggle, Math.max(0, dueIndex + 1), toggleParams);

            calendarPanel = new LinearLayout(activity);
            calendarPanel.setId(View.generateViewId());
            calendarPanel.setOrientation(LinearLayout.VERTICAL);
            calendarPanel.setPadding(dp(activity, 12), dp(activity, 10),
                    dp(activity, 12), dp(activity, 16));
            calendarPanel.setBackground(roundedBackground(activity,
                    Color.rgb(250, 252, 251), Color.rgb(211, 225, 219), 16));
            calendarPanel.setElevation(dp(activity, 8));
            calendarPanel.setVisibility(View.GONE);
            calendarPanel.setContentDescription("Grocery calendar view");

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText("Upcoming Grocery");
            title.setTextSize(15f);
            title.setTextColor(Color.rgb(26, 54, 46));
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            calendarSubtitle = new TextView(activity);
            calendarSubtitle.setText("Due dates from your existing Grocery schedule");
            calendarSubtitle.setTextSize(11.5f);
            calendarSubtitle.setTextColor(Color.rgb(91, 107, 101));
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(activity, 2);
            header.addView(calendarSubtitle, subtitleParams);
            calendarPanel.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(0, dp(activity, 8), 0, dp(activity, 80));
            calendarRows = new LinearLayout(activity);
            calendarRows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(calendarRows, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            calendarPanel.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            ConstraintLayout.LayoutParams panelParams = new ConstraintLayout.LayoutParams(0, 0);
            panelParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            panelParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            panelParams.topToBottom = R.id.grocery_action_scroll;
            panelParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            panelParams.topMargin = dp(activity, 8);
            parent.addView(calendarPanel, panelParams);

            toggle.setOnClickListener(v -> setCalendarMode(!calendarMode, true));
            ensureAdapterObserver();

            String saved = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MODE, MODE_LIST);
            setCalendarMode(MODE_CALENDAR.equals(saved), false);
        }

        void ensureAdapterObserver() {
            if (!active) return;
            RecyclerView.Adapter<?> adapter = recycler.getAdapter();
            if (adapter == observedAdapter) return;
            if (observedAdapter != null && adapterObserver != null) {
                observedAdapter.unregisterAdapterDataObserver(adapterObserver);
            }
            observedAdapter = adapter;
            if (adapter == null) return;
            adapterObserver = new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { refreshCalendarIfVisible(); }
                @Override public void onItemRangeChanged(int positionStart, int itemCount) {
                    refreshCalendarIfVisible();
                }
                @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                    refreshCalendarIfVisible();
                }
                @Override public void onItemRangeRemoved(int positionStart, int itemCount) {
                    refreshCalendarIfVisible();
                }
            };
            adapter.registerAdapterDataObserver(adapterObserver);
            if (calendarMode) refreshCalendar();
        }

        private void refreshCalendarIfVisible() {
            if (calendarMode) recycler.post(this::refreshCalendar);
        }

        private void setCalendarMode(boolean enabled, boolean persist) {
            if (!active) return;
            if (enabled == calendarMode && calendarPanel.getVisibility()
                    == (enabled ? View.VISIBLE : View.GONE)) {
                updateToggleLabel();
                return;
            }
            calendarMode = enabled;
            if (persist) {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_MODE, enabled ? MODE_CALENDAR : MODE_LIST).apply();
            }
            if (enabled) {
                savedRecyclerAlpha = recycler.getAlpha();
                savedRecyclerEnabled = recycler.isEnabled();
                savedRecyclerAccessibility = recycler.getImportantForAccessibility();
                savedEmptyAlpha = emptyState.getAlpha();

                recycler.setAlpha(0f);
                recycler.setEnabled(false);
                recycler.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                if (recycler.getAdapter() == null || recycler.getAdapter().getItemCount() == 0) {
                    if (emptyState.getVisibility() != View.VISIBLE) {
                        emptyState.setVisibility(View.VISIBLE);
                        forcedEmptyVisible = true;
                    }
                }
                emptyState.setAlpha(0f);
                calendarPanel.setVisibility(View.VISIBLE);
                refreshCalendar();
            } else {
                renderGeneration++;
                calendarPanel.setVisibility(View.GONE);
                recycler.setAlpha(savedRecyclerAlpha);
                recycler.setEnabled(savedRecyclerEnabled);
                recycler.setImportantForAccessibility(savedRecyclerAccessibility);
                emptyState.setAlpha(savedEmptyAlpha);
                if (forcedEmptyVisible) {
                    RecyclerView.Adapter<?> adapter = recycler.getAdapter();
                    if (adapter != null && adapter.getItemCount() > 0) {
                        emptyState.setVisibility(View.GONE);
                    }
                    forcedEmptyVisible = false;
                }
            }
            updateToggleLabel();
        }

        private void updateToggleLabel() {
            toggle.setText(calendarMode ? "Calendar view" : "List view");
            toggle.setContentDescription(calendarMode
                    ? "Calendar view selected. Switch to Grocery list view"
                    : "List view selected. Switch to Grocery calendar view");
        }

        private void refreshCalendar() {
            if (!active || !calendarMode) return;
            final long generation = ++renderGeneration;
            calendarSubtitle.setText("Refreshing due dates…");
            READ_EXECUTOR.execute(() -> {
                List<GroceryItem> source = FamilyHubDatabase.getInstance(appContext)
                        .groceryItemDao().getAll();
                List<CalendarEntry> entries = buildEntries(source);
                activity.runOnUiThread(() -> {
                    if (!active || !calendarMode || generation != renderGeneration
                            || activity.isFinishing() || activity.isDestroyed()) return;
                    renderCalendar(entries);
                });
            });
        }

        @NonNull
        private List<CalendarEntry> buildEntries(@Nullable List<GroceryItem> source) {
            if (source == null || source.isEmpty()) return Collections.emptyList();
            long now = System.currentTimeMillis();
            long todayStart = startOfDay(now);
            List<CalendarEntry> result = new ArrayList<>();
            for (GroceryItem item : source) {
                if (item == null || item.isPurchased) continue;
                String cycle = GroceryRecurrenceEngine.originalCycle(item);
                long dueAt;
                if (!GroceryRecurrenceEngine.isRecurringType(cycle)
                        || item.purchasedAt <= 0L) {
                    dueAt = todayStart;
                } else {
                    dueAt = GroceryRecurrenceEngine.nextDueAt(item);
                    if (dueAt == Long.MAX_VALUE) dueAt = todayStart;
                }
                result.add(new CalendarEntry(item, dueAt, cycle));
            }
            result.sort(Comparator
                    .comparingLong((CalendarEntry entry) -> entry.dueAt)
                    .thenComparing(entry -> entry.item.category,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.item.name,
                            String.CASE_INSENSITIVE_ORDER));
            return result;
        }

        private void renderCalendar(@NonNull List<CalendarEntry> entries) {
            calendarRows.removeAllViews();
            if (entries.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("No pending Grocery items\nAdd an item to see its due date here.");
                empty.setGravity(Gravity.CENTER);
                empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                empty.setTextSize(13f);
                empty.setTextColor(Color.rgb(91, 107, 101));
                empty.setPadding(dp(activity, 16), dp(activity, 44),
                        dp(activity, 16), dp(activity, 44));
                calendarRows.addView(empty, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                calendarSubtitle.setText("0 upcoming items • local saved data");
                return;
            }

            calendarSubtitle.setText(entries.size()
                    + " upcoming item" + (entries.size() == 1 ? "" : "s")
                    + " • tap an item to find it in List view");

            long lastDay = Long.MIN_VALUE;
            for (CalendarEntry entry : entries) {
                long day = startOfDay(entry.dueAt);
                if (day != lastDay) {
                    addDateHeader(day);
                    lastDay = day;
                }
                addCalendarRow(entry);
            }
        }

        private void addDateHeader(long dayStart) {
            TextView header = new TextView(activity);
            header.setText(dateHeader(dayStart));
            header.setTextSize(12f);
            header.setTextColor(Color.rgb(15, 108, 89));
            header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
            header.setPadding(dp(activity, 4), dp(activity, 12),
                    dp(activity, 4), dp(activity, 6));
            calendarRows.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void addCalendarRow(@NonNull CalendarEntry entry) {
            MaterialCardView card = new MaterialCardView(activity);
            card.setCardBackgroundColor(Color.WHITE);
            card.setCardElevation(0f);
            card.setRadius(dp(activity, 14));
            card.setStrokeWidth(dp(activity, 1));
            card.setStrokeColor(Color.rgb(220, 229, 225));
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(dp(activity, 12), dp(activity, 9),
                    dp(activity, 10), dp(activity, 9));

            LinearLayout textColumn = new LinearLayout(activity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(activity);
            name.setText(entry.item.name);
            name.setTextSize(13.5f);
            name.setTextColor(Color.rgb(27, 39, 35));
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            textColumn.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView meta = new TextView(activity);
            StringBuilder metaText = new StringBuilder();
            if (!entry.item.category.trim().isEmpty()) {
                metaText.append(entry.item.category.trim());
            }
            if (metaText.length() > 0) metaText.append(" • ");
            metaText.append(cycleLabel(entry.cycle));
            if (!entry.item.quantity.trim().isEmpty()) {
                metaText.append(" • ").append(entry.item.quantity.trim());
            }
            meta.setText(metaText.toString());
            meta.setTextSize(11f);
            meta.setTextColor(Color.rgb(101, 113, 109));
            meta.setMaxLines(1);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = dp(activity, 2);
            textColumn.addView(meta, metaParams);
            content.addView(textColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView due = new TextView(activity);
            due.setText(shortDueLabel(entry.dueAt));
            due.setTextSize(10.5f);
            due.setTextColor(Color.rgb(15, 108, 89));
            due.setGravity(Gravity.CENTER);
            due.setPadding(dp(activity, 8), dp(activity, 5),
                    dp(activity, 8), dp(activity, 5));
            due.setBackground(roundedBackground(activity,
                    Color.rgb(235, 248, 242), Color.rgb(193, 222, 209), 12));
            content.addView(due, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            card.addView(content);
            card.setContentDescription(entry.item.name + ", due " + dateHeader(entry.dueAt));
            card.setOnClickListener(v -> openInList(entry.item.name));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(activity, 6);
            calendarRows.addView(card, cardParams);
        }

        private void openInList(@NonNull String itemName) {
            setCalendarMode(false, true);
            if (searchInput == null) return;
            searchInput.setText(itemName);
            searchInput.setSelection(searchInput.length());
            searchInput.requestFocus();
        }

        private void dispose() {
            if (!active) return;
            active = false;
            renderGeneration++;
            if (observedAdapter != null && adapterObserver != null) {
                observedAdapter.unregisterAdapterDataObserver(adapterObserver);
            }
            if (calendarMode) {
                recycler.setAlpha(savedRecyclerAlpha);
                recycler.setEnabled(savedRecyclerEnabled);
                recycler.setImportantForAccessibility(savedRecyclerAccessibility);
                emptyState.setAlpha(savedEmptyAlpha);
                if (forcedEmptyVisible && recycler.getAdapter() != null
                        && recycler.getAdapter().getItemCount() > 0) {
                    emptyState.setVisibility(View.GONE);
                }
            }
            if (toggle.getParent() instanceof ViewGroup) {
                ((ViewGroup) toggle.getParent()).removeView(toggle);
            }
            if (calendarPanel.getParent() instanceof ViewGroup) {
                ((ViewGroup) calendarPanel.getParent()).removeView(calendarPanel);
            }
        }
    }

    private static final class CalendarEntry {
        final GroceryItem item;
        final long dueAt;
        @NonNull final String cycle;

        CalendarEntry(@NonNull GroceryItem item, long dueAt, @NonNull String cycle) {
            this.item = item;
            this.dueAt = dueAt;
            this.cycle = cycle;
        }
    }

    @NonNull
    private static MaterialButton buildToggleButton(@NonNull Context context) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinHeight(dp(context, 44));
        button.setMinimumHeight(dp(context, 44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setCornerRadius(dp(context, 14));
        button.setStrokeWidth(dp(context, 1));
        button.setStrokeColor(ColorStateList.valueOf(Color.rgb(183, 207, 198)));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(242, 249, 246)));
        button.setTextColor(Color.rgb(15, 108, 89));
        return button;
    }

    @NonNull
    private static android.graphics.drawable.GradientDrawable roundedBackground(
            @NonNull Context context, int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    private static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @NonNull
    private static String dateHeader(long millis) {
        long today = startOfDay(System.currentTimeMillis());
        long target = startOfDay(millis);
        long dayMillis = 24L * 60L * 60L * 1000L;
        long difference = Math.round((target - today) / (double) dayMillis);
        if (difference <= 0L) return "Today";
        if (difference == 1L) return "Tomorrow";
        return new SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                .format(new Date(target));
    }

    @NonNull
    private static String shortDueLabel(long millis) {
        String header = dateHeader(millis);
        if ("Today".equals(header) || "Tomorrow".equals(header)) return header;
        return new SimpleDateFormat("dd MMM", Locale.getDefault())
                .format(new Date(millis));
    }

    @NonNull
    private static String cycleLabel(@Nullable String cycle) {
        String normalized = GroceryRecurrenceEngine.normalizeCycle(cycle);
        if (GroceryItem.LIST_WEEKLY.equals(normalized)) return "Weekly";
        if (GroceryItem.LIST_FORTNIGHTLY.equals(normalized)) return "Fortnightly";
        if (GroceryItem.LIST_MONTHLY.equals(normalized)) return "Monthly";
        return "Daily";
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
