package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.integration.MoneyManagerMasterCatalogBridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Privacy-safe Grocery -> MoneyManager bridge using stable master refs. */
public final class GroceryMoneyManagerBridge {

    private static final String AUTHORITY = MoneyManagerMasterCatalogBridge.AUTHORITY;
    private static final Uri ENDPOINT = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD_ACCEPT_V1 = "accept_family_event_v1";
    private static final String METHOD_CANCEL_V1 = "cancel_family_grocery_v1";

    private static final String PREFS = "grocery_money_manager_master_v2";
    private static final String ACCOUNT_PREFIX = "next_account_";
    private static final String CATEGORY_PREFIX = "next_category_";

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

        private AccountCatalog(boolean available, List<AccountChoice> choices, String reason) {
            this.available = available;
            this.choices = Collections.unmodifiableList(choices);
            this.reason = safe(reason);
        }
    }

    @NonNull
    public static String eventIdFor(@NonNull GroceryItem item) {
        return "family_grocery_" + sha256(sourceRecordIdFor(item));
    }

    @NonNull
    public static String sourceRecordIdFor(@NonNull GroceryItem item) {
        String itemKey = item.cloudId == null || item.cloudId.trim().isEmpty()
                ? "local-" + item.id
                : "cloud-" + item.cloudId.trim();
        long purchasedAt = item.purchasedAt > 0L ? item.purchasedAt : item.updatedAt;
        return safeStructured("grocery:" + itemKey + ":" + purchasedAt, 160);
    }

    @NonNull
    public static AccountCatalog loadAccountCatalog(@NonNull Context context) {
        MoneyManagerMasterCatalogBridge.Catalog catalog =
                MoneyManagerMasterCatalogBridge.load(context);
        List<AccountChoice> choices = new ArrayList<>();
        for (MoneyManagerMasterCatalogBridge.Choice choice : catalog.accounts) {
            choices.add(new AccountChoice(choice.ref, choice.label));
        }
        return new AccountCatalog(catalog.available, choices, catalog.reason);
    }

    public static void rememberNextPurchaseAccount(
            @NonNull Context context,
            @NonNull GroceryItem item,
            @Nullable String canonicalRef) {
        rememberRef(context, ACCOUNT_PREFIX, item, canonicalRef, "(account|card):[0-9]+");
        String ref = safe(canonicalRef).toLowerCase(Locale.ROOT);
        if (ref.matches("(account|card):[0-9]+")) {
            MoneyManagerMasterCatalogBridge.rememberGroceryDefaultAccount(context, ref);
        }
    }

    public static void rememberNextPurchaseCategory(
            @NonNull Context context,
            @NonNull GroceryItem item,
            @Nullable String canonicalRef) {
        rememberRef(context, CATEGORY_PREFIX, item, canonicalRef, "category:[0-9]+");
        String ref = safe(canonicalRef).toLowerCase(Locale.ROOT);
        if (ref.matches("category:[0-9]+")) {
            MoneyManagerMasterCatalogBridge.rememberGroceryDefaultCategory(context, ref);
        }
    }

    public static void rememberNextPurchaseSelections(
            @NonNull Context context,
            @NonNull GroceryItem item,
            @Nullable String accountRef,
            @Nullable String categoryRef) {
        rememberNextPurchaseAccount(context, item, accountRef);
        rememberNextPurchaseCategory(context, item, categoryRef);
    }

    @NonNull
    public static String selectedAccountRef(
            @NonNull Context context,
            @NonNull GroceryItem item) {
        String ref = pendingRef(context, ACCOUNT_PREFIX, item, "(account|card):[0-9]+");
        return ref.isEmpty()
                ? MoneyManagerMasterCatalogBridge.groceryDefaultAccountRef(context)
                : ref;
    }

    @NonNull
    public static String selectedCategoryRef(
            @NonNull Context context,
            @NonNull GroceryItem item) {
        String ref = pendingRef(context, CATEGORY_PREFIX, item, "category:[0-9]+");
        return ref.isEmpty()
                ? MoneyManagerMasterCatalogBridge.groceryDefaultCategoryRef(context)
                : ref;
    }

    /**
     * Post one finalized Grocery purchase. The exact account/card and Expense
     * category refs are revalidated against MoneyManager's live master catalog
     * immediately before submission. This prevents a stale overlay/default ref
     * from creating a NEEDS_REVIEW event that could otherwise look successful.
     */
    @NonNull
    public static Result sendPurchase(@NonNull Context context, @NonNull GroceryItem item) {
        double amount = item.actualCost > 0D ? item.actualCost : item.estimatedCost;
        long amountMinor = toMinor(amount);
        if (!item.isPurchased || item.purchasedAt <= 0L || amountMinor <= 0L) {
            return new Result(false, "SKIPPED", "Purchase is incomplete or has no amount");
        }

        String selectedAccount = selectedAccountRef(context, item);
        String selectedCategory = selectedCategoryRef(context, item);
        if (selectedAccount.isEmpty() || selectedCategory.isEmpty()) {
            return new Result(false, "MAPPING_REQUIRED",
                    "Choose a MoneyManager account/card and Expense category");
        }

        MoneyManagerMasterCatalogBridge.Catalog liveCatalog =
                MoneyManagerMasterCatalogBridge.load(context);
        if (!liveCatalog.available) {
            return new Result(false, "UNAVAILABLE",
                    liveCatalog.reason.isEmpty()
                            ? "MoneyManager master catalog is unavailable"
                            : liveCatalog.reason);
        }

        MoneyManagerMasterCatalogBridge.Choice accountChoice =
                MoneyManagerMasterCatalogBridge.findByRef(
                        liveCatalog.accounts, selectedAccount);
        MoneyManagerMasterCatalogBridge.Choice categoryChoice =
                MoneyManagerMasterCatalogBridge.findByRef(
                        liveCatalog.expenseCategories, selectedCategory);
        if (accountChoice == null) {
            return new Result(false, "MAPPING_REQUIRED",
                    "Selected Grocery account/card is no longer in MoneyManager master catalog");
        }
        if (categoryChoice == null) {
            return new Result(false, "MAPPING_REQUIRED",
                    "Selected Grocery Expense category is no longer in MoneyManager master catalog");
        }

        String eventId = eventIdFor(item);
        String sourceRecordId = sourceRecordIdFor(item);
        String merchant = metadata(item.storeName, 120);
        String accountHint = accountChoice.ref;
        String categoryHint = categoryChoice.ref;

        Bundle extras = new Bundle();
        extras.putString("event_id", eventId);
        extras.putString("source_record_id", sourceRecordId);
        extras.putString("event_type", "GROCERY_PURCHASE");
        extras.putString("direction", "DEBIT");
        extras.putString("scope", "FAMILY");
        extras.putLong("amount_minor", amountMinor);
        extras.putString("currency", "INR");
        extras.putLong("occurred_at", item.purchasedAt);
        extras.putString("account_hint", accountHint);
        extras.putString("merchant_hint", merchant);
        extras.putString("category_hint", categoryHint);
        extras.putString("fingerprint", sha256(
                sourceRecordId + "|" + amountMinor + "|" + accountHint + "|"
                        + categoryHint + "|" + merchant.toLowerCase(Locale.ROOT)));

        Result result = call(context, METHOD_ACCEPT_V1, extras);
        if (result.accepted) clearSelections(context, item);
        return result;
    }

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

    private static void rememberRef(
            Context context,
            String prefix,
            GroceryItem item,
            @Nullable String canonicalRef,
            String pattern) {
        String ref = safe(canonicalRef).toLowerCase(Locale.ROOT);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefix + stableItemKey(item);
        if (!ref.matches(pattern)) {
            preferences.edit().remove(key).apply();
            return;
        }
        preferences.edit().putString(key, ref).apply();
    }

    @NonNull
    private static String pendingRef(
            Context context,
            String prefix,
            GroceryItem item,
            String pattern) {
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(prefix + stableItemKey(item), "");
        String ref = safe(value).toLowerCase(Locale.ROOT);
        return ref.matches(pattern) ? ref : "";
    }

    private static void clearSelections(@NonNull Context context, @NonNull GroceryItem item) {
        String suffix = stableItemKey(item);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(ACCOUNT_PREFIX + suffix).remove(CATEGORY_PREFIX + suffix).apply();
    }

    @NonNull
    private static String stableItemKey(@NonNull GroceryItem item) {
        String value = item.cloudId == null || item.cloudId.trim().isEmpty()
                ? "local:" + item.id
                : "cloud:" + item.cloudId.trim();
        return sha256(value).substring(0, 24);
    }

    @NonNull
    private static Result call(Context context, String method, Bundle extras) {
        try {
            Bundle response = context.getApplicationContext().getContentResolver()
                    .call(ENDPOINT, method, null, extras);
            if (response == null) {
                return new Result(false, "UNAVAILABLE", "MoneyManager did not return a response");
            }
            String status = safe(response.getString("status"));
            String reason = safe(response.getString("reason"));
            return new Result(isFinalizedStatus(status), status, reason);
        } catch (RuntimeException unavailable) {
            return new Result(false, "UNAVAILABLE", "MoneyManager bridge is unavailable");
        }
    }

    private static boolean isFinalizedStatus(@Nullable String status) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        return "POSTED".equals(value)
                || "RECONCILED".equals(value)
                || "DUPLICATE".equals(value)
                || "CANCELLED".equals(value)
                || "PRESERVED".equals(value);
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
    private static String safeStructured(@Nullable String value, int maxLength) {
        String clean = safe(value).replace('\n', ' ').replace('\r', ' ')
                .replaceAll("[^A-Za-z0-9:_\\-]", "_");
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
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
