package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * STEP 13E read-only yearly finance bridge.
 *
 * Only annual/monthly aggregate Income, Expense and Remaining totals are read
 * from MoneyManagerPro. No transaction rows, notes, merchant data, SMS bodies,
 * account numbers or per-account balances are copied into Family Hub.
 */
public final class MoneyManagerYearAnalyticsBridge {

    private static final Uri ENDPOINT = Uri.parse(
            "content://com.example.moneymanagerpro.tridev.accountanalytics");
    private static final String METHOD = "get_year_finance_v1";

    public static final class Snapshot {
        public final boolean available;
        public final int year;
        @NonNull public final String currency;
        public final double totalIncome;
        public final double totalExpense;
        public final double totalRemaining;
        public final int transactionCount;
        @NonNull public final String[] monthLabels;
        @NonNull public final double[] monthIncome;
        @NonNull public final double[] monthExpense;
        @NonNull public final double[] monthRemaining;
        @NonNull public final int[] monthTransactionCounts;
        public final long generatedAt;
        @NonNull public final String reason;

        private Snapshot(
                boolean available,
                int year,
                @Nullable String currency,
                double totalIncome,
                double totalExpense,
                double totalRemaining,
                int transactionCount,
                @NonNull String[] monthLabels,
                @NonNull double[] monthIncome,
                @NonNull double[] monthExpense,
                @NonNull double[] monthRemaining,
                @NonNull int[] monthTransactionCounts,
                long generatedAt,
                @Nullable String reason) {
            this.available = available;
            this.year = year;
            this.currency = safe(currency);
            this.totalIncome = totalIncome;
            this.totalExpense = totalExpense;
            this.totalRemaining = totalRemaining;
            this.transactionCount = Math.max(0, transactionCount);
            this.monthLabels = monthLabels;
            this.monthIncome = monthIncome;
            this.monthExpense = monthExpense;
            this.monthRemaining = monthRemaining;
            this.monthTransactionCounts = monthTransactionCounts;
            this.generatedAt = Math.max(0L, generatedAt);
            this.reason = safe(reason);
        }

        @NonNull
        public static Snapshot unavailable(int year, @Nullable String reason) {
            return new Snapshot(false, year, "INR", 0D, 0D, 0D, 0,
                    defaultLabels(), new double[12], new double[12],
                    new double[12], new int[12], 0L, reason);
        }
    }

    private MoneyManagerYearAnalyticsBridge() { }

    /** Call from a worker thread. */
    @NonNull
    public static Snapshot loadYear(@NonNull Context context, int year) {
        if (year < 2000 || year > 2100) {
            return Snapshot.unavailable(year, "Invalid finance year");
        }
        try {
            Bundle request = new Bundle();
            request.putInt("year", year);
            Bundle response = context.getApplicationContext().getContentResolver()
                    .call(ENDPOINT, METHOD, null, request);
            if (response == null || !"OK".equals(safe(response.getString("status")))) {
                return Snapshot.unavailable(year, response == null
                        ? "MoneyManager did not return yearly analytics"
                        : safe(response.getString("reason")));
            }

            String currency = safe(response.getString("currency"));
            if (!"INR".equalsIgnoreCase(currency)) {
                return Snapshot.unavailable(year,
                        "Unsupported MoneyManager yearly analytics currency");
            }

            String[] labels = response.getStringArray("month_labels");
            long[] incomeMinor = response.getLongArray("month_income_minor");
            long[] expenseMinor = response.getLongArray("month_expense_minor");
            long[] remainingMinor = response.getLongArray("month_remaining_minor");
            int[] counts = response.getIntArray("month_transaction_counts");
            if (!validTwelve(labels) || !validTwelve(incomeMinor)
                    || !validTwelve(expenseMinor) || !validTwelve(remainingMinor)
                    || !validTwelve(counts)) {
                return Snapshot.unavailable(year,
                        "MoneyManager yearly analytics payload is incomplete");
            }

            return new Snapshot(
                    true,
                    response.getInt("year", year),
                    currency,
                    fromMinor(response.getLong("total_income_minor", 0L)),
                    fromMinor(response.getLong("total_expense_minor", 0L)),
                    fromMinor(response.getLong("total_remaining_minor", 0L)),
                    response.getInt("transaction_count", 0),
                    labels.clone(),
                    fromMinor(incomeMinor),
                    fromMinor(expenseMinor),
                    fromMinor(remainingMinor),
                    counts.clone(),
                    response.getLong("generated_at", 0L),
                    response.getString("reason"));
        } catch (RuntimeException unavailable) {
            return Snapshot.unavailable(year,
                    "MoneyManager yearly finance analytics are unavailable");
        }
    }

    private static boolean validTwelve(@Nullable Object[] values) {
        return values != null && values.length == 12;
    }

    private static boolean validTwelve(@Nullable long[] values) {
        return values != null && values.length == 12;
    }

    private static boolean validTwelve(@Nullable int[] values) {
        return values != null && values.length == 12;
    }

    @NonNull
    private static double[] fromMinor(@NonNull long[] values) {
        double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = fromMinor(values[index]);
        }
        return result;
    }

    private static double fromMinor(long value) {
        return value / 100D;
    }

    @NonNull
    private static String[] defaultLabels() {
        return new String[]{
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        };
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
