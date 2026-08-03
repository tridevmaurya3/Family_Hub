package com.tridev.familyhub.feature.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class FamilyAutomationPolicyTest {

    @Test
    public void normalSharingWindow_runsOnlyInsideSelectedTime() {
        FamilyAutomationRule rule = scheduleRule(8 * 60, 18 * 60,
                FamilyAutomationPolicy.ALL_DAYS_MASK);

        assertTrue(FamilyAutomationPolicy.shouldRunSharingWindow(
                rule,
                localTime(Calendar.MONDAY, 9, 0)
        ));
        assertFalse(FamilyAutomationPolicy.shouldRunSharingWindow(
                rule,
                localTime(Calendar.MONDAY, 19, 0)
        ));
    }

    @Test
    public void overnightWindow_usesPreviousSelectedDayAfterMidnight() {
        FamilyAutomationRule rule = scheduleRule(22 * 60, 6 * 60,
                FamilyAutomationPolicy.MONDAY);

        assertTrue(FamilyAutomationPolicy.shouldRunSharingWindow(
                rule,
                localTime(Calendar.MONDAY, 23, 0)
        ));
        assertTrue(FamilyAutomationPolicy.shouldRunSharingWindow(
                rule,
                localTime(Calendar.TUESDAY, 2, 0)
        ));
        assertFalse(FamilyAutomationPolicy.shouldRunSharingWindow(
                rule,
                localTime(Calendar.TUESDAY, 23, 0)
        ));
    }

    @Test
    public void criticalBattery_blocksAutomaticStartUnlessCharging() {
        assertTrue(FamilyAutomationPolicy.shouldPauseAutomaticStart(8, false));
        assertFalse(FamilyAutomationPolicy.shouldPauseAutomaticStart(8, true));
        assertFalse(FamilyAutomationPolicy.shouldPauseAutomaticStart(9, false));
        assertFalse(FamilyAutomationPolicy.shouldPauseAutomaticStart(-1, false));
    }

    @Test
    public void placeRadius_matchesNearPointAndRejectsFarPoint() {
        FamilyAutomationRule rule = placeRule();
        rule.latitude = 25.3176D;
        rule.longitude = 82.9739D;
        rule.radiusMeters = 200D;

        assertTrue(FamilyAutomationPolicy.insidePlace(
                25.3180D,
                82.9742D,
                rule
        ));
        assertFalse(FamilyAutomationPolicy.insidePlace(
                25.3300D,
                82.9900D,
                rule
        ));
    }

    @Test
    public void lateWindow_beginsAfterExpectedTimeAndGrace() {
        FamilyAutomationRule rule = placeRule();
        rule.startMinute = 18 * 60;
        rule.graceMinutes = 30;
        rule.daysMask = FamilyAutomationPolicy.ALL_DAYS_MASK;

        assertFalse(FamilyAutomationPolicy.isLateWindow(
                rule,
                localTime(Calendar.WEDNESDAY, 18, 29)
        ));
        assertTrue(FamilyAutomationPolicy.isLateWindow(
                rule,
                localTime(Calendar.WEDNESDAY, 18, 30)
        ));
    }

    @Test
    public void tripStartsOnMovementAndEndsAfterStationaryConfirmation() {
        assertTrue(FamilyAutomationPolicy.shouldStartTrip(
                false,
                "STATIONARY",
                "TRAVELLING"
        ));
        assertFalse(FamilyAutomationPolicy.shouldStartTrip(
                true,
                "STATIONARY",
                "TRAVELLING"
        ));

        long lastMovingAt = 1_000_000L;
        assertFalse(FamilyAutomationPolicy.shouldEndTrip(
                true,
                lastMovingAt,
                "STATIONARY",
                lastMovingAt + 9L * 60L * 1000L
        ));
        assertTrue(FamilyAutomationPolicy.shouldEndTrip(
                true,
                lastMovingAt,
                "STATIONARY",
                lastMovingAt + 10L * 60L * 1000L
        ));
    }

    @Test
    public void nextBoundary_isFutureForEnabledSchedule() {
        FamilyAutomationRule rule = scheduleRule(8 * 60, 18 * 60,
                FamilyAutomationPolicy.ALL_DAYS_MASK);
        long now = localTime(Calendar.THURSDAY, 7, 30);
        long boundary = FamilyAutomationPolicy.nextBoundaryAfter(rule, now);

        assertTrue(boundary > now);
        assertTrue(boundary - now <= 31L * 60L * 1000L);
    }

    @Test
    public void invalidPlaceRule_isRejected() {
        FamilyAutomationRule rule = placeRule();
        rule.latitude = 0D;
        rule.longitude = 0D;

        assertFalse(FamilyAutomationPolicy.validRule(rule));
        rule.latitude = 25.3D;
        rule.longitude = 82.9D;
        assertTrue(FamilyAutomationPolicy.validRule(rule));
    }

    private static FamilyAutomationRule scheduleRule(
            int startMinute,
            int endMinute,
            int daysMask
    ) {
        FamilyAutomationRule rule = new FamilyAutomationRule();
        rule.ruleId = "schedule-1";
        rule.familyId = "family-1";
        rule.targetUid = "member-12345678";
        rule.targetName = "Papa";
        rule.createdByUid = "member-12345678";
        rule.title = "Workday sharing";
        rule.type = FamilyAutomationRule.TYPE_SCHEDULED_SHARING;
        rule.daysMask = daysMask;
        rule.startMinute = startMinute;
        rule.endMinute = endMinute;
        rule.graceMinutes = 0;
        rule.enabled = true;
        return rule;
    }

    private static FamilyAutomationRule placeRule() {
        FamilyAutomationRule rule = new FamilyAutomationRule();
        rule.ruleId = "routine-1";
        rule.familyId = "family-1";
        rule.targetUid = "member-12345678";
        rule.targetName = "Child";
        rule.createdByUid = "guardian-12345678";
        rule.title = "Reach school";
        rule.type = FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL;
        rule.placeName = "School";
        rule.latitude = 25.3D;
        rule.longitude = 82.9D;
        rule.radiusMeters = 150D;
        rule.daysMask = FamilyAutomationPolicy.WEEKDAYS_MASK;
        rule.startMinute = 8 * 60;
        rule.endMinute = 15 * 60;
        rule.graceMinutes = 30;
        rule.enabled = true;
        return rule;
    }

    private static long localTime(int dayOfWeek, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 2026);
        calendar.set(Calendar.MONTH, Calendar.AUGUST);
        calendar.set(Calendar.DAY_OF_MONTH, 3);
        while (calendar.get(Calendar.DAY_OF_WEEK) != dayOfWeek) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
