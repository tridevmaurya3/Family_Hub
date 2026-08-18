package com.tridev.familyhub.feature.finance;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.feature.integration.MoneyManagerMasterCatalogBridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Privacy-safe Family Hub Finance -> MoneyManager bridge. */
public final class FinanceMoneyManagerBridge {

    private static final Uri COMPANION_ENDPOINT = Uri.parse(
            "content://" + MoneyManagerMasterCatalogBridge.AUTHORITY);
    private static final Uri EDIT_ENDPOINT = Uri.parse(
            "content://com.example.moneymanagerpro.tridev.familyhubedit");
    private static final String METHOD_ACCEPT_V1 = "accept_family_event_v1";
    private static final String METHOD_UPDATE_V1 = "update_family_event_v1";
    private static final String METHOD_CANCEL_FINANCE_V1 = "cancel_family_finance_event_v1";

    private FinanceMoneyManagerBridge() { }

    public static final class Result {
        public final boolean accepted;
        public final boolean preservedLedger;
        public final String status;
        public final String reason;

        private Result(boolean accepted, boolean preservedLedger, String status, String reason) {
            this.accepted = accepted;
            this.preservedLedger = preservedLedger;
            this.status = safe(status);
            this.reason = safe(reason);
        }
    }

    @NonNull
    public static String eventIdFor(@NonNull FinanceEntry entry) {
        return "family_finance_" + sha256(sourceRecordIdFor(entry));
    }

    @NonNull
    public static String sourceRecordIdFor(@NonNull FinanceEntry entry) {
        String record = entry.cloudId == null || entry.cloudId.trim().isEmpty()
                ? "local-" + entry.id : "cloud-" + entry.cloudId.trim();
        long version = entry.updatedAt > 0L ? entry.updatedAt : entry.createdAt;
        return structured("finance:" + record + ":" + version, 160);
    }

    @NonNull
    public static String stableEntryKey(@NonNull FinanceEntry entry) {
        String record = entry.cloudId == null || entry.cloudId.trim().isEmpty()
                ? "local:" + entry.id : "cloud:" + entry.cloudId.trim();
        return sha256(record).substring(0, 24);
    }

    @NonNull
    public static Result send(
            @NonNull Context context,
            @NonNull FinanceEntry entry,
            boolean forceReview) {
        Bundle extras = buildPayload(context, entry, forceReview);
        if (extras == null) {
            return validationFailure(context, entry);
        }
        return call(COMPANION_ENDPOINT, context, METHOD_ACCEPT_V1, extras);
    }

    /**
     * Corrects the exact MoneyManager row created from this Family Hub entry.
     * The canonical event/source identity is deliberately retained so editing a
     * category, account, amount or date never becomes a delete/new transaction.
     */
    @NonNull
    public static Result updateLinked(
            @NonNull Context context,
            @NonNull FinanceEntry entry,
            @NonNull String canonicalEventId,
            @NonNull String canonicalSourceRecordId) {
        Bundle extras = buildPayload(context, entry, false);
        if (extras == null) {
            return validationFailure(context, entry);
        }
        extras.putString("canonical_event_id", structured(canonicalEventId, 120));
        extras.putString("canonical_source_record_id",
                structured(canonicalSourceRecordId, 160));
        return call(EDIT_ENDPOINT, context, METHOD_UPDATE_V1, extras);
    }

    @Nullable
    private static Bundle buildPayload(
            @NonNull Context context,
            @NonNull FinanceEntry entry,
            boolean forceReview) {
        if (!isPostable(entry)) return null;
        long amountMinor = toMinor(entry.amount);
        if (amountMinor <= 0L) return null;

        boolean income = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
        String sourceRecordId = sourceRecordIdFor(entry);
        String eventId = eventIdFor(entry);
        String accountRef = MoneyManagerMasterCatalogBridge.accountRefForLabel(
                context, entry.accountName);
        String categoryRef = MoneyManagerMasterCatalogBridge.categoryRefForFinanceLabel(
                context, entry.category, income);
        if (accountRef.isEmpty() || categoryRef.isEmpty()) return null;

        long occurredAt = occurredAt(entry);
        String paymentMethod = metadata(entry.paymentMethod, 80);
        Bundle extras = new Bundle();
        extras.putString("event_id", eventId);
        extras.putString("source_record_id", sourceRecordId);
        extras.putString("event_type", income ? "INCOME" : "EXPENSE");
        extras.putString("direction", income ? "CREDIT" : "DEBIT");
        extras.putString("scope", entry.isShared ? "FAMILY" : "PERSONAL");
        extras.putBoolean("force_review", forceReview);
        extras.putLong("amount_minor", amountMinor);
        extras.putString("currency", "INR");
        extras.putLong("occurred_at", occurredAt);
        extras.putString("account_hint", accountRef);
        extras.putString("merchant_hint", paymentMethod);
        extras.putString("category_hint", categoryRef);
        extras.putString("fingerprint", sha256(
                sourceRecordId + "|" + amountMinor + "|"
                        + (income ? "CREDIT" : "DEBIT") + "|"
                        + accountRef.toLowerCase(Locale.ROOT) + "|"
                        + categoryRef.toLowerCase(Locale.ROOT)));
        return extras;
    }

