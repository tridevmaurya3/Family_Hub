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
        return result.toArray(new String[0]);
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
