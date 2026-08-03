package com.tridev.familyhub.feature.familylive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FamilyMapDistanceTest {

    @Test
    public void identicalPointsHaveZeroDistance() {
        assertEquals(
                0D,
                FamilyMapDistance.meters(25.2677D, 83.2695D,
                        25.2677D, 83.2695D),
                0.001D
        );
    }

    @Test
    public void nearbyPointsReturnExpectedDistanceRange() {
        double distance = FamilyMapDistance.meters(
                25.2677D,
                83.2695D,
                25.2687D,
                83.2695D
        );
        assertTrue(distance > 100D);
        assertTrue(distance < 120D);
    }

    @Test
    public void invalidCoordinatesReturnNaN() {
        assertTrue(Double.isNaN(FamilyMapDistance.meters(
                0D,
                0D,
                25.2677D,
                83.2695D
        )));
    }

    @Test
    public void formatUsesMetersAndKilometres() {
        assertEquals("250 m", FamilyMapDistance.format(250D));
        assertEquals("1.5 km", FamilyMapDistance.format(1500D));
    }
}
