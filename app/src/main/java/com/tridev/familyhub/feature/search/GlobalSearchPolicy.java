package com.tridev.familyhub.feature.search;

import androidx.annotation.NonNull;

/** Bounded query normalization keeps cross-module searches predictable. */
public final class GlobalSearchPolicy {
    public static final int MAX_QUERY_LENGTH = 80;
    private GlobalSearchPolicy() { }

    @NonNull public static String normalize(String query) {
        String clean = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        return clean.length() <= MAX_QUERY_LENGTH
                ? clean : clean.substring(0, MAX_QUERY_LENGTH);
    }
}
