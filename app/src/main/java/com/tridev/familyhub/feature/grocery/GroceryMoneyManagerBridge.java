package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * STEP 8 - privacy-safe same-device bridge from Family Hub Grocery to
 * MoneyManagerPro.
 *
 * Only structured purchase metadata is sent. Item notes, family member details,
 * contacts and any other private Family Hub content are never included.
 *
 * MoneyManager verifies the real Android Binder caller UID, exact Family Hub
 * package and matching app signature before accepting these calls.
 */
public final class GroceryMoneyManagerBridge {

    private static final String AUTHORITY =
            "com.example.moneymanagerpro.tridev.finance";
    private static final Uri ENDPOINT = Uri.parse("content://" + AUTHORITY);

    private static final String METHOD_ACCEPT_V1 = "accept_finance_event_v1";
    private static final String METHOD_CANCEL_V1 = "cancel_finance_event_v1";

    private GroceryMoneyManagerBridge() { }

    public static final class Result {
        public final boolean accepted;
        public final String status;
        public final String reason;

        private Result(boolean accepted, String status, String reason) {
            this.accepted = accepted;
            this.status = safe(status);
            this.reason = safe(reason);
        }
    }

    /** Deterministic id so retrying the same purchase cannot create duplicates. */
    @NonNull
    public static String eventIdFor(@NonNull GroceryItem item) {
        return "family_grocery_" + sha256(sourceRecordIdFor(item));
    }

    /** Stable for one concrete purchase cycle and changes on the next purchase. */
    @NonNull
    public static String sourceRecordIdFor(@NonNull GroceryItem item) {
        String itemKey = item.cloudId == null || item.cloudId.trim().isEmpty()
                ? "local-" + item.id
                : "cloud-" + item.cloudId.trim();
        long purchasedAt = item.purchasedAt > 0L
                ? item.purchasedAt : item.updatedAt;
        return safeStructured("grocery:" + itemKey + ":" + purchasedAt, 160);
    }

    /**
     * Sends one completed purchase. When Family Hub does not know the paying
     * bank/card, account_hint is deliberately purchase-specific rather than a
     * global "unknown" mapping. This prevents one review choice from silently
     * becoming the account for every future grocery purchase.
     */
    @NonNull
    public static Result sendPurchase(
            @NonNull Context context,
            @NonNull GroceryItem item) {
        double amount = item.actualCost > 0D ? item.actualCost : item.estimatedCost;
        long amountMinor = toMinor(amount);
        if (!item.isPurchased || item.purchasedAt <= 0L || amountMinor <= 0L) {
            return new Result(false, "SKIPPED", "Purchase is incomplete or has no amount");
        }

        String eventId = eventIdFor(item);
        String sourceRecordId = sourceRecordIdFor(item);
        String merchant = metadata(item.storeName, 120);
        String accountHint = "unassigned:" + eventId.substring(
                Math.max(0, eventId.length() - 24));

        Bundle extras = new Bundle();
        extras.putString("event_id", eventId);
        extras.putString("source_record_id", sourceRecordId);
        extras.putString("event_type", "GROCERY_PURCHASE");
        extras.putString("direction", "DEBIT");
        extras.putLong("amount_minor", amountMinor);
        extras.putString("currency", "INR");
        extras.putLong("occurred_at", item.purchasedAt);
        extras.putString("account_hint", accountHint);
        extras.putString("merchant_hint", merchant);
        extras.putString("category_hint", "Grocery");
        extras.putString("fingerprint", sha256(
                sourceRecordId + "|" + amountMinor + "|" + merchant.toLowerCase(Locale.ROOT)));

        return call(context, METHOD_ACCEPT_V1, extras);
    }

    /**
     * Cancels only the integration event for the exact purchase cycle. On the
     * MoneyManager side an auto-created Family Hub row may be removed, but an
     * existing/manual ledger row is never deleted.
     */
    @NonNull
    public static Result cancelPurchase(
            @NonNull Context context,
            @NonNull String eventId,
            @NonNull String sourceRecordId) {
        Bundle extras = new Bundle();
        extras.putString("event_id", safeStructured(eventId, 120));
        extras.putString("source_record_id", safeStructured(sourceRecordId, 160));
        return call(context, METHOD_CANCEL_V1, extras);
    }

    @NonNull
    private static Result call(
            @NonNull Context context,
            @NonNull String method,
            @NonNull Bundle extras) {
        try {
            Bundle response = context.getApplicationContext()
                    .getContentResolver()
                    .call(ENDPOINT, method, null, extras);
            if (response == null) {
                return new Result(false, "UNAVAILABLE", "MoneyManager did not return a response");
            }
            String status = safe(response.getString("status"));
            String reason = safe(response.getString("reason"));
            boolean accepted = !("REJECTED".equals(status)
                    || "FAILED".equals(status)
                    || "UNAVAILABLE".equals(status));
            return new Result(accepted, status, reason);
        } catch (RuntimeException unavailable) {
            // Family Hub remains fully usable if MoneyManager is absent, locked,
            // signed differently or temporarily unavailable.
            return new Result(false, "UNAVAILABLE", "MoneyManager bridge is unavailable");
        }
    }

    private static long toMinor(double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) return 0L;
        try {
            return BigDecimal.valueOf(amount)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException invalid) {
            return 0L;
        }
    }

    @NonNull
    private static String metadata(@Nullable String value, int maxLength) {
        String safe = value == null ? "" : value.trim()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ");
        return safe.length() <= maxLength
                ? safe : safe.substring(0, maxLength).trim();
    }

    @NonNull
    private static String safeStructured(@Nullable String value, int maxLength) {
        String safe = safe(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("[^A-Za-z0-9:_\\-]", "_");
        return safe.length() <= maxLength
                ? safe : safe.substring(0, maxLength);
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                output.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return output.toString();
        } catch (Exception impossibleOnAndroid) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
