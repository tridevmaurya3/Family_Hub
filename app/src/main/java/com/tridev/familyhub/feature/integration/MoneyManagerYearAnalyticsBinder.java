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
 * STEP 13E/13F yearly Finance dashboard binder.
 *
 * A compact annual MoneyManager summary is injected into the existing Finance
 * Analytics card. STEP 13F compares the selected year with the previous year
 * using the same read-only MoneyManager yearly aggregate endpoint. No
 * transaction rows, notes, merchant data, account numbers or SMS bodies are
 * copied into Family Hub Room/Firebase.
 */
public final class MoneyManagerYearAnalyticsBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final String VIEW_TAG = "money_manager_year_analytics";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, Integer> loadedYears =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, Comparison> comparisons =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Comparison {
        @NonNull final MoneyManagerYearAnalyticsBridge.Snapshot current;
        @NonNull final MoneyManagerYearAnalyticsBridge.Snapshot previous;

        private Comparison(
                @NonNull MoneyManagerYearAnalyticsBridge.Snapshot current,
                @NonNull MoneyManagerYearAnalyticsBridge.Snapshot previous) {
            this.current = current;
            this.previous = previous;
        }
    }

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
            loadComparison(activity, yearly, selected.year);
        }

        TextView finalYearly = yearly;
        yearly.setOnClickListener(v -> {
            Comparison comparison = comparisons.get(finalYearly);
            if (comparison != null && comparison.current.available) {
                showDetails(activity, comparison);
            } else {
                loadComparison(activity, finalYearly,
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
        view.setContentDescription("MoneyManager yearly finance and year over year comparison");
        view.setText("Loading MoneyManager yearly comparison…");

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 4);
        params.bottomMargin = dp(activity, 4);
        view.setLayoutParams(params);
        return view;
    }

    private void loadComparison(
            @NonNull Activity activity,
            @NonNull TextView view,
            int year) {
        loadedYears.put(view, year);
        comparisons.remove(view);
        view.setText("Year " + year + " • Loading MoneyManager comparison…");

        EXECUTOR.execute(() -> {
            MoneyManagerYearAnalyticsBridge.Snapshot current =
                    MoneyManagerYearAnalyticsBridge.loadYear(activity, year);
            MoneyManagerYearAnalyticsBridge.Snapshot previous = year > 2000
                    ? MoneyManagerYearAnalyticsBridge.loadYear(activity, year - 1)
                    : MoneyManagerYearAnalyticsBridge.Snapshot.unavailable(
                            year - 1, "Previous year is outside supported range");
            Comparison comparison = new Comparison(current, previous);

            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Integer requested = loadedYears.get(view);
                MoneyManagerFinancePeriodStore.Selection selected =
                        MoneyManagerFinancePeriodStore.get(activity);
                if (requested == null || requested != year || selected.year != year) return;

                comparisons.put(view, comparison);
                if (current.available) {
                    renderCompact(view, comparison);
                } else {
                    String reason = current.reason.isEmpty()
                            ? "MoneyManager yearly summary unavailable"
                            : current.reason;
                    view.setText("Year " + year + " • " + reason + " • Tap to retry");
                }
            });
        });
    }

    private void renderCompact(
            @NonNull TextView view,
            @NonNull Comparison comparison) {
        MoneyManagerYearAnalyticsBridge.Snapshot current = comparison.current;
        StringBuilder text = new StringBuilder();
        text.append("Year ").append(current.year)
                .append(" • Income ").append(money(current.totalIncome))
                .append(" • Expense ").append(money(current.totalExpense))
                .append(" • Saving ").append(money(current.totalRemaining));

        if (comparison.previous.available) {
            text.append('\n')
                    .append("vs ").append(comparison.previous.year)
                    .append(" • Income ").append(changeLabel(
                            current.totalIncome, comparison.previous.totalIncome))
                    .append(" • Expense ").append(changeLabel(
                            current.totalExpense, comparison.previous.totalExpense))
                    .append(" • Saving ").append(changeLabel(
                            current.totalRemaining, comparison.previous.totalRemaining));
        }
        text.append("\nTap for 12-month + year-over-year comparison");
        view.setText(text.toString());
    }

    private void showDetails(
            @NonNull Activity activity,
            @NonNull Comparison comparison) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        MoneyManagerYearAnalyticsBridge.Snapshot current = comparison.current;
        MoneyManagerYearAnalyticsBridge.Snapshot previous = comparison.previous;
        StringBuilder message = new StringBuilder();
        message.append(current.year).append(" totals\n")
                .append("Income  ").append(money(current.totalIncome)).append('\n')
                .append("Expense  ").append(money(current.totalExpense)).append('\n')
                .append("Saving  ").append(money(current.totalRemaining)).append('\n')
                .append("Posted income/expense entries  ")
                .append(current.transactionCount).append("\n\n");

        if (previous.available) {
            message.append("Year-over-year • vs ").append(previous.year).append('\n')
                    .append("Income change  ").append(changeLabel(
                            current.totalIncome, previous.totalIncome))
                    .append(" • ").append(moneyDelta(
                            current.totalIncome - previous.totalIncome)).append('\n')
                    .append("Expense change  ").append(changeLabel(
                            current.totalExpense, previous.totalExpense))
                    .append(" • ").append(moneyDelta(
                            current.totalExpense - previous.totalExpense)).append('\n')
                    .append("Saving change  ").append(changeLabel(
                            current.totalRemaining, previous.totalRemaining))
                    .append(" • ").append(moneyDelta(
                            current.totalRemaining - previous.totalRemaining))
                    .append("\n\n");
        }

        message.append("Month-to-month comparison\n");

        int bestSavingMonth = -1;
        int highestExpenseMonth = -1;
        int biggestExpenseRiseMonth = -1;
        int biggestExpenseDropMonth = -1;
        double bestSaving = -Double.MAX_VALUE;
        double highestExpense = -1D;
        double biggestExpenseRise = 0D;
        double biggestExpenseDrop = 0D;

        for (int index = 0; index < 12; index++) {
            String label = safeMonth(current, index);
            int count = current.monthTransactionCounts[index];
            int previousCount = previous.available
                    ? previous.monthTransactionCounts[index]
                    : 0;
            if (count <= 0 && previousCount <= 0) {
                message.append(label).append("  — no activity\n");
                continue;
            }

            double saving = current.monthRemaining[index];
            double expense = current.monthExpense[index];
            message.append(label)
                    .append("  In ").append(money(current.monthIncome[index]))
                    .append(" • Out ").append(money(expense))
                    .append(" • Save ").append(money(saving));

            if (previous.available) {
                double expenseDelta = expense - previous.monthExpense[index];
                message.append(" • Out vs ").append(previous.year).append(' ')
                        .append(moneyDelta(expenseDelta));

                if (expenseDelta > biggestExpenseRise) {
                    biggestExpenseRise = expenseDelta;
                    biggestExpenseRiseMonth = index;
                }
                if (expenseDelta < biggestExpenseDrop) {
                    biggestExpenseDrop = expenseDelta;
                    biggestExpenseDropMonth = index;
                }
            }
            message.append('\n');

            if (count > 0 && saving > bestSaving) {
                bestSaving = saving;
                bestSavingMonth = index;
            }
            if (count > 0 && expense > highestExpense) {
                highestExpense = expense;
                highestExpenseMonth = index;
            }
        }

        if (bestSavingMonth >= 0 || highestExpenseMonth >= 0
                || biggestExpenseRiseMonth >= 0 || biggestExpenseDropMonth >= 0) {
            message.append("\nTrend insight\n");
            if (bestSavingMonth >= 0) {
                message.append("Best saving month • ")
                        .append(safeMonth(current, bestSavingMonth))
                        .append(" • ").append(money(bestSaving)).append('\n');
            }
            if (highestExpenseMonth >= 0) {
                message.append("Highest expense month • ")
                        .append(safeMonth(current, highestExpenseMonth))
                        .append(" • ").append(money(highestExpense)).append('\n');
            }
            if (biggestExpenseRiseMonth >= 0) {
                message.append("Largest expense rise vs ").append(previous.year).append(" • ")
                        .append(safeMonth(current, biggestExpenseRiseMonth))
                        .append(" • ").append(moneyDelta(biggestExpenseRise)).append('\n');
            }
            if (biggestExpenseDropMonth >= 0) {
                message.append("Largest expense reduction vs ").append(previous.year).append(" • ")
                        .append(safeMonth(current, biggestExpenseDropMonth))
                        .append(" • ").append(moneyDelta(biggestExpenseDrop));
            }
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle("MoneyManager • " + current.year + " finance trend")
                .setMessage(message.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private String changeLabel(double current, double previous) {
        double delta = current - previous;
        if (Math.abs(previous) < 0.005D) {
            if (Math.abs(current) < 0.005D) return "0%";
            return delta > 0D ? "new" : "changed";
        }
        double percent = (delta / Math.abs(previous)) * 100D;
        return String.format(Locale.ENGLISH, "%+.1f%%", percent);
    }

    @NonNull
    private String moneyDelta(double value) {
        if (Math.abs(value) < 0.005D) return "₹0";
        return (value > 0D ? "+" : "−") + money(Math.abs(value));
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
