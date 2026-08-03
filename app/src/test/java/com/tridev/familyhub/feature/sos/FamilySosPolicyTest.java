package com.tridev.familyhub.feature.sos;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FamilySosPolicyTest {

    @Test
    public void supportedStatuses_areRecognized() {
        assertTrue(FamilySosPolicy.isSupportedStatus(
                FamilySosPolicy.STATUS_ACTIVE
        ));
        assertTrue(FamilySosPolicy.isSupportedStatus(
                FamilySosPolicy.STATUS_CANCELLED
        ));
        assertTrue(FamilySosPolicy.isSupportedStatus(
                FamilySosPolicy.STATUS_RESOLVED
        ));
        assertFalse(FamilySosPolicy.isSupportedStatus("UNKNOWN"));
    }

    @Test
    public void validCoordinates_rejectInvalidOrEmptyPoints() {
        assertTrue(FamilySosPolicy.validCoordinates(
                25.2677D,
                83.2680D,
                25D
        ));
        assertFalse(FamilySosPolicy.validCoordinates(0D, 0D, 10D));
        assertFalse(FamilySosPolicy.validCoordinates(91D, 83D, 10D));
        assertFalse(FamilySosPolicy.validCoordinates(25D, 181D, 10D));
        assertFalse(FamilySosPolicy.validCoordinates(25D, 83D, 0D));
    }

    @Test
    public void latestSharedLocation_hasTwentyFourHourLimit() {
        long now = 2_000_000_000L;
        assertTrue(FamilySosPolicy.isFreshLocation(
                now - FamilySosPolicy.LOCATION_MAX_AGE_MS,
                now
        ));
        assertFalse(FamilySosPolicy.isFreshLocation(
                now - FamilySosPolicy.LOCATION_MAX_AGE_MS - 1L,
                now
        ));
        assertFalse(FamilySosPolicy.isFreshLocation(now + 1L, now));
    }

    @Test
    public void processActiveNotification_skipsOldSos() {
        long now = 3_000_000_000L;
        assertTrue(FamilySosPolicy.shouldNotifyLive(
                now - FamilySosPolicy.LIVE_NOTIFICATION_MAX_AGE_MS,
                now
        ));
        assertFalse(FamilySosPolicy.shouldNotifyLive(
                now - FamilySosPolicy.LIVE_NOTIFICATION_MAX_AGE_MS - 1L,
                now
        ));
    }
}
