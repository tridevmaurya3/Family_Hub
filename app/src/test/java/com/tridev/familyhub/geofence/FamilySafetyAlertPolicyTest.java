package com.tridev.familyhub.geofence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FamilySafetyAlertPolicyTest {

    @Test
    public void unreadFilter_onlyMatchesUnreadAlerts() {
        assertTrue(FamilySafetyAlertPolicy.matchesFilter(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                false,
                FamilySafetyAlertPolicy.FILTER_UNREAD
        ));
        assertFalse(FamilySafetyAlertPolicy.matchesFilter(
                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                true,
                FamilySafetyAlertPolicy.FILTER_UNREAD
        ));
    }

    @Test
    public void legacyEnterAndExit_matchModernFilters() {
        assertTrue(FamilySafetyAlertPolicy.matchesFilter(
                "ENTER",
                true,
                FamilySafetyAlertPolicy.FILTER_ARRIVED
        ));
        assertTrue(FamilySafetyAlertPolicy.matchesFilter(
                "EXIT",
                true,
                FamilySafetyAlertPolicy.FILTER_LEFT
        ));
    }

    @Test
    public void overnightQuietHours_coverLateNightAndEarlyMorning() {
        assertTrue(FamilySafetyAlertPolicy.isQuietMinute(
                23 * 60,
                22 * 60,
                7 * 60
        ));
        assertTrue(FamilySafetyAlertPolicy.isQuietMinute(
                6 * 60 + 59,
                22 * 60,
                7 * 60
        ));
        assertFalse(FamilySafetyAlertPolicy.isQuietMinute(
                12 * 60,
                22 * 60,
                7 * 60
        ));
    }

    @Test
    public void notificationRequiresMasterAndTypeControls() {
        assertFalse(FamilySafetyAlertPolicy.shouldShowNotification(
                false,
                true,
                false,
                12 * 60,
                22 * 60,
                7 * 60
        ));
        assertFalse(FamilySafetyAlertPolicy.shouldShowNotification(
                true,
                false,
                false,
                12 * 60,
                22 * 60,
                7 * 60
        ));
        assertTrue(FamilySafetyAlertPolicy.shouldShowNotification(
                true,
                true,
                false,
                12 * 60,
                22 * 60,
                7 * 60
        ));
    }

    @Test
    public void quietHoursSilenceNotificationButNotOutsideWindow() {
        assertFalse(FamilySafetyAlertPolicy.shouldShowNotification(
                true,
                true,
                true,
                23 * 60,
                22 * 60,
                7 * 60
        ));
        assertTrue(FamilySafetyAlertPolicy.shouldShowNotification(
                true,
                true,
                true,
                10 * 60,
                22 * 60,
                7 * 60
        ));
    }
}
