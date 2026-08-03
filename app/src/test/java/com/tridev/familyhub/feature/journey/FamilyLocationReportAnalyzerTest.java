package com.tridev.familyhub.feature.journey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tridev.familyhub.feature.insights.FamilyLocationReport;
import com.tridev.familyhub.feature.insights.FamilyLocationReportAnalyzer;
import com.tridev.familyhub.feature.insights.FamilyReportRange;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FamilyLocationReportAnalyzerTest {

    @Test
    public void analyzerAggregatesDistanceMovementAndSafePlaceTime() {
        long day = dayStart(2026, Calendar.AUGUST, 1);
        FamilyJourneyRepository.Member member = new FamilyJourneyRepository.Member(
                "member-one",
                "Papa",
                "OWNER_ADMIN",
                true,
                true
        );
        List<FamilyJourneyPoint> points = new ArrayList<>();
        points.add(point(day + hours(8), 25.0000, 83.0000,
                "STATIONARY", "Home"));
        points.add(point(day + hours(9), 25.0000, 83.0000,
                "STATIONARY", "Home"));
        points.add(point(day + hours(9) + minutes(10), 25.0100, 83.0100,
                "TRAVELLING", ""));
        points.add(point(day + hours(9) + minutes(25), 25.0150, 83.0150,
                "TRAVELLING", ""));
        points.add(point(day + hours(9) + minutes(40), 25.0200, 83.0200,
                "STATIONARY", "Office"));
        points.add(point(day + hours(10) + minutes(40), 25.0200, 83.0200,
                "STATIONARY", "Office"));

        Map<String, List<FamilyJourneyPoint>> source = new HashMap<>();
        source.put(member.uid, points);
        FamilyLocationReport report = FamilyLocationReportAnalyzer.analyze(
                FamilyReportRange.daily(day),
                Collections.singletonList(member),
                source,
                day + hours(20)
        );

        assertEquals(1, report.members.size());
        assertEquals(1, report.activeMemberDays);
        assertTrue(report.totalDistanceMeters > 1_000D);
        assertTrue(report.totalMovingDurationMs >= minutes(15));
        assertTrue(report.totalSafePlaceDurationMs >= hours(2));
        assertFalse(report.familySafePlaces.isEmpty());
        assertEquals("Home", report.members.get(0).mostVisitedPlace);
    }

    @Test
    public void regularPlaceMissingOnLatestPastDayCreatesInsight() {
        long firstDay = dayStart(2026, Calendar.JULY, 1);
        FamilyJourneyRepository.Member member = new FamilyJourneyRepository.Member(
                "member-two",
                "Child",
                "CHILD",
                false,
                true
        );
        List<FamilyJourneyPoint> points = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < 3; dayOffset++) {
            long day = firstDay + dayOffset * hours(24);
            points.add(point(day + hours(8), 25.0, 83.0,
                    "STATIONARY", "School"));
            points.add(point(day + hours(9), 25.0, 83.0,
                    "STATIONARY", "School"));
        }
        long latestDay = firstDay + 3 * hours(24);
        points.add(point(latestDay + hours(8), 25.0, 83.0,
                "STATIONARY", "Home"));
        points.add(point(latestDay + hours(9), 25.0, 83.0,
                "STATIONARY", "Home"));

        Map<String, List<FamilyJourneyPoint>> source = new HashMap<>();
        source.put(member.uid, points);
        FamilyLocationReport report = FamilyLocationReportAnalyzer.analyze(
                FamilyReportRange.custom(firstDay, latestDay),
                Collections.singletonList(member),
                source,
                latestDay + hours(20)
        );

        boolean found = false;
        for (FamilyLocationReport.Insight insight
                : report.members.get(0).insights) {
            if (FamilyLocationReport.Insight.MISSED_VISIT.equals(insight.type)
                    && insight.detail.contains("School")) {
                found = true;
            }
        }
        assertTrue(found);
    }

    private static FamilyJourneyPoint point(
            long capturedAt,
            double latitude,
            double longitude,
            String movement,
            String safePlace
    ) {
        FamilyJourneyPoint point = new FamilyJourneyPoint();
        point.pointId = "p" + capturedAt;
        point.familyId = "family";
        point.uid = "member";
        point.dayKey = FamilyJourneyPolicy.dayKey(capturedAt);
        point.latitude = latitude;
        point.longitude = longitude;
        point.accuracy = 10D;
        point.capturedAt = capturedAt;
        point.recordedAt = capturedAt;
        point.movementType = movement;
        point.safePlaceName = safePlace;
        return point;
    }

    private static long dayStart(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long hours(long value) {
        return value * 60L * 60L * 1000L;
    }

    private static long minutes(long value) {
        return value * 60L * 1000L;
    }
}
