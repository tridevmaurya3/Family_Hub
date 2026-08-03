package com.tridev.familyhub.feature.journey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FamilyJourneySummaryTest {

    @Test
    public void summaryCalculatesDistanceAndEndpoints() {
        List<FamilyJourneyPoint> points = new ArrayList<>();
        points.add(point(
                25.3176,
                82.9739,
                1_000_000L,
                "WALKING",
                "Home",
                ""
        ));
        points.add(point(
                25.3186,
                82.9749,
                1_120_000L,
                "WALKING",
                "",
                "Varanasi"
        ));

        FamilyJourneySummary summary = FamilyJourneySummary.from(points);

        assertEquals(2, summary.points.size());
        assertTrue(summary.totalDistanceMeters > 100D);
        assertEquals("Home", summary.startPlace);
        assertEquals("Varanasi", summary.endPlace);
        assertEquals(1, summary.segments.size());
    }

    @Test
    public void movementChangeCreatesSeparateSegments() {
        List<FamilyJourneyPoint> points = new ArrayList<>();
        points.add(point(
                25.0000,
                82.0000,
                1_000_000L,
                "WALKING",
                "",
                ""
        ));
        points.add(point(
                25.0003,
                82.0003,
                1_060_000L,
                "WALKING",
                "",
                ""
        ));
        points.add(point(
                25.0010,
                82.0010,
                1_120_000L,
                "TRAVELLING",
                "",
                ""
        ));

        FamilyJourneySummary summary = FamilyJourneySummary.from(points);

        assertEquals(2, summary.segments.size());
        assertEquals("WALKING", summary.segments.get(0).movementType);
        assertEquals("TRAVELLING", summary.segments.get(1).movementType);
    }

    @Test
    public void safePlaceSamplesCreateVisit() {
        List<FamilyJourneyPoint> points = new ArrayList<>();
        points.add(point(
                25.0000,
                82.0000,
                1_000_000L,
                "STATIONARY",
                "Home",
                ""
        ));
        points.add(point(
                25.0000,
                82.0000,
                1_300_000L,
                "STATIONARY",
                "Home",
                ""
        ));
        points.add(point(
                25.0020,
                82.0020,
                1_600_000L,
                "TRAVELLING",
                "",
                "Road"
        ));

        FamilyJourneySummary summary = FamilyJourneySummary.from(points);

        assertEquals(1, summary.safePlaceVisits.size());
        assertEquals("Home", summary.safePlaceVisits.get(0).safePlaceName);
        assertEquals(
                300_000L,
                summary.safePlaceVisits.get(0).durationMs()
        );
    }

    private static FamilyJourneyPoint point(
            double latitude,
            double longitude,
            long capturedAt,
            String movement,
            String safePlace,
            String placeLabel
    ) {
        FamilyJourneyPoint point = new FamilyJourneyPoint();
        point.latitude = latitude;
        point.longitude = longitude;
        point.accuracy = 15D;
        point.capturedAt = capturedAt;
        point.movementType = movement;
        point.safePlaceName = safePlace;
        point.placeLabel = placeLabel;
        return point;
    }
}
