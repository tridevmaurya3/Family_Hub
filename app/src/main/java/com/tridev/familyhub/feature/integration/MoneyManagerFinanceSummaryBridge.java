package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Local, read-only aggregate finance bridge.
 *
 * The summary is read directly from MoneyManagerPro on this device and is never
 * uploaded to Family Hub cloud/shared finance. No individual transaction rows,
 * notes or per-account balances are requested.
 */
public final class MoneyManagerFinanceSummaryBridge {

    private static final Uri ENDPOINT = Uri.parse(
            "content://" + MoneyManagerMasterCatalogBridge.AUTHORITY);
    private static final String METHOD = "get_finance_summary_v1";

    public static final class Summary {
        public final boolean available;
        public final double income;
        public final double expense;
        public final double remaining;
        public final double totalAccountBalance;
        public final int transactionCount;
        public final int accountCount;
        public final int activeCardCount;
        @NonNull public final String currency;
        @NonNull public final String periodStart;
        @NonNull public final String periodEnd;
        @NonNull public final String periodLabel;
        public final long generatedAt;
        @NonNull public final String reason;

        private Summary(
                boolean available,
                double income,
                double expense,
                double remaining,
                double totalAccountBalance,
                int transactionCount,
                int accountCount,
                int activeCardCount,
                @Nullable String currency,
                @Nullable String periodStart,
                @Nullable String periodEnd,
                @Nullable String periodLabel,
                long generatedAt,
                @Nullable String reason) {
            this.available = available;
            this.income = income;
            this.expense = expense;
            this.remaining = remaining;
            this.totalAccountBalance = totalAccountBalance;
            this.transactionCount = Math.max(0, transactionCount);
            this.accountCount = Math.max(0, accountCount);
            this.activeCardCount = Math.max(0, activeCardCount);
            this.currency = safe(currency);
            this.periodStart = safe(periodStart);
            this.periodEnd = safe(periodEnd);
            this.periodLabel = safe(periodLabel);
            this.generatedAt = Math.max(0L, generatedAt);
            this.reason = safe(reason);
        }

        @NonNull
        public static Summary unavailable(@Nullable String reason) {
            return new Summary(false, 0D, 0D, 0D, 0D,
                    0, 0, 0, "INR", "", "", "", 0L, reason);
        }
    }

    private MoneyManagerFinanceSummaryBridge() { }

    /** Call from a worker thread. */
    @NonNull
    public static Summary loadCurrentMonth(@NonNull Context context) {
        try {
            Bundle response = context.getApplicationContext().getContentResolver()
                    .call(ENDPOINT, METHOD, null, null);
            if (response == null || !"OK".equals(safe(response.getString("status")))) {
                return Summary.unavailable(response == null
                        ? "MoneyManager did not return a finance summary"
                        : safe(response.getString("reason")));
            }

            String currency = safe(response.getString("currency"));
            if (!"INR".equalsIgnoreCase(currency)) {
                return Summary.unavailable("Unsupported MoneyManager summary currency");
            }

            return new Summary(
                    true,
                    fromMinor(response.getLong("income_minor", 0L)),
                    fromMinor(response.getLong("expense_minor", 0L)),
                    fromMinor(response.getLong("remaining_minor", 0L)),
                    fromMinor(response.getLong("total_account_balance_minor", 0L)),
                    response.getInt("transaction_count", 0),
                    response.getInt("account_count", 0),
                    response.getInt("active_card_count", 0),
                    currency,
                    response.getString("period_start"),
                    response.getString("period_end"),
                    response.getString("period_label"),
                    response.getLong("generated_at", 0L),
                    response.getString("reason"));
        } catch (RuntimeException unavailable) {
            return Summary.unavailable("MoneyManager finance summary is unavailable");
        }
    }

    private static double fromMinor(long amountMinor) {
        return amountMinor / 100D;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
