package com.tridev.familyhub.feature.familylive;

import androidx.annotation.NonNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure URI builder for external map and navigation apps.
 *
 * These links do not call the Google Directions API from Family Hub, so they
 * avoid consuming an in-app directions quota. The receiving map application
 * is responsible for route calculation and live navigation.
 */
public final class FamilyMapNavigationUri {

    public static final String MODE_DRIVING = "driving";
    public static final String MODE_WALKING = "walking";

    private FamilyMapNavigationUri() {
    }

    @NonNull
    public static String googleNavigation(
            double latitude,
            double longitude,
            @NonNull String travelMode
    ) {
        requireValid(latitude, longitude);
        String mode = MODE_WALKING.equals(travelMode) ? "w" : "d";
        return "google.navigation:q="
                + coordinate(latitude, longitude)
                + "&mode="
                + mode;
    }

    @NonNull
    public static String webDirections(
            double latitude,
            double longitude,
            @NonNull String travelMode
    ) {
        requireValid(latitude, longitude);
        String mode = MODE_WALKING.equals(travelMode)
                ? MODE_WALKING
                : MODE_DRIVING;
        return "https://www.google.com/maps/dir/?api=1&destination="
                + encode(coordinate(latitude, longitude))
                + "&travelmode="
                + mode;
    }

    @NonNull
    public static String geoLocation(
            double latitude,
            double longitude,
            @NonNull String label
    ) {
        requireValid(latitude, longitude);
        String query = coordinate(latitude, longitude)
                + " ("
                + safeLabel(label)
                + ")";
        return "geo:0,0?q=" + encode(query);
    }

    @NonNull
    public static String webLocation(
            double latitude,
            double longitude
    ) {
        requireValid(latitude, longitude);
        return "https://www.google.com/maps/search/?api=1&query="
                + encode(coordinate(latitude, longitude));
    }

    public static boolean validCoordinates(
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

    @NonNull
    private static String coordinate(double latitude, double longitude) {
        return String.format(
                Locale.US,
                "%.7f,%.7f",
                latitude,
                longitude
        );
    }

    @NonNull
    private static String safeLabel(@NonNull String label) {
        String value = label.trim();
        return value.isEmpty() ? "Family member" : value;
    }

    @NonNull
    private static String encode(@NonNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void requireValid(double latitude, double longitude) {
        if (!validCoordinates(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid map coordinates");
        }
    }
}
