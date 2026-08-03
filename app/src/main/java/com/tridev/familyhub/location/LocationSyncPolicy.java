package com.tridev.familyhub.location;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Pure rules shared by the foreground service and WorkManager sync worker.
 */
public final class LocationSyncPolicy {

    private static final long RETRY_BASE_DELAY_MS = 30_000L;
    private static final long RETRY_MAX_DELAY_MS = 15L * 60L * 1000L;

    private LocationSyncPolicy() {
    }

    /**
     * A queued point must never overwrite a location captured later.
     */
    public static boolean shouldUpload(
            long remoteClientTimestamp,
            long queuedClientTimestamp
    ) {
        return queuedClientTimestamp > 0L
                && queuedClientTimestamp > remoteClientTimestamp;
    }

    /**
     * Prevents a point captured before the current explicit sharing session
     * from being uploaded after a rapid stop/start sequence.
     */
    public static boolean belongsToCurrentSharingSession(
            long capturedAt,
            long sharingEnabledAt
    ) {
        if (sharingEnabledAt <= 0L) {
            return capturedAt > 0L;
        }
        return capturedAt >= sharingEnabledAt;
    }

    /**
     * Exponential retry delay capped at fifteen minutes.
     */
    public static long retryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(0, attemptCount), 5);
        return Math.min(
                RETRY_MAX_DELAY_MS,
                RETRY_BASE_DELAY_MS * (1L << exponent)
        );
    }

    /**
     * Creates a privacy-safe deterministic id for one captured point.
     * Coordinates are hashed and are never exposed in the id itself.
     */
    @NonNull
    public static String createUpdateId(
            @NonNull String familyId,
            @NonNull String userId,
            long clientTimestamp,
            double latitude,
            double longitude
    ) {
        String source = String.format(
                Locale.US,
                "%s|%s|%d|%.5f|%.5f",
                familyId,
                userId,
                clientTimestamp,
                latitude,
                longitude
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    source.getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder result = new StringBuilder();
            for (byte value : hash) {
                result.append(String.format(Locale.US, "%02x", value));
            }
            return result.substring(0, 24);
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(source.hashCode())
                    + Long.toHexString(clientTimestamp);
        }
    }

    public static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
