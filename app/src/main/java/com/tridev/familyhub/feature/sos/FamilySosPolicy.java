package com.tridev.familyhub.feature.sos;

import androidx.annotation.Nullable;

/** Pure validation and timing rules for the consent-based Family SOS flow. */
public final class FamilySosPolicy {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String RESPONSE_RESPONDING = "RESPONDING";

    public static final long HOLD_DURATION_MS = 3_000L;
    public static final long LOCATION_MAX_AGE_MS = 24L * 60L * 60L * 1_000L;
    public static final long LIVE_NOTIFICATION_MAX_AGE_MS =
            15L * 60L * 1_000L;
    public static final long LOCAL_REQUEST_COOLDOWN_MS = 10_000L;
    public static final int MAX_HISTORY_ITEMS = 60;

    private FamilySosPolicy() {
    }

    public static boolean isSupportedStatus(@Nullable String status) {
        return STATUS_ACTIVE.equals(status)
                || STATUS_CANCELLED.equals(status)
                || STATUS_RESOLVED.equals(status);
    }

    public static boolean isActive(@Nullable String status) {
        return STATUS_ACTIVE.equals(status);
    }

    public static boolean validCoordinates(
            double latitude,
            double longitude,
            double accuracy
    ) {
        return Double.isFinite(latitude)
                && latitude >= -90D
                && latitude <= 90D
                && Double.isFinite(longitude)
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D)
                && Double.isFinite(accuracy)
                && accuracy > 0D
                && accuracy <= 10_000D;
    }

    public static boolean isFreshLocation(long locationUpdatedAt, long now) {
        return locationUpdatedAt > 0L
                && now >= locationUpdatedAt
                && now - locationUpdatedAt <= LOCATION_MAX_AGE_MS;
    }

    public static boolean shouldNotifyLive(long createdAt, long now) {
        return createdAt > 0L
                && now >= createdAt
                && now - createdAt <= LIVE_NOTIFICATION_MAX_AGE_MS;
    }
}
