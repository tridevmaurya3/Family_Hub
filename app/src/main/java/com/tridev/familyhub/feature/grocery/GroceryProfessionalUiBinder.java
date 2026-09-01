package com.tridev.familyhub.feature.grocery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * purchase data for the two smart summary cards.
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
            if (page != null && page.overview == overview) return;
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
        if (detail == null || dueValue == null || purchasedValue == null) return null;

        setCardLabel(dueValue, "Due today");
        setCardLabel(purchasedValue, "Purchased this month");
        dueValue.setContentDescription("Grocery items due today");
        purchasedValue.setContentDescription("Grocery items purchased this month");

        PageBinding binding = new PageBinding(
                activity, overview, detail, dueValue, purchasedValue);
        binding.installValueGuards();
        binding.attachConnectionStatus();
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
            binding.activity.runOnUiThread(() -> {
                if (!binding.active
                        || !binding.overview.isAttachedToWindow()
                        || binding.activity.isFinishing()
                        || binding.activity.isDestroyed()) {
                    return;
                }
                binding.latestDueText = dueText;
                binding.latestPurchasedText = purchasedText;
                binding.dueValue.setText(dueText);
                binding.purchasedValue.setText(purchasedText);
            });
        });
    }

    private final class PageBinding {
        private final Activity activity;
        private final View overview;
        private final TextView detail;
        private final TextView dueValue;
        private final TextView purchasedValue;
        private final DatabaseReference connectionReference;
        private final ValueEventListener connectionListener;
        @Nullable private TextWatcher dueGuard;
        @Nullable private TextWatcher purchasedGuard;
        @Nullable private String latestDueText;
        @Nullable private String latestPurchasedText;
        private boolean active = true;

        PageBinding(@NonNull Activity activity,
                    @NonNull View overview,
                    @NonNull TextView detail,
                    @NonNull TextView dueValue,
                    @NonNull TextView purchasedValue) {
            this.activity = activity;
            this.overview = overview;
            this.detail = detail;
            this.dueValue = dueValue;
            this.purchasedValue = purchasedValue;
            connectionReference = FirebaseDatabase.getInstance()
                    .getReference(".info/connected");
            connectionListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    boolean connected = Boolean.TRUE.equals(
                            snapshot.getValue(Boolean.class));
                    updateConnectionStatus(connected);
                    refreshSmartSummary(PageBinding.this);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    updateConnectionStatus(false);
                }
            };
        }

        void installValueGuards() {
            dueGuard = guardValue(dueValue, () -> latestDueText);
            purchasedGuard = guardValue(purchasedValue, () -> latestPurchasedText);
        }

        void attachConnectionStatus() {
            detail.setText("● Connecting • Family sync");
            detail.setTextColor(Color.rgb(84, 93, 105));
            connectionReference.addValueEventListener(connectionListener);
        }

        void updateConnectionStatus(boolean connected) {
            if (!active || !overview.isAttachedToWindow()) return;
            detail.setText(connected
                    ? "● Live • Family sync connected"
                    : "● Offline • Changes sync automatically");
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
}