    @NonNull
    private static Result validationFailure(
            @NonNull Context context,
            @NonNull FinanceEntry entry) {
        if (!isPostable(entry) || toMinor(entry.amount) <= 0L) {
            return new Result(false, false, "SKIPPED",
                    "Family Finance entry is not postable");
        }
        boolean income = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
        String accountRef = MoneyManagerMasterCatalogBridge.accountRefForLabel(
                context, entry.accountName);
        if (accountRef.isEmpty()) {
            return new Result(false, false, "NEEDS_REVIEW",
                    "Selected Family Finance account/card is not in MoneyManager master catalog");
        }
        String categoryRef = MoneyManagerMasterCatalogBridge.categoryRefForFinanceLabel(
                context, entry.category, income);
        return new Result(false, false, "NEEDS_REVIEW",
                categoryRef.isEmpty()
                        ? (income
                        ? "Selected Income category is not in MoneyManager master catalog"
                        : "Selected Expense category is not in MoneyManager master catalog")
                        : "Family Finance entry could not be prepared");
    }

    @NonNull
    public static Result cancel(
            @NonNull Context context,
            @NonNull String eventId,
            @NonNull String sourceRecordId) {
        Bundle extras = new Bundle();
        extras.putString("event_id", structured(eventId, 120));
        extras.putString("source_record_id", structured(sourceRecordId, 160));
        return call(COMPANION_ENDPOINT, context, METHOD_CANCEL_FINANCE_V1, extras);
    }

    public static boolean isPostable(@Nullable FinanceEntry entry) {
        if (entry == null || entry.id <= 0L || entry.amount <= 0D) return false;
        if (!FinanceEntry.TYPE_EXPENSE.equals(entry.entryType)
                && !FinanceEntry.TYPE_INCOME.equals(entry.entryType)) return false;
        return !"UPCOMING".equalsIgnoreCase(safe(entry.recurrenceStatus));
    }

    @NonNull
    private static Result call(
            @NonNull Uri endpoint,
            @NonNull Context context,
            @NonNull String method,
            @NonNull Bundle extras) {
        try {
            Bundle response = context.getApplicationContext().getContentResolver()
                    .call(endpoint, method, null, extras);
            if (response == null) {
                return new Result(false, false, "UNAVAILABLE",
                        "MoneyManager did not return a response");
            }
            String status = safe(response.getString("status"));
            String reason = safe(response.getString("reason"));
            boolean accepted = !("REJECTED".equals(status)
                    || "FAILED".equals(status)
                    || "UNAVAILABLE".equals(status)
                    || "NEEDS_REVIEW".equals(status));
            return new Result(accepted, "PRESERVED".equals(status), status, reason);
        } catch (RuntimeException unavailable) {
            return new Result(false, false, "UNAVAILABLE",
                    "MoneyManager bridge is unavailable");
        }
    }

    private static long occurredAt(@NonNull FinanceEntry entry) {
        long fallback = entry.createdAt > 0L ? entry.createdAt
                : (entry.updatedAt > 0L ? entry.updatedAt : System.currentTimeMillis());
        String dateText = safe(entry.transactionDate);
        if (dateText.isEmpty()) return fallback;
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        parser.setLenient(false);
        try {
            Date date = parser.parse(dateText);
            if (date == null) return fallback;
            Calendar target = Calendar.getInstance();
            target.setTime(date);
            Calendar clock = Calendar.getInstance();
            clock.setTimeInMillis(fallback);
            target.set(Calendar.HOUR_OF_DAY, clock.get(Calendar.HOUR_OF_DAY));
            target.set(Calendar.MINUTE, clock.get(Calendar.MINUTE));
            target.set(Calendar.SECOND, clock.get(Calendar.SECOND));
            target.set(Calendar.MILLISECOND, 0);
            return target.getTimeInMillis();
        } catch (ParseException ignored) {
            return fallback;
        }
    }

    private static long toMinor(double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) return 0L;
        try {
            return BigDecimal.valueOf(amount).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException invalid) {
            return 0L;
        }
    }

    @NonNull
    private static String metadata(@Nullable String value, int maxLength) {
        String clean = safe(value).replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ");
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    @NonNull
    private static String structured(@Nullable String value, int maxLength) {
        String clean = safe(value).replace('\n', ' ').replace('\r', ' ')
                .replaceAll("[^A-Za-z0-9:_\\-]", "_");
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                output.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return output.toString();
        } catch (Exception impossibleOnAndroid) {
            return String.format(Locale.US, "%064x", value.hashCode());
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
