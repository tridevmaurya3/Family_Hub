package com.tridev.familyhub.feature.documents;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

/** Shared expiry calculations for the Documents Vault UI and notifications. */
public final class DocumentExpiryPolicy {

    public static final String STATUS_NO_EXPIRY = "NO_EXPIRY";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_EXPIRING = "EXPIRING";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1L);

    private DocumentExpiryPolicy() {
    }

    @NonNull
    public static String status(
            long expiryAt,
            long now,
            int reminderDays
    ) {
        if (expiryAt <= 0L) {
            return STATUS_NO_EXPIRY;
        }
        long endOfToday = startOfDay(now) + DAY_MILLIS - 1L;
        if (expiryAt < startOfDay(now)) {
            return STATUS_EXPIRED;
        }
        long threshold = endOfToday
                + TimeUnit.DAYS.toMillis(Math.max(1, reminderDays));
        return expiryAt <= threshold
                ? STATUS_EXPIRING
                : STATUS_VALID;
    }

    public static long daysRemaining(long expiryAt, long now) {
        if (expiryAt <= 0L) {
            return Long.MAX_VALUE;
        }
        long difference = startOfDay(expiryAt) - startOfDay(now);
        return difference / DAY_MILLIS;
    }

    public static boolean shouldNotify(
            long expiryAt,
            long now,
            int reminderDays
    ) {
        String status = status(expiryAt, now, reminderDays);
        return STATUS_EXPIRING.equals(status)
                || STATUS_EXPIRED.equals(status);
    }

    public static long startOfDay(long timeMillis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(timeMillis);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
