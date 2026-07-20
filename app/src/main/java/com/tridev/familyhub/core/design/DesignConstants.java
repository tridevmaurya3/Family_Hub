package com.tridev.familyhub.core.design;

/**
 * Shared design constants for the entire Family Hub application.
 *
 * Every module should use these values instead of hardcoded numbers.
 */
public final class DesignConstants {

    private DesignConstants() {
        // Utility class
    }

    // Corner Radius (dp)
    public static final int RADIUS_SMALL = 12;
    public static final int RADIUS_MEDIUM = 16;
    public static final int RADIUS_LARGE = 20;
    public static final int RADIUS_HERO = 28;

    // Standard Spacing (dp)
    public static final int SPACE_4 = 4;
    public static final int SPACE_8 = 8;
    public static final int SPACE_12 = 12;
    public static final int SPACE_16 = 16;
    public static final int SPACE_20 = 20;
    public static final int SPACE_24 = 24;
    public static final int SPACE_32 = 32;
    public static final int SPACE_40 = 40;

    // Animation Duration (ms)
    public static final int FAST_ANIMATION = 150;
    public static final int NORMAL_ANIMATION = 250;
    public static final int SLOW_ANIMATION = 400;
}