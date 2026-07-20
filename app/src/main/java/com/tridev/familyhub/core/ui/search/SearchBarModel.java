package com.tridev.familyhub.core.ui.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Data com.tridev.familyhub.data.model for the reusable Family Hub Search Bar.
 *
 * This com.tridev.familyhub.data.model stores:
 * - Search hint text
 * - Initial search text
 * - Whether the filter button should be displayed
 * - Whether the voice-search button should be displayed
 */
public class SearchBarModel {

    @NonNull
    private final String hint;

    @NonNull
    private final String initialQuery;

    private final boolean filterVisible;
    private final boolean voiceSearchVisible;

    /**
     * Creates a complete SearchBarModel.
     *
     * @param hint               text displayed when the search field is empty
     * @param initialQuery       text displayed initially inside the search field
     * @param filterVisible      true to display the filter button
     * @param voiceSearchVisible true to display the voice-search button
     */
    public SearchBarModel(
            @Nullable String hint,
            @Nullable String initialQuery,
            boolean filterVisible,
            boolean voiceSearchVisible
    ) {
        this.hint = hint == null
                ? ""
                : hint;

        this.initialQuery = initialQuery == null
                ? ""
                : initialQuery;

        this.filterVisible = filterVisible;
        this.voiceSearchVisible = voiceSearchVisible;
    }

    /**
     * Creates a basic search-bar com.tridev.familyhub.data.model with no initial query.
     */
    public SearchBarModel(
            @Nullable String hint,
            boolean filterVisible,
            boolean voiceSearchVisible
    ) {
        this(
                hint,
                "",
                filterVisible,
                voiceSearchVisible
        );
    }

    /**
     * Creates a simple search-bar com.tridev.familyhub.data.model without filter or voice buttons.
     */
    public SearchBarModel(
            @Nullable String hint
    ) {
        this(
                hint,
                "",
                false,
                false
        );
    }

    @NonNull
    public String getHint() {
        return hint;
    }

    @NonNull
    public String getInitialQuery() {
        return initialQuery;
    }

    public boolean isFilterVisible() {
        return filterVisible;
    }

    public boolean isVoiceSearchVisible() {
        return voiceSearchVisible;
    }
}