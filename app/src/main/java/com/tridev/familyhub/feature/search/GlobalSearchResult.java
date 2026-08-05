package com.tridev.familyhub.feature.search;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

/** Safe, presentation-only result returned by the cross-module search index. */
public class GlobalSearchResult {
    @NonNull public final String module;
    @NonNull public final String title;
    @NonNull public final String detail;
    @NonNull public final String route;
    @DrawableRes public final int icon;
    public final boolean urgent;

    public GlobalSearchResult(@NonNull String module, @NonNull String title,
                              @NonNull String detail, @NonNull String route,
                              @DrawableRes int icon, boolean urgent) {
        this.module = module;
        this.title = title;
        this.detail = detail;
        this.route = route;
        this.icon = icon;
        this.urgent = urgent;
    }
}
