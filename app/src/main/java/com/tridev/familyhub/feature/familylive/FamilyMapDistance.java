package com.tridev.familyhub.feature.familylive;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Pure distance helpers used by Family Map comparison and nearest-member UI. */
public final class FamilyMapDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8D;

    private FamilyMapDistance() {
    }

    public static double meters(
            double latitudeOne,
            double longitudeOne,
            double latitudeTwo,
            double longitudeTwo
    ) {
        if (!valid(latitudeOne, longitudeOne)
                || !valid(latitudeTwo, longitudeTwo)) {
            return Double.NaN;
        }

        double latitudeDelta = Math.toRadians(latitudeTwo - latitudeOne);
        double longitudeDelta = Math.toRadians(longitudeTwo - longitudeOne);
        double firstLatitude = Math.toRadians(latitudeOne);
        double secondLatitude = Math.toRadians(latitudeTwo);

        double sinLatitude = Math.sin(latitudeDelta / 2D);
        double sinLongitude = Math.sin(longitudeDelta / 2D);
        double haversine = sinLatitude * sinLatitude
                + Math.cos(firstLatitude)
                * Math.cos(secondLatitude)
                * sinLongitude
                * sinLongitude;
        double centralAngle = 2D * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(Math.max(0D, 1D - haversine))
        );
        return EARTH_RADIUS_METERS * centralAngle;
    }

    @NonNull
    public static String format(double meters) {
        if (!Double.isFinite(meters) || meters < 0D) {
            return "—";
        }
        if (meters < 1000D) {
            return String.format(
                    Locale.getDefault(),
                    "%d m",
                    Math.round(meters)
            );
        }
        if (meters < 10_000D) {
            return String.format(
                    Locale.getDefault(),
                    "%.1f km",
                    meters / 1000D
            );
        }
        return String.format(
                Locale.getDefault(),
                "%d km",
                Math.round(meters / 1000D)
        );
    }

    private static boolean valid(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }
}
