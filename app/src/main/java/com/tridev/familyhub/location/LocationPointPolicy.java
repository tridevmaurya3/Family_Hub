package com.tridev.familyhub.location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure validation rules for Family Live location points.
 *
 * The policy rejects invalid coordinates, mock-provider points, stale fixes,
 * poor-accuracy fixes, out-of-order fixes and physically implausible jumps.
 * A suspicious jump is not accepted until a second nearby point confirms it.
 */
public final class LocationPointPolicy {

    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT_INVALID_COORDINATES =
            "REJECT_INVALID_COORDINATES";
    public static final String REJECT_MOCK = "REJECT_MOCK";
    public static final String REJECT_STALE = "REJECT_STALE";
    public static final String REJECT_FUTURE = "REJECT_FUTURE";
    public static final String REJECT_POOR_ACCURACY =
            "REJECT_POOR_ACCURACY";
    public static final String REJECT_OUT_OF_ORDER =
            "REJECT_OUT_OF_ORDER";
    public static final String REQUIRE_JUMP_CONFIRMATION =
            "REQUIRE_JUMP_CONFIRMATION";

    public static final long MAX_LIVE_POINT_AGE_MS = 2L * 60L * 1000L;
    public static final long MAX_LAST_KNOWN_AGE_MS = 5L * 60L * 1000L;
    public static final long MAX_FUTURE_SKEW_MS = 60_000L;
    public static final float MAX_LIVE_ACCURACY_METERS = 300F;
    public static final float MAX_LAST_KNOWN_ACCURACY_METERS = 500F;

    private static final double EARTH_RADIUS_METERS = 6_371_000D;
    private static final double MAX_IMPLAUSIBLE_SPEED_MPS = 100D;
    private static final double MIN_JUMP_DISTANCE_METERS = 1_000D;
    private static final double JUMP_CONFIRMATION_RADIUS_METERS = 750D;
    private static final long JUMP_CONFIRMATION_WINDOW_MS = 2L * 60L * 1000L;

    private LocationPointPolicy() {
    }

    @NonNull
    public static String evaluate(
            @NonNull Point current,
            @Nullable Point previousAccepted,
            long nowWallTimeMs,
            long nowElapsedRealtimeNanos,
            boolean lastKnownSource,
            boolean mockLocation
    ) {
        if (!hasValidCoordinates(current.latitude, current.longitude)) {
            return REJECT_INVALID_COORDINATES;
        }

        if (mockLocation) {
            return REJECT_MOCK;
        }

        float maximumAccuracy = lastKnownSource
                ? MAX_LAST_KNOWN_ACCURACY_METERS
                : MAX_LIVE_ACCURACY_METERS;
        if (!Float.isFinite(current.accuracyMeters)
                || current.accuracyMeters <= 0F
                || current.accuracyMeters > maximumAccuracy) {
            return REJECT_POOR_ACCURACY;
        }

        long ageMs = pointAgeMs(
                current,
                nowWallTimeMs,
                nowElapsedRealtimeNanos
        );
        long maximumAgeMs = lastKnownSource
                ? MAX_LAST_KNOWN_AGE_MS
                : MAX_LIVE_POINT_AGE_MS;
        if (ageMs > maximumAgeMs) {
            return REJECT_STALE;
        }
        if (ageMs < -MAX_FUTURE_SKEW_MS) {
            return REJECT_FUTURE;
        }

        if (previousAccepted == null) {
            return ACCEPT;
        }

        long elapsedMs = elapsedBetweenMs(previousAccepted, current);
        if (elapsedMs <= 0L) {
            return REJECT_OUT_OF_ORDER;
        }

        double distanceMeters = distanceMeters(
                previousAccepted.latitude,
                previousAccepted.longitude,
                current.latitude,
                current.longitude
        );
        double uncertaintyMeters = Math.max(
                50D,
                previousAccepted.accuracyMeters + current.accuracyMeters
        );
        double effectiveDistanceMeters = Math.max(
                0D,
                distanceMeters - uncertaintyMeters
        );
        double speedMetersPerSecond = effectiveDistanceMeters
                / Math.max(1D, elapsedMs / 1000D);

        if (effectiveDistanceMeters >= MIN_JUMP_DISTANCE_METERS
                && speedMetersPerSecond > MAX_IMPLAUSIBLE_SPEED_MPS) {
            return REQUIRE_JUMP_CONFIRMATION;
        }

        return ACCEPT;
    }

