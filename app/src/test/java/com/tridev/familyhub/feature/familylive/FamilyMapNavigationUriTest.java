package com.tridev.familyhub.feature.familylive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FamilyMapNavigationUriTest {

    @Test
    public void drivingNavigationUsesGoogleNavigationScheme() {
        assertEquals(
                "google.navigation:q=25.2667000,83.2686000&mode=d",
                FamilyMapNavigationUri.googleNavigation(
                        25.2667D,
                        83.2686D,
                        FamilyMapNavigationUri.MODE_DRIVING
                )
        );
    }

    @Test
    public void walkingNavigationUsesWalkingMode() {
        assertEquals(
                "google.navigation:q=25.2667000,83.2686000&mode=w",
                FamilyMapNavigationUri.googleNavigation(
                        25.2667D,
                        83.2686D,
                        FamilyMapNavigationUri.MODE_WALKING
                )
        );
    }

    @Test
    public void webDirectionsAreQuotaSafeExternalLinks() {
        String result = FamilyMapNavigationUri.webDirections(
                25.2667D,
                83.2686D,
                FamilyMapNavigationUri.MODE_DRIVING
        );

        assertTrue(result.startsWith(
                "https://www.google.com/maps/dir/?api=1"
        ));
        assertTrue(result.contains("travelmode=driving"));
        assertTrue(result.contains("25.2667000%2C83.2686000"));
    }

    @Test
    public void geoLocationEncodesMemberLabel() {
        String result = FamilyMapNavigationUri.geoLocation(
                25.2667D,
                83.2686D,
                "Kusum Maurya"
        );

        assertTrue(result.startsWith("geo:0,0?q="));
        assertTrue(result.contains("Kusum%20Maurya"));
    }

    @Test
    public void invalidZeroCoordinatesAreRejected() {
        assertFalse(FamilyMapNavigationUri.validCoordinates(0D, 0D));
    }
}
