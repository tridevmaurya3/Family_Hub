package com.tridev.familyhub.feature.journey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FamilyJourneyPolicyTest {

    @Test
    public void freshReliablePoint_isValidForRecording() {
        long now = 1_000_000L;
        assertTrue(FamilyJourneyPolicy.validPoint(
                25.3176,
                82.9739,
                18D,
                now - 30_000L,
                now
        ));
    }

    @Test
    public void staleOrInaccuratePoint_isRejectedForRecording() {
        long now = 1_000_000L;
        assertFalse(FamilyJourneyPolicy.validPoint(
                25.3176,
                82.9739,
                18D,
                now - FamilyJourneyPolicy.MAX_LOCATION_AGE_MS - 1L,
                now
        ));
        assertFalse(FamilyJourneyPolicy.validPoint(
                25.3176,
                82.9739,
                FamilyJourneyPolicy.MAX_ACCURACY_METERS + 1D,
                now,
                now
        ));
    }

    @Test
    public void storedHistoricalPoint_doesNotRequireLiveFreshness() {
        long now = 20L * 24L * 60L * 60L * 1000L;
        long capturedAt = now - 7L * 24L * 60L * 60L * 1000L;
        assertTrue(FamilyJourneyPolicy.validStoredPoint(
                25.3176,
                82.9739,
                25D,
                capturedAt,
                now
        ));
    }

    @Test
    public void duplicateUpdateId_isNotRecordedAgain() {
        FamilyJourneyPoint previous = point(
                "same-update",
                "TRAVELLING",
                25.0000,
                82.0000,
                1_000_000L
        );
        FamilyJourneyPoint current = point(
                "same-update",
                "TRAVELLING",
                25.0010,
                82.0010,
                1_030_000L
        );
        assertFalse(FamilyJourneyPolicy.shouldRecord(previous, current));
    }

    @Test
    public void travellingRecordsSoonerThanStationary() {
        FamilyJourneyPoint travellingPrevious = point(
                "a",
                "TRAVELLING",
                25.0000,
                82.0000,
                1_000_000L
        );
        FamilyJourneyPoint travellingCurrent = point(
                "b",
                "TRAVELLING",
                25.0004,
                82.0004,
                1_020_000L
        );
        assertTrue(FamilyJourneyPolicy.shouldRecord(
                travellingPrevious,
                travellingCurrent
        ));

        FamilyJourneyPoint stationaryPrevious = point(
                "c",
                "STATIONARY",
                25.0000,
                82.0000,
                1_000_000L
        );
        FamilyJourneyPoint stationaryCurrent = point(
                "d",
                "STATIONARY",
                25.0004,
                82.0004,
                1_020_000L
        );
        assertFalse(FamilyJourneyPolicy.shouldRecord(
                stationaryPrevious,
                stationaryCurrent
        ));
    }

    @Test
    public void impossibleJump_isRejected() {
        assertFalse(FamilyJourneyPolicy.plausibleTransition(
                10_000D,
                10_000L
        ));
    }

    @Test
    public void retentionValues_areRestrictedToSupportedChoices() {
        assertEquals(7, FamilyJourneyPolicy.normalizeRetentionDays(1));
        assertEquals(30, FamilyJourneyPolicy.normalizeRetentionDays(20));
        assertEquals(90, FamilyJourneyPolicy.normalizeRetentionDays(60));
        assertEquals(90, FamilyJourneyPolicy.normalizeRetentionDays(200));
    }

    private static FamilyJourneyPoint point(
            String updateId,
            String movement,
            double latitude,
            double longitude,
            long capturedAt
    ) {
        FamilyJourneyPoint point = new FamilyJourneyPoint();
        point.clientUpdateId = updateId;
        point.movementType = movement;
        point.latitude = latitude;
        point.longitude = longitude;
        point.accuracy = 15D;
        point.capturedAt = capturedAt;
        return point;
    }
}
