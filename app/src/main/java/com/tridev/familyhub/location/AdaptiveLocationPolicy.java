package com.tridev.familyhub.location;

import androidx.annotation.NonNull;

import com.google.android.gms.location.Priority;

/**
 * Central battery-aware and movement-aware policy for Family Live tracking.
 *
 * The service asks this policy for a profile instead of scattering timing
 * constants across location code. This keeps behaviour predictable, testable
 * and easy to tune for real devices.
 */
public final class AdaptiveLocationPolicy {

    public static final String PROFILE_NORMAL = "NORMAL";
    public static final String PROFILE_ACTIVE = "ACTIVE";
    public static final String PROFILE_TRAVELLING = "TRAVELLING";
    public static final String PROFILE_STATIONARY = "STATIONARY";
    public static final String PROFILE_POWER_SAVER = "POWER_SAVER";
    public static final String PROFILE_LOW_BATTERY = "LOW_BATTERY";

    public static final int LOW_BATTERY_THRESHOLD = 15;
    public static final int POWER_SAVER_BATTERY_THRESHOLD = 25;

    private AdaptiveLocationPolicy() {
    }

    @NonNull
    public static Config resolve(
            @NonNull String movementType,
            int batteryPercentage,
            boolean charging,
            boolean powerSaveMode
    ) {
        if (!charging
                && batteryPercentage >= 0
                && batteryPercentage <= LOW_BATTERY_THRESHOLD) {
            return configFor(PROFILE_LOW_BATTERY);
        }

        if (!charging && (powerSaveMode
                || (batteryPercentage >= 0
                && batteryPercentage <= POWER_SAVER_BATTERY_THRESHOLD))) {
            return configFor(PROFILE_POWER_SAVER);
        }

        if ("TRAVELLING".equalsIgnoreCase(movementType)
                || "CYCLING".equalsIgnoreCase(movementType)) {
            return configFor(PROFILE_TRAVELLING);
        }

        if ("WALKING".equalsIgnoreCase(movementType)) {
            return configFor(PROFILE_ACTIVE);
        }

        if ("STATIONARY".equalsIgnoreCase(movementType)) {
            return configFor(PROFILE_STATIONARY);
        }

        return configFor(PROFILE_NORMAL);
    }

    @NonNull
    public static Config configFor(@NonNull String profile) {
        switch (profile) {
            case PROFILE_ACTIVE:
                return new Config(
                        PROFILE_ACTIVE,
                        25_000L,
                        12_000L,
                        10F,
                        Priority.PRIORITY_HIGH_ACCURACY,
                        true,
                        30_000L
                );

            case PROFILE_TRAVELLING:
                return new Config(
                        PROFILE_TRAVELLING,
                        15_000L,
                        8_000L,
                        15F,
                        Priority.PRIORITY_HIGH_ACCURACY,
                        true,
                        20_000L
                );

            case PROFILE_STATIONARY:
                return new Config(
                        PROFILE_STATIONARY,
                        180_000L,
                        60_000L,
                        50F,
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        false,
                        360_000L
                );

            case PROFILE_POWER_SAVER:
                return new Config(
                        PROFILE_POWER_SAVER,
                        240_000L,
                        120_000L,
                        75F,
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        false,
                        480_000L
                );

            case PROFILE_LOW_BATTERY:
                return new Config(
                        PROFILE_LOW_BATTERY,
                        300_000L,
                        120_000L,
                        100F,
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        false,
                        600_000L
                );

            default:
                return new Config(
                        PROFILE_NORMAL,
                        60_000L,
                        30_000L,
                        25F,
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        false,
                        90_000L
                );
        }
    }

    public static boolean isImmediateSafetyProfile(@NonNull String profile) {
        return PROFILE_LOW_BATTERY.equals(profile)
                || PROFILE_POWER_SAVER.equals(profile)
                || PROFILE_TRAVELLING.equals(profile);
    }

    public static final class Config {
        @NonNull public final String profile;
        public final long intervalMs;
        public final long minIntervalMs;
        public final float minDistanceMeters;
        public final int priority;
        public final boolean waitForAccurateLocation;
        public final long maxUpdateDelayMs;

        private Config(
                @NonNull String profile,
                long intervalMs,
                long minIntervalMs,
                float minDistanceMeters,
                int priority,
                boolean waitForAccurateLocation,
                long maxUpdateDelayMs
        ) {
            this.profile = profile;
            this.intervalMs = intervalMs;
            this.minIntervalMs = minIntervalMs;
            this.minDistanceMeters = minDistanceMeters;
            this.priority = priority;
            this.waitForAccurateLocation = waitForAccurateLocation;
            this.maxUpdateDelayMs = maxUpdateDelayMs;
        }
    }
}
