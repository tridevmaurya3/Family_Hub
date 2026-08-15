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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STEP 13G - Local, read-only Smart Finance Insights for the MoneyManager-backed
 * Family Hub Finance screen.
 *
 * The binder compares the selected MoneyManager month with the immediately
 * previous month and surfaces only aggregate observations. It never copies or
 * persists individual transactions, notes, SMS bodies, merchant text, account
 * numbers or balances into Family Hub Room/Firebase.
 */
public final class MoneyManagerSmartFinanceInsightBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final String VIEW_TAG = "money_manager_smart_finance_insights";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final double MIN_MEANINGFUL_CHANGE = 500D;
    private static final double MIN_INCOME_SAVING_CHANGE = 1000D;
    private static final double NEW_CATEGORY_ALERT_AMOUNT = 2000D;

    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, Integer> loadedPeriods =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView, InsightSnapshot> snapshots =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(
                new MoneyManagerSmartFinanceInsightBinder());
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
        TextView insightView = findInsightView(container);
        if (insightView == null) {
            insightView = createInsightView(activity);
            int summaryIndex = container.indexOfChild(analyticsSummary);
            container.addView(insightView, Math.max(1, summaryIndex));
        }

        MoneyManagerFinancePeriodStore.Selection selected =
                MoneyManagerFinancePeriodStore.get(activity);
        int periodKey = periodKey(selected.year, selected.month);
        Integer loaded = loadedPeriods.get(insightView);
        if (loaded == null || loaded != periodKey) {
            loadInsights(activity, insightView, selected.year, selected.month);
        }

        TextView finalInsightView = insightView;
        insightView.setOnClickListener(v -> {
            InsightSnapshot snapshot = snapshots.get(finalInsightView);
            if (snapshot != null && snapshot.available) {
                showDetails(activity, snapshot);
            } else {
                MoneyManagerFinancePeriodStore.Selection current =
                        MoneyManagerFinancePeriodStore.get(activity);
                loadInsights(activity, finalInsightView, current.year, current.month);
            }
        });
    }

    @Nullable
    private TextView findInsightView(@NonNull LinearLayout container) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child instanceof TextView && VIEW_TAG.equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    @NonNull
    private TextView createInsightView(@NonNull Activity activity) {
        TextView view = new TextView(activity);
        view.setTag(VIEW_TAG);
        view.setTextSize(13f);
        view.setTextColor(ContextCompat.getColor(activity, R.color.fh_module_finance));
        view.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription("MoneyManager smart finance insights and alerts");
        view.setText("Smart Finance • Loading MoneyManager insights…");

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 4);
        params.bottomMargin = dp(activity, 4);
        view.setLayoutParams(params);
        return view;
    }

    private void loadInsights(
            @NonNull Activity activity,
            @NonNull TextView view,
            int year,
            int month) {
        int key = periodKey(year, month);
        loadedPeriods.put(view, key);
        snapshots.remove(view);
        view.setText("Smart Finance • Analysing " + monthLabel(year, month) + "…");

        EXECUTOR.execute(() -> {
            MoneyManagerFinanceSummaryBridge.Summary current =
                    MoneyManagerFinanceSummaryBridge.loadPeriod(activity, year, month);
            int previousYear = month == 1 ? year - 1 : year;
            int previousMonth = month == 1 ? 12 : month - 1;
            MoneyManagerFinanceSummaryBridge.Summary previous =
                    MoneyManagerFinanceSummaryBridge.loadPeriod(
                            activity, previousYear, previousMonth);

            InsightSnapshot snapshot = buildSnapshot(
                    current, previous, year, month, previousYear, previousMonth);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                MoneyManagerFinancePeriodStore.Selection selected =
                        MoneyManagerFinancePeriodStore.get(activity);
                Integer requested = loadedPeriods.get(view);
                if (requested == null || requested != key
                        || selected.year != year || selected.month != month) return;

                snapshots.put(view, snapshot);
                renderCompact(view, snapshot);
            });
        });
    }

    @NonNull
    private InsightSnapshot buildSnapshot(
            @NonNull MoneyManagerFinanceSummaryBridge.Summary current,
            @NonNull MoneyManagerFinanceSummaryBridge.Summary previous,
            int year,
            int month,
            int previousYear,
            int previousMonth) {
        if (!current.available) {
            return InsightSnapshot.unavailable(
                    monthLabel(year, month),
                    current.reason.isEmpty()
                            ? "MoneyManager finance data unavailable"
                            : current.reason);
        }

        List<Insight> alerts = new ArrayList<>();
        List<Insight> positives = new ArrayList<>();

        if (current.remaining < -0.005D) {
            alerts.add(new Insight(
                    Severity.HIGH,
                    "Negative saving",
                    "Expense is " + money(Math.abs(current.remaining))
                            + " higher than income for this period."));
        }

        if (previous.available && previous.transactionCount > 0) {
            double expenseDiff = current.expense - previous.expense;
            double expensePct = percentChange(current.expense, previous.expense);
            if (previous.expense > 0D
                    && expenseDiff >= MIN_MEANINGFUL_CHANGE
                    && expensePct >= 20D) {
                alerts.add(new Insight(
                        expensePct >= 40D ? Severity.HIGH : Severity.WATCH,
                        "Expense spike",
                        "Expense increased " + percent(expensePct)
                                + " (" + signedMoney(expenseDiff) + ") vs "
                                + monthLabel(previousYear, previousMonth) + "."));
            }

            double incomeDiff = current.income - previous.income;
            double incomePct = percentChange(current.income, previous.income);
            if (previous.income > 0D
                    && incomeDiff <= -MIN_INCOME_SAVING_CHANGE
                    && incomePct <= -15D) {
                alerts.add(new Insight(
                        incomePct <= -30D ? Severity.HIGH : Severity.WATCH,
                        "Income drop",
                        "Income changed " + percent(incomePct)
                                + " (" + signedMoney(incomeDiff) + ") vs previous month."));
            }

            double savingDiff = current.remaining - previous.remaining;
            double savingPct = percentChange(current.remaining, previous.remaining);
            if (previous.remaining > 0D
                    && savingDiff <= -MIN_INCOME_SAVING_CHANGE
                    && savingPct <= -20D) {
                alerts.add(new Insight(
                        savingPct <= -40D ? Severity.HIGH : Severity.WATCH,
                        "Saving declined",
                        "Monthly saving changed " + percent(savingPct)
                                + " (" + signedMoney(savingDiff) + ")."));
            } else if (previous.remaining >= 0D
                    && savingDiff >= MIN_INCOME_SAVING_CHANGE
                    && (previous.remaining == 0D || savingPct >= 15D)) {
                positives.add(new Insight(
                        Severity.GOOD,
                        "Saving improved",
                        "Saving improved by " + signedMoney(savingDiff)
                                + " compared with the previous month."));
            }

            Insight categorySpike = categorySpike(current, previous);
            if (categorySpike != null) alerts.add(categorySpike);
        }

        if (current.expense > 0D && !current.expenseCategories.isEmpty()) {
            MoneyManagerFinanceSummaryBridge.CategoryTotal top =
                    current.expenseCategories.get(0);
            double share = (top.amount / current.expense) * 100D;
            if (top.amount > 0D && share >= 45D) {
                alerts.add(new Insight(
                        share >= 65D ? Severity.WATCH : Severity.INFO,
                        "Expense concentration",
                        top.label + " is " + percentPlain(share)
                                + " of this period's total expense (" + money(top.amount) + ")."));
            }
        }

        if (!previous.available || previous.transactionCount == 0) {
            positives.add(new Insight(
                    Severity.INFO,
                    "Baseline building",
                    "Previous month has no comparable MoneyManager activity, so trend alerts will improve as data grows."));
        }

        if (alerts.isEmpty() && positives.isEmpty()) {
            positives.add(new Insight(
                    Severity.GOOD,
                    "Stable finance trend",
                    "No meaningful expense spike, income drop or saving deterioration was detected."));
        }

        return new InsightSnapshot(
                true,
                monthLabel(year, month),
                monthLabel(previousYear, previousMonth),
                current,
                previous,
                Collections.unmodifiableList(alerts),
                Collections.unmodifiableList(positives),
                "");
    }

    @Nullable
    private Insight categorySpike(
            @NonNull MoneyManagerFinanceSummaryBridge.Summary current,
            @NonNull MoneyManagerFinanceSummaryBridge.Summary previous) {
        if (current.expenseCategories.isEmpty()) return null;

        Map<String, Double> previousTotals = new HashMap<>();
        for (MoneyManagerFinanceSummaryBridge.CategoryTotal item : previous.expenseCategories) {
            previousTotals.put(item.label.toLowerCase(Locale.ROOT), item.amount);
        }

        String bestLabel = "";
        double bestCurrent = 0D;
        double bestPrevious = 0D;
        double bestDiff = 0D;
        double bestPct = 0D;

        for (MoneyManagerFinanceSummaryBridge.CategoryTotal item : current.expenseCategories) {
            double old = previousTotals.getOrDefault(
                    item.label.toLowerCase(Locale.ROOT), 0D);
            double diff = item.amount - old;
            if (diff < MIN_MEANINGFUL_CHANGE) continue;

            if (old <= 0D) {
                if (item.amount >= NEW_CATEGORY_ALERT_AMOUNT && diff > bestDiff) {
                    bestLabel = item.label;
                    bestCurrent = item.amount;
                    bestPrevious = 0D;
                    bestDiff = diff;
                    bestPct = Double.POSITIVE_INFINITY;
                }
                continue;
            }

            double pct = percentChange(item.amount, old);
            if (pct >= 35D && diff > bestDiff) {
                bestLabel = item.label;
                bestCurrent = item.amount;
                bestPrevious = old;
                bestDiff = diff;
                bestPct = pct;
            }
        }

        if (bestLabel.isEmpty()) return null;
        if (bestPrevious <= 0D) {
            return new Insight(
                    Severity.WATCH,
                    "New high-spend category",
                    bestLabel + " reached " + money(bestCurrent)
                            + " with no comparable spend last month.");
        }
        return new Insight(
                bestPct >= 70D ? Severity.HIGH : Severity.WATCH,
                "Category spending jump",
                bestLabel + " increased " + percent(bestPct)
                        + " (" + signedMoney(bestDiff) + ") vs previous month.");
    }

    private void renderCompact(
            @NonNull TextView view,
            @NonNull InsightSnapshot snapshot) {
        if (!snapshot.available) {
            view.setText("Smart Finance • " + snapshot.reason + " • Tap to retry");
            return;
        }

        List<Insight> combined = new ArrayList<>();
        combined.addAll(snapshot.alerts);
        combined.addAll(snapshot.positives);
        int highCount = 0;
        int watchCount = 0;
        for (Insight insight : snapshot.alerts) {
            if (insight.severity == Severity.HIGH) highCount++;
            else if (insight.severity == Severity.WATCH) watchCount++;
        }

        String status;
        if (highCount > 0) status = "High attention";
        else if (watchCount > 0) status = "Watch";
        else status = "Stable";

        StringBuilder text = new StringBuilder("Smart Finance • ")
                .append(snapshot.periodLabel).append(" • ").append(status);
        int previewCount = Math.min(2, combined.size());
        for (int index = 0; index < previewCount; index++) {
            text.append("\n• ").append(combined.get(index).title);
        }
        text.append("\nTap for all insights & alerts");
        view.setText(text.toString());
    }

    private void showDetails(
            @NonNull Activity activity,
            @NonNull InsightSnapshot snapshot) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        StringBuilder message = new StringBuilder();
        message.append(snapshot.periodLabel)
                .append(" vs ").append(snapshot.previousPeriodLabel).append("\n\n")
                .append("Income  ").append(money(snapshot.current.income)).append('\n')
                .append("Expense  ").append(money(snapshot.current.expense)).append('\n')
                .append("Saving  ").append(money(snapshot.current.remaining)).append("\n\n");

        if (!snapshot.alerts.isEmpty()) {
            message.append("Alerts\n");
            for (Insight insight : snapshot.alerts) {
                message.append(severityLabel(insight.severity))
                        .append(" • ").append(insight.title).append('\n')
                        .append(insight.detail).append("\n\n");
            }
        }

        if (!snapshot.positives.isEmpty()) {
            message.append("Insights\n");
            for (Insight insight : snapshot.positives) {
                message.append("• ").append(insight.title).append('\n')
                        .append(insight.detail).append("\n\n");
            }
        }

        message.append("These are read-only MoneyManager aggregate insights. ")
                .append("No transaction is changed automatically.");

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Smart Finance • " + snapshot.periodLabel)
                .setMessage(message.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private String severityLabel(@NonNull Severity severity) {
        if (severity == Severity.HIGH) return "HIGH";
        if (severity == Severity.WATCH) return "WATCH";
        if (severity == Severity.GOOD) return "GOOD";
        return "INFO";
    }

    private double percentChange(double current, double previous) {
        if (Math.abs(previous) < 0.005D) {
            if (Math.abs(current) < 0.005D) return 0D;
            return current > 0D ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        return ((current - previous) / Math.abs(previous)) * 100D;
    }

    @NonNull
    private String percent(double value) {
        if (Double.isInfinite(value)) return value > 0D ? "new" : "down";
        return String.format(Locale.ENGLISH, "%+.1f%%", value);
    }

    @NonNull
    private String percentPlain(double value) {
        if (Double.isInfinite(value)) return "100%+";
        return String.format(Locale.ENGLISH, "%.1f%%", Math.abs(value));
    }

    @NonNull
    private String signedMoney(double value) {
        return (value >= 0D ? "+" : "−") + money(Math.abs(value));
    }

    @NonNull
    private String money(double value) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        formatter.setMaximumFractionDigits(Math.abs(value - Math.rint(value)) < 0.005D ? 0 : 2);
        return formatter.format(value);
    }

    @NonNull
    private String monthLabel(int year, int month) {
        String[] labels = {
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        };
        int index = Math.max(1, Math.min(12, month)) - 1;
        return labels[index] + " " + year;
    }

    private int periodKey(int year, int month) {
        return year * 100 + month;
    }

    private int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private enum Severity { HIGH, WATCH, INFO, GOOD }

    private static final class Insight {
        @NonNull final Severity severity;
        @NonNull final String title;
        @NonNull final String detail;

        private Insight(
                @NonNull Severity severity,
                @NonNull String title,
                @NonNull String detail) {
            this.severity = severity;
            this.title = title;
            this.detail = detail;
        }
    }

    private static final class InsightSnapshot {
        final boolean available;
        @NonNull final String periodLabel;
        @NonNull final String previousPeriodLabel;
        @NonNull final MoneyManagerFinanceSummaryBridge.Summary current;
        @NonNull final MoneyManagerFinanceSummaryBridge.Summary previous;
        @NonNull final List<Insight> alerts;
        @NonNull final List<Insight> positives;
        @NonNull final String reason;

        private InsightSnapshot(
                boolean available,
                @NonNull String periodLabel,
                @NonNull String previousPeriodLabel,
                @NonNull MoneyManagerFinanceSummaryBridge.Summary current,
                @NonNull MoneyManagerFinanceSummaryBridge.Summary previous,
                @NonNull List<Insight> alerts,
                @NonNull List<Insight> positives,
                @NonNull String reason) {
            this.available = available;
            this.periodLabel = periodLabel;
            this.previousPeriodLabel = previousPeriodLabel;
            this.current = current;
            this.previous = previous;
            this.alerts = alerts;
            this.positives = positives;
            this.reason = reason;
        }

        @NonNull
        static InsightSnapshot unavailable(
                @NonNull String periodLabel,
                @NonNull String reason) {
            MoneyManagerFinanceSummaryBridge.Summary empty =
                    MoneyManagerFinanceSummaryBridge.Summary.unavailable(reason);
            return new InsightSnapshot(
                    false,
                    periodLabel,
                    "previous month",
                    empty,
                    empty,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    reason);
        }
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
