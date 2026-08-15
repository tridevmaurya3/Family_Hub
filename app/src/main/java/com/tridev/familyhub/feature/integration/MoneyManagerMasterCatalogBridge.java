package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only MoneyManager master catalog for Family Hub forms. */
public final class MoneyManagerMasterCatalogBridge {

    public static final String AUTHORITY =
            "com.example.moneymanagerpro.tridev.companion";
    private static final Uri ENDPOINT = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD_CATALOG = "get_master_catalog_v1";
    private static final String PREFS = "money_manager_master_catalog_v1";
    private static final String ACCOUNT_PREFIX = "account_label_";
    private static final String CATEGORY_PREFIX = "category_label_";
    private static final String GROCERY_ACCOUNT = "grocery_default_account";
    private static final String GROCERY_CATEGORY = "grocery_default_category";

    public static final class Choice {
        @NonNull public final String ref;
        @NonNull public final String label;
        @NonNull public final String type;

        public Choice(@Nullable String ref, @Nullable String label, @Nullable String type) {
            this.ref = safe(ref).toLowerCase(Locale.ROOT);
            this.label = safe(label);
            this.type = safe(type);
        }
    }

    public static final class Catalog {
        public final boolean available;
        @NonNull public final List<Choice> accounts;
        @NonNull public final List<Choice> expenseCategories;
        @NonNull public final List<Choice> incomeCategories;
        @NonNull public final String reason;

        private Catalog(boolean available, @NonNull List<Choice> accounts,
                        @NonNull List<Choice> expenseCategories,
                        @NonNull List<Choice> incomeCategories,
                        @NonNull String reason) {
            this.available = available;
            this.accounts = Collections.unmodifiableList(accounts);
            this.expenseCategories = Collections.unmodifiableList(expenseCategories);
            this.incomeCategories = Collections.unmodifiableList(incomeCategories);
            this.reason = reason;
        }

