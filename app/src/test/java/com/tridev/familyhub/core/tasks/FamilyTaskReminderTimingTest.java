package com.tridev.familyhub.core.tasks;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.assertEquals;

public class FamilyTaskReminderTimingTest {
    private static final long MINUTE = 60000L;
    @Test public void missedAdvanceNoticeKeepsTodaysDueNotice() {
        long due = 120 * MINUTE;
        long occurrence = FamilyTaskReminderTiming.occurrence(due, "NONE", 115 * MINUTE, false);
        assertEquals(due, occurrence);
        assertEquals(105 * MINUTE, FamilyTaskReminderTiming.trigger(occurrence, 15, 0));
        assertEquals(due, FamilyTaskReminderTiming.trigger(occurrence, 15, 1));
    }
    @Test public void recurringAdvancePassedDoesNotSkipTodaysDue() {
        long due = 120 * MINUTE;
        assertEquals(due, FamilyTaskReminderTiming.occurrence(due, "DAILY", 115 * MINUTE, false));
    }
    @Test public void followUpStillWorksAfterDueTime() {
        long due = 120 * MINUTE;
        assertEquals(due, FamilyTaskReminderTiming.occurrence(due, "NONE", 130 * MINUTE, true));
        assertEquals(150 * MINUTE, FamilyTaskReminderTiming.trigger(due, 15, 2));
    }
    @Test public void completedFollowUpWindowDoesNotResurrectOneOff() {
        assertEquals(-1, FamilyTaskReminderTiming.occurrence(120 * MINUTE, "NONE", 150 * MINUTE, true));
    }
    @Test public void noFollowUpMeansNoLateOneOffAlarm() {
        assertEquals(-1, FamilyTaskReminderTiming.occurrence(120 * MINUTE, "NONE", 130 * MINUTE, false));
    }
    @Test public void invalidDateDoesNotCreateAlarm() {
        assertEquals(-1, FamilyTaskReminderTiming.occurrence(0, "DAILY", 120 * MINUTE, true));
    }
    @Test public void repeatUsesCalendarAcrossDaylightSaving() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Calendar c = Calendar.getInstance();
            c.clear(); c.set(2026, Calendar.MARCH, 7, 18, 0);
            long due = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
            assertEquals(c.getTimeInMillis(), FamilyTaskReminderTiming.occurrence(due, "DAILY", due + MINUTE, false));
        } finally { TimeZone.setDefault(original); }
    }
    @Test public void atTimeDoesNotNeedAdvanceSlot() {
        assertEquals(120 * MINUTE, FamilyTaskReminderTiming.trigger(120 * MINUTE, 0, 1));
    }
    @Test public void taskAddedAtCurrentMinuteStillGetsDueNotice() {
        assertEquals(121 * MINUTE + 1000, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 121 * MINUTE, false));
    }
    @Test public void notificationAccessRestoredRecoversRecentMissedDue() {
        assertEquals(150 * MINUTE + 1000, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 150 * MINUTE, false));
    }
    @Test public void deliveredDueNoticeDoesNotReappearOnFocusOrRestart() {
        assertEquals(-1, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 121 * MINUTE, true));
    }
    @Test public void lateAdvanceNoticeIsSkippedInsteadOfReplacingDueNotice() {
        assertEquals(-1, FamilyTaskReminderTiming.deliveryTrigger(105 * MINUTE, 0, 121 * MINUTE, false));
        assertEquals(121 * MINUTE + 1000, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 121 * MINUTE, false));
    }
    @Test public void oldMissedTasksDoNotCreateNotificationFlood() {
        assertEquals(-1, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 1561 * MINUTE, false));
    }
    @Test public void snoozeCanRecoverAfterRestart() {
        assertEquals(131 * MINUTE + 1000, FamilyTaskReminderTiming.deliveryTrigger(130 * MINUTE, 3, 131 * MINUTE, false));
    }
    @Test public void futureDueTimingStaysUnchanged() {
        assertEquals(120 * MINUTE, FamilyTaskReminderTiming.deliveryTrigger(120 * MINUTE, 1, 110 * MINUTE, false));
    }

}

