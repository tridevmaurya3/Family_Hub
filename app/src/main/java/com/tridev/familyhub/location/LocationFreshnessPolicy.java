package com.tridev.familyhub.location;

/** Determines whether a cloud timestamp can represent a recent location. */
public final class LocationFreshnessPolicy {

    private static final long MAX_FUTURE_CLOCK_SKEW_MS = 2L * 60L * 1000L;

    private LocationFreshnessPolicy() {
    }

    public static boolean isFresh(
            long locationUpdatedAt,
            long now,
            long freshnessWindowMs
    ) {
        if (locationUpdatedAt <= 0L
                || now <= 0L
                || freshnessWindowMs < 0L) {
            return false;
        }
        if (locationUpdatedAt > now + MAX_FUTURE_CLOCK_SKEW_MS) {
            return false;
        }
        return locationUpdatedAt >= now - freshnessWindowMs;
    }

    public static boolean isStale(
            long locationUpdatedAt,
            long now,
            long freshnessWindowMs
    ) {
        return !isFresh(locationUpdatedAt, now, freshnessWindowMs);
    }
}
