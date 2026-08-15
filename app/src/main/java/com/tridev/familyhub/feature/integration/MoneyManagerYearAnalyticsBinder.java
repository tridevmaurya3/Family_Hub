package com.tridev.familyhub.feature.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STEP 13E yearly Finance dashboard binder.
 *
 * A compact annual MoneyManager summary is injected into the existing Finance
 * Analytics card. Tapping it opens a 12-month Income/Expense/Remaining
 * comparison. Aggregates are read-only and are never persisted to Family Hub
 * Room/Firebase.
 */
public final class MoneyManagerYearAnalyticsBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final String VIEW_TAG = "money_manager_year_analytics";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, Integer> loadedYears =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, MoneyManagerYearAnalyticsBridge.Snapshot> snapshots =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(new MoneyManagerYearAnalyticsBinder());
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        View root = activity.getWindow().getDecorView();
        scan(activity, root);
        if (listeners.containsKey(activity)) return;

        ViewTreeObserver.OnGlobalLayoutListener listener = () -> scan(activity, root);
        listeners.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private void scan(@NonNull Activity activity, @NonNull View root) {
        TextView analyticsSummary = root.findViewById(R.id.finance_analytics_summary);
        if (analyticsSummary == null || !(analyticsSummary.getParent() instanceof LinearLayout)) {
            return;
        }

        LinearLayout container = (LinearLayout) analyticsSummary.getParent();
        TextView yearly = findYearlyView(container);
        if (yearly == null) {
            yearly = createYearlyView(activity);
            int summaryIndex = container.indexOfChild(analyticsSummary);
            container.addView(yearly, Math.max(1, summaryIndex));
        }

        MoneyManagerFinancePeriodStore.Selection selected =
                MoneyManagerFinancePeriodStore.get(activity);
        Integer loadedYear = loadedYears.get(yearly);
        if (loadedYear == null || loadedYear != selected.year) {
            loadYear(activity, yearly, selected.year);
        }

        TextView finalYearly = yearly;
        yearly.setOnClickListener(v -> {
            MoneyManagerYearAnalyticsBridge.Snapshot snapshot = snapshots.get(finalYearly);
            if (snapshot != null && snapshot.available) {
                showDetails(activity, snapshot);
            } else {
                loadYear(activity, finalYearly,
                        MoneyManagerFinancePeriodStore.get(activity).year);
            }
        });
    }

    @Nullable
    private TextView findYearlyView(@NonNull LinearLayout container) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child instanceof TextView && VIEW_TAG.equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    @NonNull
    private TextView createYearlyView(@NonNull Activity activity) {
        TextView view = new TextView(activity);
        view.setTag(VIEW_TAG);
        view.setTextSize(13f);
        view.setTextColor(ContextCompat.getColor(activity, R.color.fh_module_finance));
        view.setPadding(dp(activity, 10), dp(activity, 8),
                dp(activity, 10), dp(activity, 8));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription("MoneyManager yearly finance comparison");
        view.setText("Loading MoneyManager yearly summary…");

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 4);
        params.bottomMargin = dp(activity, 4);
        view.setLayoutParams(params);
        return view;
    }

    private void loadYear(
            @NonNull Activity activity,
            @NonNull TextView view,
            int year) {
        loadedYears.put(view, year);
        snapshots.remove(view);
        view.setText("Year " + year + " • Loading MoneyManager comparison…");

        EXECUTOR.execute(() -> {
            MoneyManagerYearAnalyticsBridge.Snapshot snapshot =
                    MoneyManagerYearAnalyticsBridge.loadYear(activity, year);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Integer requested = loadedYears.get(view);
                MoneyManagerFinancePeriodStore.Selection selected =
                        MoneyManagerFinancePeriodStore.get(activity);
                if (requested == null || requested != year || selected.year != year) return;

                snapshots.put(view, snapshot);
                if (snapshot.available) {
                    renderCompact(view, snapshot);
                } else {
                    String reason = snapshot.reason.isEmpty()
                            ? "MoneyManager yearly summary unavailable"
                            : snapshot.reason;
                    view.setText("Year " + year + " • " + reason + " • Tap to retry");
                }
            });
        });
    }

    private void renderCompact(
            @NonNull TextView view,
            @NonNull MoneyManagerYearAnalyticsBridge.Snapshot snapshot) {
        String text = "Year " + snapshot.year
                + " • Income " + money(snapshot.totalIncome)
                + " • Expense " + money(snapshot.totalExpense)
                + " • Saving " + money(snapshot.totalRemaining)
                + "\nTap for 12-month comparison";
        view.setText(text);
    }

    private void showDetails(
            @NonNull Activity activity,
            @NonNull MoneyManagerYearAnalyticsBridge.Snapshot snapshot) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        StringBuilder message = new StringBuilder();
        message.append("Total Income  ").append(money(snapshot.totalIncome)).append('\n')
                .append("Total Expense  ").append(money(snapshot.totalExpense)).append('\n')
                .append("Total Saving  ").append(money(snapshot.totalRemaining)).append('\n')
                .append("Posted income/expense entries  ")
                .append(snapshot.transactionCount).append("\n\n")
                .append("Month-to-month comparison\n");

        int bestSavingMonth = -1;
        int highestExpenseMonth = -1;
        double bestSaving = -Double.MAX_VALUE;
        double highestExpense = -1D;

        for (int index = 0; index < 12; index++) {
            String label = safeMonth(snapshot, index);
            int count = snapshot.monthTransactionCounts[index];
            if (count <= 0) {
                message.append(label).append("  — no activity\n");
                continue;
            }

            double saving = snapshot.monthRemaining[index];
            double expense = snapshot.monthExpense[index];
            message.append(label)
                    .append("  In ").append(money(snapshot.monthIncome[index]))
                    .append(" • Out ").append(money(expense))
                    .append(" • Save ").append(money(saving))
                    .append('\n');

            if (saving > bestSaving) {
                bestSaving = saving;
                bestSavingMonth = index;
            }
            if (expense > highestExpense) {
                highestExpense = expense;
                highestExpenseMonth = index;
            }
        }

        if (bestSavingMonth >= 0 || highestExpenseMonth >= 0) {
            message.append("\nYear insight\n");
            if (bestSavingMonth >= 0) {
                message.append("Best saving month • ")
                        .append(safeMonth(snapshot, bestSavingMonth))
                        .append(" • ").append(money(bestSaving)).append('\n');
            }
            if (highestExpenseMonth >= 0) {
                message.append("Highest expense month • ")
                        .append(safeMonth(snapshot, highestExpenseMonth))
                        .append(" • ").append(money(highestExpense));
            }
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle("MoneyManager • " + snapshot.year + " yearly finance")
                .setMessage(message.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private String safeMonth(
            @NonNull MoneyManagerYearAnalyticsBridge.Snapshot snapshot,
            int index) {
        if (index >= 0 && index < snapshot.monthLabels.length) {
            String value = snapshot.monthLabels[index];
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "Month " + (index + 1);
    }

    @NonNull
    private String money(double value) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        formatter.setMaximumFractionDigits(Math.abs(value - Math.rint(value)) < 0.005 ? 0 : 2);
        return formatter.format(value);
    }

    private int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(
            @NonNull Activity activity, @NonNull Bundle outState) { }
    @Override public void onActivityCreated(
            @NonNull Activity activity, @Nullable Bundle savedInstanceState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener == null) return;
        View root = activity.getWindow().getDecorView();
        if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }
}