        @NonNull
        public static Catalog unavailable(@Nullable String reason) {
            return new Catalog(false, new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), safe(reason));
        }
    }

    private MoneyManagerMasterCatalogBridge() { }

    /** Call from a worker thread. */
    @NonNull
    public static Catalog load(@NonNull Context context) {
        try {
            Bundle response = context.getApplicationContext().getContentResolver()
                    .call(ENDPOINT, METHOD_CATALOG, null, null);
            if (response == null || !"OK".equals(safe(response.getString("status")))) {
                return Catalog.unavailable(response == null
                        ? "MoneyManager is unavailable"
                        : safe(response.getString("reason")));
            }
            ArrayList<String> accountRefs = response.getStringArrayList("account_refs");
            ArrayList<String> accountLabels = response.getStringArrayList("account_labels");
            ArrayList<String> categoryRefs = response.getStringArrayList("category_refs");
            ArrayList<String> categoryLabels = response.getStringArrayList("category_labels");
            ArrayList<String> categoryTypes = response.getStringArrayList("category_types");

            List<Choice> accounts = new ArrayList<>();
            if (accountRefs != null && accountLabels != null) {
                int count = Math.min(accountRefs.size(), accountLabels.size());
                for (int i = 0; i < count; i++) {
                    String ref = safe(accountRefs.get(i)).toLowerCase(Locale.ROOT);
                    String label = safe(accountLabels.get(i));
                    if (ref.matches("(account|card):[0-9]+") && !label.isEmpty()) {
                        accounts.add(new Choice(ref, label,
                                ref.startsWith("card:") ? "Credit Card" : "Account"));
                    }
                }
            }

            List<Choice> expense = new ArrayList<>();
            List<Choice> income = new ArrayList<>();
            if (categoryRefs != null && categoryLabels != null && categoryTypes != null) {
                int count = Math.min(categoryRefs.size(),
                        Math.min(categoryLabels.size(), categoryTypes.size()));
                for (int i = 0; i < count; i++) {
                    String ref = safe(categoryRefs.get(i)).toLowerCase(Locale.ROOT);
                    String label = safe(categoryLabels.get(i));
                    String type = safe(categoryTypes.get(i));
                    if (!ref.matches("category:[0-9]+") || label.isEmpty()) continue;
                    Choice choice = new Choice(ref, label, type);
                    if ("income".equalsIgnoreCase(type)) income.add(choice);
                    else if ("expense".equalsIgnoreCase(type)) expense.add(choice);
                }
            }
            return new Catalog(true, accounts, expense, income,
                    safe(response.getString("reason")));
        } catch (RuntimeException unavailable) {
            return Catalog.unavailable("MoneyManager master catalog is unavailable");
        }
    }

    public static void rememberAccountChoice(Context context, @Nullable String label,
                                             @Nullable String ref) {
        remember(context, ACCOUNT_PREFIX, label, ref, "(account|card):[0-9]+");
    }

    public static void rememberCategoryChoice(Context context, @Nullable String label,
                                              @Nullable String ref) {
        remember(context, CATEGORY_PREFIX, label, ref, "category:[0-9]+");
    }

    @NonNull
    public static String accountRefForLabel(Context context, @Nullable String label) {
        return remembered(context, ACCOUNT_PREFIX, label, "(account|card):[0-9]+");
    }

    @NonNull
    public static String categoryRefForLabel(Context context, @Nullable String label) {
        return remembered(context, CATEGORY_PREFIX, label, "category:[0-9]+");
    }

    public static void rememberGroceryDefaultAccount(
            @NonNull Context context, @Nullable String ref) {
        rememberDirect(context, GROCERY_ACCOUNT, ref, "(account|card):[0-9]+");
    }

    public static void rememberGroceryDefaultCategory(
            @NonNull Context context, @Nullable String ref) {
        rememberDirect(context, GROCERY_CATEGORY, ref, "category:[0-9]+");
    }

    @NonNull
    public static String groceryDefaultAccountRef(@NonNull Context context) {
        return direct(context, GROCERY_ACCOUNT, "(account|card):[0-9]+");
    }

    @NonNull
    public static String groceryDefaultCategoryRef(@NonNull Context context) {
        return direct(context, GROCERY_CATEGORY, "category:[0-9]+");
    }

    @Nullable
    public static Choice findByLabel(@NonNull List<Choice> choices, @Nullable String label) {
        String wanted = safe(label);
        for (Choice choice : choices) {
            if (choice.label.equalsIgnoreCase(wanted)) return choice;
        }
        return null;
    }

    @Nullable
    public static Choice findByRef(@NonNull List<Choice> choices, @Nullable String ref) {
        String wanted = safe(ref).toLowerCase(Locale.ROOT);
        for (Choice choice : choices) {
            if (choice.ref.equalsIgnoreCase(wanted)) return choice;
        }
        return null;
    }

    @NonNull
    public static String[] labels(@NonNull List<Choice> choices) {
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        return labels;
    }

    private static void remember(Context context, String prefix, @Nullable String label,
                                 @Nullable String ref, String allowedPattern) {
        String cleanLabel = safe(label);
        String cleanRef = safe(ref).toLowerCase(Locale.ROOT);
        if (cleanLabel.isEmpty() || !cleanRef.matches(allowedPattern)) return;
        prefs(context).edit()
                .putString(prefix + sha256(normalize(cleanLabel)), cleanRef).apply();
    }

    @NonNull
    private static String remembered(Context context, String prefix, @Nullable String label,
                                     String allowedPattern) {
        String cleanLabel = safe(label);
        if (cleanLabel.isEmpty()) return "";
        String value = safe(prefs(context)
                .getString(prefix + sha256(normalize(cleanLabel)), ""))
                .toLowerCase(Locale.ROOT);
        return value.matches(allowedPattern) ? value : "";
    }

    private static void rememberDirect(Context context, String key, @Nullable String ref,
                                       String allowedPattern) {
        String value = safe(ref).toLowerCase(Locale.ROOT);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (value.matches(allowedPattern)) editor.putString(key, value);
        else editor.remove(key);
        editor.apply();
    }

    @NonNull
    private static String direct(Context context, String key, String allowedPattern) {
        String value = safe(prefs(context).getString(key, "")).toLowerCase(Locale.ROOT);
        return value.matches(allowedPattern) ? value : "";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return safe(value).toLowerCase(Locale.ROOT).replace('•', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                out.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
