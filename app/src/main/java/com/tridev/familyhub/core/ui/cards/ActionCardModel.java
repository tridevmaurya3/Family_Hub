package com.tridev.familyhub.core.ui.cards;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

/** Display model for a compact actionable dashboard module card. */
public class ActionCardModel {

    @NonNull private final String title;
    @NonNull private final String primaryValue;
    @NonNull private final String secondaryValue;
    @DrawableRes private final int iconResId;
    @ColorRes private final int accentColorResId;
    @ColorRes private final int containerColorResId;

    public ActionCardModel(
            @NonNull String title,
            @NonNull String primaryValue,
            @NonNull String secondaryValue,
            @DrawableRes int iconResId,
            @ColorRes int accentColorResId,
            @ColorRes int containerColorResId
    ) {
        this.title = title;
        this.primaryValue = primaryValue;
        this.secondaryValue = secondaryValue;
        this.iconResId = iconResId;
        this.accentColorResId = accentColorResId;
        this.containerColorResId = containerColorResId;
    }

    @NonNull public String getTitle() {
        return title;
    }

    @NonNull public String getPrimaryValue() {
        return primaryValue;
    }

    @NonNull public String getSecondaryValue() {
        return secondaryValue;
    }

    public int getIconResId() {
        return iconResId;
    }

    public int getAccentColorResId() {
        return accentColorResId;
    }

    public int getContainerColorResId() {
        return containerColorResId;
    }
}
