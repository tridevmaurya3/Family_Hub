package com.tridev.familyhub.geofence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.location.Geofence;
import com.tridev.familyhub.data.local.entity.SafePlace;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SafePlaceGeofencePolicyTest {

    @Test
    public void requestId_roundTripsAndSupportsLegacyIds() {
        assertEquals(
                "42",
                SafePlaceGeofencePolicy.placeIdFromRequestId(
                        SafePlaceGeofencePolicy.requestId(42L)
                )
        );
        assertEquals(
                "42",
                SafePlaceGeofencePolicy.placeIdFromRequestId("42")
        );
        assertNull(SafePlaceGeofencePolicy.placeIdFromRequestId("bad"));
    }

    @Test
    public void validPlace_requiresEnabledCoordinatesAndSupportedRadius() {
        SafePlace place = place(1L, true, 25.1D, 83.2D, 200F);
        assertTrue(SafePlaceGeofencePolicy.isValid(place));

        place.alertsEnabled = false;
        assertFalse(SafePlaceGeofencePolicy.isValid(place));
        place.alertsEnabled = true;
        place.latitude = 0D;
        place.longitude = 0D;
        assertFalse(SafePlaceGeofencePolicy.isValid(place));
        place.latitude = 25.1D;
        place.longitude = 83.2D;
        place.radiusMeters = 99F;
        assertFalse(SafePlaceGeofencePolicy.isValid(place));
    }

    @Test
    public void transitionTypes_includeEnterExitAndDwell() {
        int transitions = SafePlaceGeofencePolicy.transitionTypes();
        assertTrue((transitions & Geofence.GEOFENCE_TRANSITION_ENTER) != 0);
        assertTrue((transitions & Geofence.GEOFENCE_TRANSITION_EXIT) != 0);
        assertTrue((transitions & Geofence.GEOFENCE_TRANSITION_DWELL) != 0);
    }

    @Test
    public void sanitize_removesInvalidDuplicatesAndCapsAtOneHundred() {
        List<SafePlace> places = new ArrayList<>();
        for (long id = 1L; id <= 105L; id++) {
            places.add(place(id, true, 25D + id / 1000D, 83D, 200F));
        }
        places.add(place(10L, true, 26D, 84D, 300F));
        places.add(place(200L, false, 25D, 83D, 200F));

        List<SafePlace> result = SafePlaceGeofencePolicy.sanitize(places);

        assertEquals(SafePlaceGeofencePolicy.MAX_GEOFENCES, result.size());
        assertEquals(1L, result.get(0).id);
        assertEquals(100L, result.get(result.size() - 1).id);
    }

    private SafePlace place(
            long id,
            boolean enabled,
            double latitude,
            double longitude,
            float radius
    ) {
        SafePlace place = new SafePlace();
        place.id = id;
        place.name = "Place " + id;
        place.alertsEnabled = enabled;
        place.latitude = latitude;
        place.longitude = longitude;
        place.radiusMeters = radius;
        return place;
    }
}
