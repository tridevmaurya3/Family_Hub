package com.tridev.familyhub.feature.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STEP 13C UI binder.
 *
 * The existing Linked Accounts card remains compact. Tapping it opens a
 * read-only MoneyManager current-month account/card breakdown. Family Hub does
 * not persist or upload these aggregates.
 */
public final class MoneyManagerAccountAnalyticsBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Map<View, Boolean> boundCards =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(
                new MoneyManagerAccountAnalyticsBinder());
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
        View card = root.findViewById(R.id.finance_accounts_card);
        TextView summary = root.findViewById(R.id.finance_accounts_summary);
        if (card == null || summary == null) return;
        if (boundCards.put(card, Boolean.TRUE) != null) return;

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(
                "Linked MoneyManager accounts. Tap for account and card breakdown.");
        summary.setContentDescription(
                "MoneyManager linked account summary. Tap for account and card breakdown.");

        View.OnClickListener click = ignored -> loadAndShow(activity, card);
        card.setOnClickListener(click);
        summary.setOnClickListener(click);
    }

    private void loadAndShow(@NonNull Activity activity, @NonNull View anchor) {
        EXECUTOR.execute(() -> {
            MoneyManagerAccountAnalyticsBridge.Snapshot snapshot =
                    MoneyManagerAccountAnalyticsBridge.loadCurrentMonth(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!snapshot.available) {
                    String reason = snapshot.reason.isEmpty()
                            ? "MoneyManager account analytics are unavailable"
                            : snapshot.reason;
                    Snackbar.make(anchor, reason, Snackbar.LENGTH_LONG).show();
                    return;
                }
                showBreakdown(activity, snapshot);
            });
        });
    }

    private void showBreakdown(
            @NonNull Activity activity,
            @NonNull MoneyManagerAccountAnalyticsBridge.Snapshot snapshot) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        StringBuilder body = new StringBuilder()
                .append("MoneyManager • ")
                .append(snapshot.periodLabel.isEmpty()
                        ? "Current month"
                        : snapshot.periodLabel)
                .append("\n\nExpense by account / card\n");

        appendTotals(body, snapshot.expenseAccounts, formatter);
        body.append("\nExpense total • ")
                .append(formatter.format(snapshot.expenseTotal));

        body.append("\n\nIncome received into accounts\n");
        appendTotals(body, snapshot.incomeAccounts, formatter);
        body.append("\nIncome total • ")
                .append(formatter.format(snapshot.incomeTotal));

        body.append("\n\nRead-only aggregate from MoneyManager. Individual transactions, notes, SMS data and per-account balances are not shared.");

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Account & Card Breakdown")
                .setMessage(body.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void appendTotals(
            @NonNull StringBuilder body,
            @NonNull java.util.List<MoneyManagerAccountAnalyticsBridge.AccountTotal> totals,
            @NonNull NumberFormat formatter) {
        if (totals.isEmpty()) {
            body.append("No entries");
            return;
        }
        for (MoneyManagerAccountAnalyticsBridge.AccountTotal item : totals) {
            body.append("• ")
                    .append(item.label)
                    .append(" — ")
                    .append(formatter.format(item.amount))
                    .append('\n');
        }
        if (body.length() > 0 && body.charAt(body.length() - 1) == '\n') {
            body.setLength(body.length() - 1);
        }
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        View root = activity.getWindow().getDecorView();
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    @Override public void onActivityCreated(
            @NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(
            @NonNull Activity activity, @NonNull Bundle outState) { }
}
