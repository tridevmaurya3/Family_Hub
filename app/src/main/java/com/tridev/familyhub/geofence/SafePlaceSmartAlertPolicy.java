package com.tridev.familyhub.geofence;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure timing, distance and de-duplication policy for Safe Place alerts. */
public final class SafePlaceSmartAlertPolicy {

    public static final String ALERT_ARRIVED = "ARRIVED";
    public static final String ALERT_LEFT = "LEFT";
    public static final String ALERT_DWELL = "DWELL";

    public static final long ARRIVAL_CONFIRMATION_DELAY_MS =
            90L * 1000L;
    public static final long EXIT_CONFIRMATION_DELAY_MS =
            2L * 60L * 1000L;
    public static final long SAME_ALERT_COOLDOWN_MS =
            15L * 60L * 1000L;
    public static final long OPPOSITE_ALERT_GUARD_MS =
            3L * 60L * 1000L;
    public static final long DWELL_ALERT_COOLDOWN_MS =
            2L * 60L * 60L * 1000L;
    public static final long LOCATION_MAX_AGE_MS =
            3L * 60L * 1000L;

    private static final float MIN_ACCURACY_BUFFER_METERS = 20F;
    private static final float MAX_ARRIVAL_BUFFER_METERS = 60F;
    private static final float MAX_EXIT_BUFFER_METERS = 120F;

    private SafePlaceSmartAlertPolicy() {
    }

    public static boolean isSupportedAlert(@Nullable String alertType) {
        return ALERT_ARRIVED.equals(alertType)
                || ALERT_LEFT.equals(alertType)
                || ALERT_DWELL.equals(alertType);
    }

    public static boolean shouldDispatch(
            @NonNull String alertType,
            long now,
            long lastSameAt,
            long lastOppositeAt
    ) {
        if (!isSupportedAlert(alertType) || now <= 0L) {
            return false;
        }
        long sameCooldown = ALERT_DWELL.equals(alertType)
                ? DWELL_ALERT_COOLDOWN_MS
                : SAME_ALERT_COOLDOWN_MS;
        if (!elapsed(lastSameAt, now, sameCooldown)) {
            return false;
        }
        return elapsed(lastOppositeAt, now, OPPOSITE_ALERT_GUARD_MS);
    }

    public static boolean confirmedInside(
            float distanceMeters,
            float radiusMeters,
            float accuracyMeters
    ) {
        if (!validMeasurement(distanceMeters, radiusMeters)) {
            return false;
        }
        float buffer = clampAccuracy(
                accuracyMeters,
                MAX_ARRIVAL_BUFFER_METERS
        );
        return distanceMeters <= radiusMeters + buffer;
    }

    public static boolean confirmedOutside(
            float distanceMeters,
            float radiusMeters,
            float accuracyMeters
    ) {
        if (!validMeasurement(distanceMeters, radiusMeters)) {
            return false;
        }
        float buffer = clampAccuracy(
                accuracyMeters,
                MAX_EXIT_BUFFER_METERS
        );
        return distanceMeters > radiusMeters + buffer;
    }

    public static boolean isFreshLocation(long locationTime, long now) {
        return locationTime > 0L
                && now >= locationTime
                && now - locationTime <= LOCATION_MAX_AGE_MS;
    }

    public static long deduplicationBucket(
            @NonNull String alertType,
            long now
    ) {
        long window = ALERT_DWELL.equals(alertType)
                ? DWELL_ALERT_COOLDOWN_MS
                : SAME_ALERT_COOLDOWN_MS;
        return Math.max(0L, now) / window;
    }

    @Nullable
    public static String oppositeOf(@Nullable String alertType) {
        if (ALERT_ARRIVED.equals(alertType)) {
            return ALERT_LEFT;
        }
        if (ALERT_LEFT.equals(alertType)) {
            return ALERT_ARRIVED;
        }
        return null;
    }

    private static boolean validMeasurement(
            float distanceMeters,
            float radiusMeters
    ) {
        return Float.isFinite(distanceMeters)
                && distanceMeters >= 0F
                && Float.isFinite(radiusMeters)
                && radiusMeters >= SafePlaceGeofencePolicy.MIN_RADIUS_METERS
                && radiusMeters <= SafePlaceGeofencePolicy.MAX_RADIUS_METERS;
    }

    private static float clampAccuracy(
            float accuracyMeters,
            float maximum
    ) {
        if (!Float.isFinite(accuracyMeters) || accuracyMeters <= 0F) {
            return MIN_ACCURACY_BUFFER_METERS;
        }
        return Math.max(
                MIN_ACCURACY_BUFFER_METERS,
                Math.min(maximum, accuracyMeters)
        );
    }

    private static boolean elapsed(long previousAt, long now, long window) {
        if (previousAt <= 0L) {
            return true;
        }
        if (previousAt > now + 2L * 60L * 1000L) {
            return true;
        }
        return now - previousAt >= window;
    }
}
