package com.tridev.familyhub.geofence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafePlaceSmartAlertPolicyTest {

    @Test
    public void arrival_requiresStableCooldownWindow() {
        long now = 1_000_000L;
        assertTrue(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                now,
                0L,
                0L
        ));
        assertFalse(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                now,
                now - 60_000L,
                0L
        ));
    }

    @Test
    public void oppositeTransitionGuard_suppressesRapidGpsBounce() {
        long now = 2_000_000L;
        assertFalse(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_LEFT,
                now,
                0L,
                now - 60_000L
        ));
        assertTrue(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_LEFT,
                now,
                0L,
                now - SafePlaceSmartAlertPolicy.OPPOSITE_ALERT_GUARD_MS
        ));
    }

    @Test
    public void confirmedInside_allowsAccuracyBuffer() {
        assertTrue(SafePlaceSmartAlertPolicy.confirmedInside(
                225F,
                200F,
                30F
        ));
        assertFalse(SafePlaceSmartAlertPolicy.confirmedInside(
                300F,
                200F,
                30F
        ));
    }

    @Test
    public void confirmedOutside_requiresRadiusAndAccuracyBuffer() {
        assertFalse(SafePlaceSmartAlertPolicy.confirmedOutside(
                250F,
                200F,
                60F
        ));
        assertTrue(SafePlaceSmartAlertPolicy.confirmedOutside(
                321F,
                200F,
                60F
        ));
    }

    @Test
    public void staleOrFutureLocation_isRejected() {
        long now = 5_000_000L;
        assertTrue(SafePlaceSmartAlertPolicy.isFreshLocation(
                now - 30_000L,
                now
        ));
        assertFalse(SafePlaceSmartAlertPolicy.isFreshLocation(
                now - SafePlaceSmartAlertPolicy.LOCATION_MAX_AGE_MS - 1L,
                now
        ));
        assertFalse(SafePlaceSmartAlertPolicy.isFreshLocation(
                now + 1L,
                now
        ));
    }

    @Test
    public void oppositeTypes_areMappedSafely() {
        assertTrue(SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(
                SafePlaceSmartAlertPolicy.oppositeOf(
                        SafePlaceSmartAlertPolicy.ALERT_ARRIVED
                )
        ));
        assertTrue(SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(
                SafePlaceSmartAlertPolicy.oppositeOf(
                        SafePlaceSmartAlertPolicy.ALERT_LEFT
                )
        ));
        assertNull(SafePlaceSmartAlertPolicy.oppositeOf(
                SafePlaceSmartAlertPolicy.ALERT_DWELL
        ));
    }

    @Test
    public void dwellUsesItsLongerDeduplicationWindow() {
        long now = 24L * 60L * 60L * 1000L;
        assertEquals(
                now / SafePlaceSmartAlertPolicy.DWELL_ALERT_COOLDOWN_MS,
                SafePlaceSmartAlertPolicy.deduplicationBucket(
                        SafePlaceSmartAlertPolicy.ALERT_DWELL,
                        now
                )
        );
    }

    @Test
    public void dispatchRecoversFromLargeFutureClockSkew() {
        long now = 5_000_000L;
        assertFalse(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                now,
                now + 60_000L,
                0L
        ));
        assertTrue(SafePlaceSmartAlertPolicy.shouldDispatch(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                now,
                now + 120_001L,
                0L
        ));
    }
}
