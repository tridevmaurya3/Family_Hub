package com.tridev.familyhub.core.ui.cards;

/**
 * Generic status card com.tridev.familyhub.data.model used across the application.
 *
 * Examples:
 * - Finance
 * - Health
 * - Vehicle
 * - Documents
 * - Password Vault
 */
public class StatusCardModel {

    private final String title;
    private final String value;
    private final String subtitle;
    private final int iconResId;

    public StatusCardModel(
            String title,
            String value,
            String subtitle,
            int iconResId
    ) {
        this.title = title;
        this.value = value;
        this.subtitle = subtitle;
        this.iconResId = iconResId;
    }

    public String getTitle() {
        return title;
    }

    public String getValue() {
        return value;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public int getIconResId() {
        return iconResId;
    }
}