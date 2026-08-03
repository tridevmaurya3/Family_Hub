package com.tridev.familyhub.feature.journey;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Pure sampling, retention and date utilities for Family Journey History. */
public final class FamilyJourneyPolicy {

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int MIN_RETENTION_DAYS = 7;
    public static final int MAX_RETENTION_DAYS = 90;

    public static final long MAX_LOCATION_AGE_MS = 2L * 60L * 1000L;
    public static final float MAX_ACCURACY_METERS = 100F;
    public static final long FORCE_SAMPLE_GAP_MS = 10L * 60L * 1000L;
    public static final long CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    public static final double MAX_PLAUSIBLE_SPEED_MPS = 75D;

    private FamilyJourneyPolicy() {
    }

    /** Validates a fresh location before it is recorded. */
    public static boolean validPoint(
            double latitude,
            double longitude,
            double accuracy,
            long capturedAt,
            long now
    ) {
        return validStoredPoint(
                latitude,
                longitude,
                accuracy,
                capturedAt,
                now
        ) && now - capturedAt <= MAX_LOCATION_AGE_MS;
    }

    /** Validates a previously stored point without applying live freshness. */
    public static boolean validStoredPoint(
            double latitude,
            double longitude,
            double accuracy,
            long capturedAt,
            long now
    ) {
        return latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(Math.abs(latitude) < 0.0000001D
                && Math.abs(longitude) < 0.0000001D)
                && accuracy > 0D
                && accuracy <= MAX_ACCURACY_METERS
                && capturedAt > 0L
                && capturedAt <= now + 15_000L;
    }

    public static boolean shouldRecord(
            @Nullable FamilyJourneyPoint previous,
            @NonNull FamilyJourneyPoint current
    ) {
        if (previous == null) {
            return true;
        }
        if (!current.clientUpdateId.isEmpty()
                && current.clientUpdateId.equals(previous.clientUpdateId)) {
            return false;
        }

        long gap = Math.max(0L, current.capturedAt - previous.capturedAt);
        if (gap >= FORCE_SAMPLE_GAP_MS) {
            return true;
        }

        double distance = distanceMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude
        );
        if (!plausibleTransition(distance, gap)) {
            return false;
        }

        if (!normalizeMovement(previous.movementType).equals(
                normalizeMovement(current.movementType)
        ) && gap >= 20_000L) {
            return true;
        }
        if (!safe(previous.safePlaceId).equals(safe(current.safePlaceId))) {
            return true;
        }

        String movement = normalizeMovement(current.movementType);
        long minGap;
        double minDistance;
        switch (movement) {
            case "TRAVELLING":
            case "CYCLING":
                minGap = 15_000L;
                minDistance = 25D;
                break;
            case "WALKING":
                minGap = 30_000L;
                minDistance = 12D;
                break;
            case "STATIONARY":
                minGap = 5L * 60L * 1000L;
                minDistance = 75D;
                break;
            default:
                minGap = 90_000L;
                minDistance = 30D;
                break;
        }
        return gap >= minGap && distance >= minDistance;
    }

    public static boolean plausibleTransition(double distanceMeters, long gapMs) {
        if (distanceMeters < 0D || gapMs <= 0L) {
            return distanceMeters <= 20D;
        }
        double speed = distanceMeters / (gapMs / 1000D);
        return speed <= MAX_PLAUSIBLE_SPEED_MPS;
    }

    public static int normalizeRetentionDays(int value) {
        if (value <= MIN_RETENTION_DAYS) {
            return MIN_RETENTION_DAYS;
        }
        if (value >= MAX_RETENTION_DAYS) {
            return MAX_RETENTION_DAYS;
        }
        return value <= 30 ? 30 : 90;
    }

    @NonNull
    public static String dayKey(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(Math.max(0L, timestamp)));
    }

    public static long startOfDay(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static long retentionCutoffDay(long now, int retentionDays) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startOfDay(now));
        calendar.add(Calendar.DAY_OF_YEAR, -normalizeRetentionDays(retentionDays));
        return calendar.getTimeInMillis();
    }

    public static double distanceMeters(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2
    ) {
        double earthRadius = 6_371_000D;
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = Math.toRadians(latitude2 - latitude1);
        double deltaLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2D) * Math.sin(deltaLon / 2D);
        return earthRadius * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    @NonNull
    public static String normalizeMovement(@Nullable String value) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "STATIONARY":
            case "WALKING":
            case "CYCLING":
            case "TRAVELLING":
                return normalized;
            default:
                return "UNKNOWN";
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
