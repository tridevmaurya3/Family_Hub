package com.tridev.familyhub.feature.grocery;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tridev.familyhub.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Grocery-only display options. Existing saved values remain valid. */
public final class GroceryOptionCatalog {
    public static final String ADD_CATEGORY_LABEL = "＋ Add new category";
    private static final String PREFS = "grocery_custom_categories";
    private static final String KEY_VALUES = "values";

    private GroceryOptionCatalog() { }

    @NonNull
    public static String[] categoryLabels(@NonNull Context context) {
        String[] base = context.getResources().getStringArray(
                R.array.grocery_category_labels);
        String[] extras = context.getResources().getStringArray(
                R.array.grocery_category_extra_labels);
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : base) addUnique(result, seen, value);
        for (String value : extras) addUnique(result, seen, value);
        Set<String> custom = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE).getStringSet(
                KEY_VALUES, new LinkedHashSet<>());
        if (custom != null) {
            List<String> sorted = new ArrayList<>(custom);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            for (String value : sorted) addUnique(result, seen, value);
        }
        return result.toArray(new String[0]);
    }

    public static boolean addCustomCategory(
            @NonNull Context context, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || ADD_CATEGORY_LABEL.equalsIgnoreCase(clean)) return false;
        Set<String> values = new LinkedHashSet<>(context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE).getStringSet(
                KEY_VALUES, new LinkedHashSet<>()));
        for (String existing : categoryLabels(context)) {
            if (existing.equalsIgnoreCase(clean)) return true;
        }
        values.add(clean);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_VALUES, values).apply();
        return true;
    }

    @NonNull
    public static String[] categoryLabelsWithAdd(@NonNull Context context) {
        String[] categories = categoryLabels(context);
        String[] result = java.util.Arrays.copyOf(categories, categories.length + 1);
        result[categories.length] = ADD_CATEGORY_LABEL;
        return result;
    }

    @NonNull
    public static String[] storePresets(@NonNull Context context) {
        return context.getResources().getStringArray(R.array.grocery_store_presets);
    }

    private static void addUnique(@NonNull List<String> out,
                                  @NonNull Set<String> seen,
                                  String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return;
        String key = clean.toLowerCase(Locale.ROOT);
        if (seen.add(key)) out.add(clean);
    }
}
