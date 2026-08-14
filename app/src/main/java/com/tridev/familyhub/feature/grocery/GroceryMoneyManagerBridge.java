package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private static final String METHOD_ACCOUNT_CATALOG_V1 = "get_account_catalog_v1";

    private static final String ACCOUNT_PREFS = "grocery_money_manager_account_v1";
    private static final String ACCOUNT_PREFIX = "next_account_";

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

    public static final class AccountChoice {
        public final String canonicalRef;
        public final String label;

        private AccountChoice(String canonicalRef, String label) {
            this.canonicalRef = safe(canonicalRef);
            this.label = safe(label);
        }
    }

    public static final class AccountCatalog {
        public final boolean available;
        public final List<AccountChoice> choices;
        public final String reason;

        private AccountCatalog(
                boolean available,
                List<AccountChoice> choices,
                String reason) {
            this.available = available;
            this.choices = Collections.unmodifiableList(choices);
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
     * Reads only MoneyManager's active account/card labels and stable refs. No
     * balances, transaction history or other finance data is exposed.
     * Call from a worker thread.
     */
    @NonNull
    public static AccountCatalog loadAccountCatalog(@NonNull Context context) {
        try {
            Bundle response = context.getApplicationContext()
                    .getContentResolver()
                    .call(ENDPOINT, METHOD_ACCOUNT_CATALOG_V1, null, null);
            if (response == null || !"OK".equals(safe(response.getString("status")))) {
                return new AccountCatalog(false, new ArrayList<>(),
                        response == null
                                ? "MoneyManager is unavailable"
                                : safe(response.getString("reason")));
            }

            ArrayList<String> refs = response.getStringArrayList("account_refs");
            ArrayList<String> labels = response.getStringArrayList("account_labels");
            List<AccountChoice> choices = new ArrayList<>();
            if (refs != null && labels != null) {
                int count = Math.min(refs.size(), labels.size());
                for (int index = 0; index < count; index++) {
                    String ref = safe(refs.get(index)).toLowerCase(Locale.ROOT);
                    String label = safe(labels.get(index));
                    if ((!ref.matches("account:[0-9]+")
                            && !ref.matches("card:[0-9]+"))
                            || label.isEmpty()) continue;
                    choices.add(new AccountChoice(ref, label));
                }
            }
            return new AccountCatalog(true, choices,
                    safe(response.getString("reason")));
        } catch (RuntimeException unavailable) {
            return new AccountCatalog(false, new ArrayList<>(),
                    "MoneyManager account list is unavailable");
        }
    }

    /**
     * Stores one explicit user choice for the NEXT purchase cycle of this item.
     * It is consumed only after MoneyManager accepts that purchase event.
     */
    public static void rememberNextPurchaseAccount(
            @NonNull Context context,
            @NonNull GroceryItem item,
            @Nullable String canonicalRef) {
        String ref = safe(canonicalRef).toLowerCase(Locale.ROOT);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE);
        String key = ACCOUNT_PREFIX + stableItemKey(item);
        if (!ref.matches("(account|card):[0-9]+")) {
            preferences.edit().remove(key).apply();
            return;
        }
        preferences.edit().putString(key, ref).apply();
    }

    /**
     * Sends one completed purchase. If the user selected a MoneyManager
     * Bank/Credit Card during purchase completion, the stable account/card ref is
     * sent and can post immediately. Otherwise a purchase-specific unassigned
     * hint is used, forcing review instead of guessing an account.
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
        String selectedAccount = pendingAccount(context, item);
        String accountHint = selectedAccount.isEmpty()
                ? "unassigned:" + eventId.substring(Math.max(0, eventId.length() - 24))
                : selectedAccount;

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

        Result result = call(context, METHOD_ACCEPT_V1, extras);
        if (result.accepted) {
            clearPendingAccount(context, item);
        }
        return result;
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
    private static String pendingAccount(
            @NonNull Context context,
            @NonNull GroceryItem item) {
        String value = context.getApplicationContext()
                .getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE)
                .getString(ACCOUNT_PREFIX + stableItemKey(item), "");
        String ref = safe(value).toLowerCase(Locale.ROOT);
        return ref.matches("(account|card):[0-9]+") ? ref : "";
    }

    private static void clearPendingAccount(
            @NonNull Context context,
            @NonNull GroceryItem item) {
        context.getApplicationContext()
                .getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(ACCOUNT_PREFIX + stableItemKey(item))
                .apply();
    }

    @NonNull
    private static String stableItemKey(@NonNull GroceryItem item) {
        String value = item.cloudId == null || item.cloudId.trim().isEmpty()
                ? "local:" + item.id
                : "cloud:" + item.cloudId.trim();
        return sha256(value).substring(0, 24);
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
