package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local read-only bridge for STEP 13C account/card aggregates.
 *
 * Only current-month totals grouped by MoneyManager account/card labels are
 * requested. No transaction rows, notes, merchant data, SMS bodies or balances
 * by individual account are copied into Family Hub.
 */
public final class MoneyManagerAccountAnalyticsBridge {

    private static final String AUTHORITY =
            "com.example.moneymanagerpro.tridev.accountanalytics";
    private static final Uri ENDPOINT = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD = "get_account_breakdown_v1";

    public static final class AccountTotal {
        @NonNull public final String label;
        public final double amount;

        private AccountTotal(@NonNull String label, double amount) {
            this.label = label;
            this.amount = amount;
        }
    }

    public static final class Snapshot {
        public final boolean available;
        @NonNull public final String currency;
        @NonNull public final String periodLabel;
        @NonNull public final String periodStart;
        @NonNull public final String periodEnd;
        public final double expenseTotal;
        public final double incomeTotal;
        @NonNull public final List<AccountTotal> expenseAccounts;
        @NonNull public final List<AccountTotal> incomeAccounts;
        public final long generatedAt;
        @NonNull public final String reason;

        private Snapshot(
                boolean available,
                @Nullable String currency,
                @Nullable String periodLabel,
                @Nullable String periodStart,
                @Nullable String periodEnd,
                double expenseTotal,
                double incomeTotal,
                @NonNull List<AccountTotal> expenseAccounts,
                @NonNull List<AccountTotal> incomeAccounts,
                long generatedAt,
                @Nullable String reason) {
            this.available = available;
            this.currency = safe(currency);
            this.periodLabel = safe(periodLabel);
            this.periodStart = safe(periodStart);
            this.periodEnd = safe(periodEnd);
            this.expenseTotal = expenseTotal;
            this.incomeTotal = incomeTotal;
            this.expenseAccounts = Collections.unmodifiableList(expenseAccounts);
            this.incomeAccounts = Collections.unmodifiableList(incomeAccounts);
            this.generatedAt = Math.max(0L, generatedAt);
            this.reason = safe(reason);
        }

        @NonNull
        public static Snapshot unavailable(@Nullable String reason) {
            return new Snapshot(false, "INR", "", "", "",
                    0D, 0D, new ArrayList<>(), new ArrayList<>(), 0L, reason);
        }
    }

    private MoneyManagerAccountAnalyticsBridge() { }

    /** Call from a worker thread. */
    @NonNull
    public static Snapshot loadCurrentMonth(@NonNull Context context) {
        try {
            Bundle response = context.getApplicationContext()
                    .getContentResolver()
                    .call(ENDPOINT, METHOD, null, null);
            if (response == null || !"OK".equals(safe(response.getString("status")))) {
                return Snapshot.unavailable(response == null
                        ? "MoneyManager did not return account analytics"
                        : safe(response.getString("reason")));
            }

            String currency = safe(response.getString("currency"));
            if (!"INR".equalsIgnoreCase(currency)) {
                return Snapshot.unavailable("Unsupported MoneyManager analytics currency");
            }

            List<AccountTotal> expense = parseTotals(
                    response.getStringArray("expense_account_labels"),
                    response.getLongArray("expense_account_totals_minor"));
            List<AccountTotal> income = parseTotals(
                    response.getStringArray("income_account_labels"),
                    response.getLongArray("income_account_totals_minor"));

            return new Snapshot(
                    true,
                    currency,
                    response.getString("period_label"),
                    response.getString("period_start"),
                    response.getString("period_end"),
                    fromMinor(response.getLong("expense_total_minor", 0L)),
                    fromMinor(response.getLong("income_total_minor", 0L)),
                    expense,
                    income,
                    response.getLong("generated_at", 0L),
                    response.getString("reason"));
        } catch (RuntimeException unavailable) {
            return Snapshot.unavailable("MoneyManager account analytics are unavailable");
        }
    }

    @NonNull
    private static List<AccountTotal> parseTotals(
            @Nullable String[] labels,
            @Nullable long[] totalsMinor) {
        if (labels == null || totalsMinor == null) return new ArrayList<>();
        int size = Math.min(labels.length, totalsMinor.length);
        List<AccountTotal> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            String label = safe(labels[index]);
            if (label.isEmpty()) continue;
            result.add(new AccountTotal(label, fromMinor(totalsMinor[index])));
        }
        return result;
    }

    private static double fromMinor(long amountMinor) {
        return amountMinor / 100D;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
