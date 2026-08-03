package com.tridev.familyhub.location;

import androidx.annotation.NonNull;

/** Timing and safety policy for temporary high-frequency Family Map viewing. */
public final class FamilyLivePrecisionPolicy {

    public static final long MAP_SESSION_TTL_MS = 45_000L;
    public static final long MAP_SESSION_HEARTBEAT_MS = 15_000L;
    public static final long ONE_SHOT_SESSION_TTL_MS = 25_000L;
    public static final long SESSION_CLOCK_SKEW_MS = 15_000L;
    public static final long PRECISION_INTERVAL_MS = 3_000L;
    public static final long PRECISION_MIN_INTERVAL_MS = 1_000L;
    public static final float PRECISION_MIN_DISTANCE_METERS = 0F;
    public static final int CRITICAL_BATTERY_PERCENTAGE = 8;

    private FamilyLivePrecisionPolicy() {
    }

    public static boolean isSessionActive(
            boolean active,
            long requestedAt,
            long expiresAt,
            long now
    ) {
        if (!active || now <= 0L || requestedAt <= 0L || expiresAt <= 0L) {
            return false;
        }
        if (requestedAt > now + SESSION_CLOCK_SKEW_MS) {
            return false;
        }
        if (expiresAt <= now || expiresAt < requestedAt) {
            return false;
        }
        return expiresAt - requestedAt <= MAP_SESSION_TTL_MS
                + SESSION_CLOCK_SKEW_MS;
    }

    public static boolean canUsePrecisionTracking(
            int batteryPercentage,
            boolean charging
    ) {
        return charging
                || batteryPercentage < 0
                || batteryPercentage > CRITICAL_BATTERY_PERCENTAGE;
    }

    public static long safeExpiry(
            long requestedAt,
            long requestedTtlMs
    ) {
        long ttl = Math.max(
                5_000L,
                Math.min(MAP_SESSION_TTL_MS, requestedTtlMs)
        );
        if (requestedAt > Long.MAX_VALUE - ttl) {
            return Long.MAX_VALUE;
        }
        return requestedAt + ttl;
    }

    @NonNull
    public static String sanitizeMode(@NonNull String mode) {
        return "MAP_VISIBLE".equals(mode)
                ? "MAP_VISIBLE"
                : "MANUAL_REFRESH";
    }
}
