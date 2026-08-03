package com.tridev.familyhub.geofence;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.Geofence;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure validation and request-id policy for Safe Place geofences. */
public final class SafePlaceGeofencePolicy {

    public static final int MAX_GEOFENCES = 100;
    public static final float MIN_RADIUS_METERS = 100F;
    public static final float MAX_RADIUS_METERS = 5000F;
    public static final int LOITERING_DELAY_MS = 2 * 60 * 1000;
    public static final int NOTIFICATION_RESPONSIVENESS_MS = 30 * 1000;

    private static final String REQUEST_PREFIX = "safe_place:";

    private SafePlaceGeofencePolicy() {
    }

    public static boolean isValid(@Nullable SafePlace place) {
        return place != null
                && place.id > 0L
                && place.alertsEnabled
                && validCoordinates(place.latitude, place.longitude)
                && Float.isFinite(place.radiusMeters)
                && place.radiusMeters >= MIN_RADIUS_METERS
                && place.radiusMeters <= MAX_RADIUS_METERS;
    }

    public static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }

    @NonNull
    public static String requestId(long placeId) {
        if (placeId <= 0L) {
            throw new IllegalArgumentException("placeId must be positive");
        }
        return REQUEST_PREFIX + placeId;
    }

    @Nullable
    public static String placeIdFromRequestId(@Nullable String requestId) {
        if (requestId == null) {
            return null;
        }
        String value = requestId.trim();
        if (value.startsWith(REQUEST_PREFIX)) {
            value = value.substring(REQUEST_PREFIX.length());
        }
        if (value.isEmpty()) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0L ? String.valueOf(id) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static int transitionTypes() {
        return Geofence.GEOFENCE_TRANSITION_ENTER
                | Geofence.GEOFENCE_TRANSITION_EXIT
                | Geofence.GEOFENCE_TRANSITION_DWELL;
    }

    @NonNull
    public static List<SafePlace> sanitize(
            @Nullable List<SafePlace> places
    ) {
        if (places == null || places.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, SafePlace> unique = new LinkedHashMap<>();
        for (SafePlace place : places) {
            if (isValid(place)) {
                unique.put(place.id, place);
            }
        }

        List<SafePlace> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingLong(value -> value.id));
        if (result.size() > MAX_GEOFENCES) {
            return new ArrayList<>(result.subList(0, MAX_GEOFENCES));
        }
        return result;
    }
}
