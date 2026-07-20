package com.tridev.familyhub.core.ui.cards;

/**
 * Standard Hero Card com.tridev.familyhub.data.model used across the Family Hub application.
 */
public class HeroCardModel {

    private final String title;
    private final String subtitle;
    private final int iconResId;
    private final String actionText;

    public HeroCardModel(
            String title,
            String subtitle,
            int iconResId,
            String actionText
    ) {
        this.title = title;
        this.subtitle = subtitle;
        this.iconResId = iconResId;
        this.actionText = actionText;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public int getIconResId() {
        return iconResId;
    }

    public String getActionText() {
        return actionText;
    }
}