    public static boolean confirmsSuspiciousJump(
            @NonNull Point candidate,
            @NonNull Point confirmation
    ) {
        long elapsedMs = elapsedBetweenMs(candidate, confirmation);
        if (elapsedMs <= 0L || elapsedMs > JUMP_CONFIRMATION_WINDOW_MS) {
            return false;
        }

        double allowedRadiusMeters = Math.max(
                JUMP_CONFIRMATION_RADIUS_METERS,
                candidate.accuracyMeters + confirmation.accuracyMeters
        );
        return distanceMeters(
                candidate.latitude,
                candidate.longitude,
                confirmation.latitude,
                confirmation.longitude
        ) <= allowedRadiusMeters;
    }

    public static long pointAgeMs(
            @NonNull Point point,
            long nowWallTimeMs,
            long nowElapsedRealtimeNanos
    ) {
        if (point.elapsedRealtimeNanos > 0L
                && nowElapsedRealtimeNanos > 0L) {
            return (nowElapsedRealtimeNanos - point.elapsedRealtimeNanos)
                    / 1_000_000L;
        }
        return nowWallTimeMs - point.wallTimeMs;
    }

    public static long safeCaptureTimeMs(
            @NonNull Point point,
            long nowWallTimeMs
    ) {
        if (point.wallTimeMs <= 0L
                || point.wallTimeMs > nowWallTimeMs + MAX_FUTURE_SKEW_MS) {
            return nowWallTimeMs;
        }
        return Math.min(point.wallTimeMs, nowWallTimeMs);
    }

    public static boolean hasValidCoordinates(
            double latitude,
            double longitude
    ) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }

    private static long elapsedBetweenMs(
            @NonNull Point first,
            @NonNull Point second
    ) {
        if (first.elapsedRealtimeNanos > 0L
                && second.elapsedRealtimeNanos > 0L) {
            return (second.elapsedRealtimeNanos
                    - first.elapsedRealtimeNanos) / 1_000_000L;
        }
        return second.wallTimeMs - first.wallTimeMs;
    }

    private static double distanceMeters(
            double firstLatitude,
            double firstLongitude,
            double secondLatitude,
            double secondLongitude
    ) {
        double firstLatRadians = Math.toRadians(firstLatitude);
        double secondLatRadians = Math.toRadians(secondLatitude);
        double latitudeDifference = Math.toRadians(
                secondLatitude - firstLatitude
        );
        double longitudeDifference = Math.toRadians(
                secondLongitude - firstLongitude
        );

        double haversine = Math.sin(latitudeDifference / 2D)
                * Math.sin(latitudeDifference / 2D)
                + Math.cos(firstLatRadians)
                * Math.cos(secondLatRadians)
                * Math.sin(longitudeDifference / 2D)
                * Math.sin(longitudeDifference / 2D);
        double centralAngle = 2D * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(1D - haversine)
        );
        return EARTH_RADIUS_METERS * centralAngle;
    }

    public static final class Point {
        public final double latitude;
        public final double longitude;
        public final float accuracyMeters;
        public final long wallTimeMs;
        public final long elapsedRealtimeNanos;

        public Point(
                double latitude,
                double longitude,
                float accuracyMeters,
                long wallTimeMs,
                long elapsedRealtimeNanos
        ) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
            this.wallTimeMs = wallTimeMs;
            this.elapsedRealtimeNanos = elapsedRealtimeNanos;
        }
    }
}
