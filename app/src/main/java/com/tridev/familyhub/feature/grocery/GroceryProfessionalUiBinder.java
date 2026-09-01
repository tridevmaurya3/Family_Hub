package com.tridev.familyhub.feature.grocery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presentation-only Grocery polish.
 *
 * Keeps the existing Grocery Fragment, Room schema, Firebase item paths,
 * recurrence writes, purchase flow, Finance bridge and MoneyManager bridge
 * untouched. It only decorates the visible Grocery screen and reads local
 * purchase data for smart summary, cached/offline and loading presentation.
 */
public final class GroceryProfessionalUiBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final ExecutorService READ_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final Map<Activity, ActivityProbe> probes = new WeakHashMap<>();

    private GroceryProfessionalUiBinder(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(
                new GroceryProfessionalUiBinder(application));
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
        @Nullable private PageBinding page;

        ActivityProbe(@NonNull Activity activity, @NonNull View root) {
            this.activity = activity;
            this.root = root;
            listener = this::checkForGroceryPage;
        }

        void install() {
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
            root.post(this::checkForGroceryPage);
        }

        void checkForGroceryPage() {
            View overview = root.findViewById(R.id.grocery_overview);
            if (overview == null || !overview.isAttachedToWindow()) {
                if (page != null) {
                    page.dispose();
                    page = null;
                }
                return;
            }
            if (page != null && page.overview == overview) {
                page.updateContentState();
                return;
            }
            if (page != null) page.dispose();
            page = createPageBinding(activity, overview);
        }

        void dispose() {
            if (page != null) {
                page.dispose();
                page = null;
            }
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
        }
    }

    @Nullable
    private PageBinding createPageBinding(@NonNull Activity activity,
                                          @NonNull View overview) {
        TextView detail = overview.findViewById(R.id.module_overview_detail);
        TextView dueValue = activity.findViewById(R.id.grocery_budget_value);
        TextView purchasedValue = activity.findViewById(R.id.grocery_actual_value);
        RecyclerView recycler = activity.findViewById(R.id.grocery_recycler_view);
        View emptyState = activity.findViewById(R.id.grocery_empty_state);
        EditText search = activity.findViewById(R.id.grocery_search_input);
        if (detail == null || dueValue == null || purchasedValue == null
                || recycler == null || emptyState == null) return null;

        setCardLabel(dueValue, "Due today");
        setCardLabel(purchasedValue, "Purchased this month");
        dueValue.setContentDescription("Grocery items due today");
        purchasedValue.setContentDescription("Grocery items purchased this month");

        PageBinding binding = new PageBinding(activity, overview, detail,
                dueValue, purchasedValue, recycler, emptyState, search);
        binding.installValueGuards();
        binding.installLoadingAndRetryUi();
        binding.attachConnectionStatus();
        binding.updateContentState();
        refreshSmartSummary(binding);
        return binding;
    }

    private void setCardLabel(@NonNull TextView valueView, @NonNull String label) {
        if (!(valueView.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) valueView.getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof TextView && child != valueView) {
                ((TextView) child).setText(label);
                return;
            }
        }
    }

    private void refreshSmartSummary(@NonNull PageBinding binding) {
        READ_EXECUTOR.execute(() -> {
            FamilyHubDatabase database = FamilyHubDatabase.getInstance(appContext);
            List<GroceryItem> items = database.groceryItemDao().getAll();

            Calendar now = Calendar.getInstance();
            long nowMillis = now.getTimeInMillis();

            Calendar tomorrow = Calendar.getInstance();
            tomorrow.set(Calendar.HOUR_OF_DAY, 0);
            tomorrow.set(Calendar.MINUTE, 0);
            tomorrow.set(Calendar.SECOND, 0);
            tomorrow.set(Calendar.MILLISECOND, 0);
            tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            long tomorrowStart = tomorrow.getTimeInMillis();

            int dueToday = 0;
            for (GroceryItem item : items) {
                if (item == null || item.isPurchased) continue;
                String origin = GroceryRecurrenceEngine.originalCycle(item);
                if (!GroceryRecurrenceEngine.isRecurringType(origin)) {
                    dueToday++;
                    continue;
                }
                if (item.purchasedAt <= 0L) {
                    dueToday++;
                    continue;
                }
                long dueAt = GroceryRecurrenceEngine.nextDueAt(item);
                if (dueAt <= tomorrowStart || dueAt <= nowMillis) dueToday++;
            }

            Calendar monthStart = Calendar.getInstance();
            monthStart.set(Calendar.DAY_OF_MONTH, 1);
            monthStart.set(Calendar.HOUR_OF_DAY, 0);
            monthStart.set(Calendar.MINUTE, 0);
            monthStart.set(Calendar.SECOND, 0);
            monthStart.set(Calendar.MILLISECOND, 0);
            Calendar nextMonth = (Calendar) monthStart.clone();
            nextMonth.add(Calendar.MONTH, 1);
            List<GroceryPurchase> purchases = database.groceryPurchaseDao().getForPeriod(
                    monthStart.getTimeInMillis(), nextMonth.getTimeInMillis());
            int purchasedThisMonth = purchases == null ? 0 : purchases.size();

            final String dueText = String.valueOf(dueToday);
            final String purchasedText = String.valueOf(purchasedThisMonth);
            final int cachedCount = items == null ? 0 : items.size();
            binding.activity.runOnUiThread(() -> {
                if (!binding.active
                        || !binding.overview.isAttachedToWindow()
                        || binding.activity.isFinishing()
                        || binding.activity.isDestroyed()) {
                    return;
                }
                binding.latestDueText = dueText;
                binding.latestPurchasedText = purchasedText;
                binding.cachedItemCount = cachedCount;
                binding.dueValue.setText(dueText);
                binding.purchasedValue.setText(purchasedText);
                binding.updateConnectionStatus(binding.connected);
                binding.updateContentState();
            });
        });
    }

    private final class PageBinding {
        private final Activity activity;
        private final View overview;
        private final TextView detail;
        private final TextView dueValue;
        private final TextView purchasedValue;
        private final RecyclerView recycler;
        private final View emptyState;
        @Nullable private final EditText search;
        private final DatabaseReference connectionReference;
        private final ValueEventListener connectionListener;
        @Nullable private TextWatcher dueGuard;
        @Nullable private TextWatcher purchasedGuard;
        @Nullable private String latestDueText;
        @Nullable private String latestPurchasedText;
        @Nullable private LinearLayout loadingSkeleton;
        @Nullable private MaterialButton retryButton;
        @Nullable private ViewGroup loadingParent;
        private boolean active = true;
        private boolean connected;
        private boolean firstLoadPending = true;
        private boolean retryLoading;
        private int cachedItemCount;

        PageBinding(@NonNull Activity activity,
                    @NonNull View overview,
                    @NonNull TextView detail,
                    @NonNull TextView dueValue,
                    @NonNull TextView purchasedValue,
                    @NonNull RecyclerView recycler,
                    @NonNull View emptyState,
                    @Nullable EditText search) {
            this.activity = activity;
            this.overview = overview;
            this.detail = detail;
            this.dueValue = dueValue;
            this.purchasedValue = purchasedValue;
            this.recycler = recycler;
            this.emptyState = emptyState;
            this.search = search;
            connectionReference = FirebaseDatabase.getInstance()
                    .getReference(".info/connected");
            connectionListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                    updateConnectionStatus(connected);
                    updateContentState();
                    refreshSmartSummary(PageBinding.this);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    connected = false;
                    updateConnectionStatus(false);
                    updateContentState();
                }
            };
        }

        void installValueGuards() {
            dueGuard = guardValue(dueValue, () -> latestDueText);
            purchasedGuard = guardValue(purchasedValue, () -> latestPurchasedText);
        }

        void installLoadingAndRetryUi() {
            if (recycler.getParent() instanceof ConstraintLayout) {
                ConstraintLayout parent = (ConstraintLayout) recycler.getParent();
                LinearLayout skeleton = new LinearLayout(activity);
                skeleton.setId(View.generateViewId());
                skeleton.setOrientation(LinearLayout.VERTICAL);
                skeleton.setPadding(dp(14), dp(12), dp(14), dp(12));
                skeleton.setBackground(roundedBackground(
                        Color.rgb(248, 250, 251), Color.rgb(222, 228, 232), 16));
                skeleton.setVisibility(View.GONE);
                skeleton.setContentDescription("Loading saved Grocery data");

                TextView loadingTitle = new TextView(activity);
                loadingTitle.setText("Loading saved Grocery data…");
                loadingTitle.setTextSize(12f);
                loadingTitle.setTextColor(Color.rgb(84, 93, 105));
                loadingTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                skeleton.addView(loadingTitle, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

                int[] widths = {92, 76, 84};
                for (int width : widths) {
                    View bar = new View(activity);
                    bar.setBackground(roundedBackground(
                            Color.rgb(228, 233, 236), Color.TRANSPARENT, 8));
                    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                            dp(width), dp(15));
                    barParams.topMargin = dp(9);
                    skeleton.addView(bar, barParams);
                }

                ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                params.topToBottom = R.id.grocery_action_scroll;
                params.topMargin = dp(12);
                parent.addView(skeleton, params);
                loadingSkeleton = skeleton;
                loadingParent = parent;
            }

            if (emptyState instanceof ViewGroup && ((ViewGroup) emptyState).getChildCount() > 0) {
                View child = ((ViewGroup) emptyState).getChildAt(0);
                if (child instanceof LinearLayout) {
                    LinearLayout content = (LinearLayout) child;
                    MaterialButton retry = new MaterialButton(activity);
                    retry.setText("Retry sync");
                    retry.setAllCaps(false);
                    retry.setTextSize(12f);
                    retry.setMinHeight(dp(44));
                    retry.setMinimumHeight(dp(44));
                    retry.setCornerRadius(dp(14));
                    retry.setStrokeWidth(dp(1));
                    retry.setStrokeColor(android.content.res.ColorStateList.valueOf(
                            Color.rgb(184, 149, 72)));
                    retry.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            Color.rgb(255, 248, 231)));
                    retry.setTextColor(Color.rgb(132, 84, 18));
                    retry.setVisibility(View.GONE);
                    retry.setContentDescription("Retry loading Grocery data and family sync");
                    LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
                    retryParams.topMargin = dp(8);
                    content.addView(retry, retryParams);
                    retry.setOnClickListener(v -> retryCurrentLoad());
                    retryButton = retry;
                }
            }
        }

        void retryCurrentLoad() {
            if (!active) return;
            retryLoading = true;
            firstLoadPending = true;
            emptyState.setVisibility(View.GONE);
            showSkeleton(true);
            detail.setText(connected
                    ? "● Live • Refreshing saved data"
                    : "● Offline • Retrying with saved data");
            if (search != null) {
                String current = search.getText() == null
                        ? "" : search.getText().toString();
                search.setText(current);
                search.setSelection(search.length());
            } else {
                overview.postDelayed(() -> {
                    retryLoading = false;
                    updateContentState();
                }, 500L);
            }
        }

        void updateContentState() {
            if (!active || !overview.isAttachedToWindow()) return;
            RecyclerView.Adapter<?> currentAdapter = recycler.getAdapter();
            int count = currentAdapter == null ? 0 : currentAdapter.getItemCount();
            boolean contentVisible = count > 0 && recycler.getVisibility() == View.VISIBLE;
            boolean emptyVisible = emptyState.getVisibility() == View.VISIBLE;

            if (contentVisible || emptyVisible) {
                firstLoadPending = false;
                retryLoading = false;
                showSkeleton(false);
            } else if (firstLoadPending || retryLoading) {
                showSkeleton(true);
            } else {
                showSkeleton(false);
            }

            if (retryButton != null) {
                retryButton.setVisibility(!connected && emptyVisible
                        ? View.VISIBLE : View.GONE);
            }
            updateConnectionStatus(connected);
        }

        void showSkeleton(boolean visible) {
            if (loadingSkeleton == null) return;
            loadingSkeleton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        void attachConnectionStatus() {
            detail.setText("● Connecting • Loading saved data");
            detail.setTextColor(Color.rgb(84, 93, 105));
            connectionReference.addValueEventListener(connectionListener);
        }

        void updateConnectionStatus(boolean isConnected) {
            if (!active || !overview.isAttachedToWindow()) return;
            connected = isConnected;
            RecyclerView.Adapter<?> currentAdapter = recycler.getAdapter();
            int visibleCount = currentAdapter == null ? 0 : currentAdapter.getItemCount();
            if (retryLoading) {
                detail.setText(connected
                        ? "● Live • Refreshing saved data"
                        : "● Offline • Retrying with saved data");
            } else if (connected) {
                detail.setText("● Live • Family sync connected");
            } else if (visibleCount > 0 || cachedItemCount > 0) {
                detail.setText("● Offline • Showing saved data");
            } else {
                detail.setText("● Offline • No saved items • Retry available");
            }
            detail.setTextColor(connected
                    ? Color.rgb(15, 122, 90)
                    : Color.rgb(176, 98, 34));
        }

        void dispose() {
            if (!active) return;
            active = false;
            connectionReference.removeEventListener(connectionListener);
            if (dueGuard != null) dueValue.removeTextChangedListener(dueGuard);
            if (purchasedGuard != null) purchasedValue.removeTextChangedListener(purchasedGuard);
            if (loadingParent != null && loadingSkeleton != null) {
                loadingParent.removeView(loadingSkeleton);
            }
            if (retryButton != null && retryButton.getParent() instanceof ViewGroup) {
                ((ViewGroup) retryButton.getParent()).removeView(retryButton);
            }
        }
    }

    private interface TextSupplier {
        @Nullable String get();
    }

    private TextWatcher guardValue(@NonNull TextView view,
                                   @NonNull TextSupplier supplier) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                String expected = supplier.get();
                if (expected == null || expected.contentEquals(editable)) return;
                view.post(() -> {
                    String latest = supplier.get();
                    if (latest != null && !latest.contentEquals(view.getText())) {
                        view.setText(latest);
                    }
                });
            }
        };
        view.addTextChangedListener(watcher);
        return watcher;
    }

    private android.graphics.drawable.GradientDrawable roundedBackground(
            int fillColor, int strokeColor, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * appContext.getResources()
                .getDisplayMetrics().density);
    }
}
