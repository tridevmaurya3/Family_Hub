package com.tridev.familyhub.core.reminders;

import com.tridev.familyhub.core.tasks.FamilyTaskReminderTiming;
import org.junit.Test;
import java.util.Calendar;
import static org.junit.Assert.*;

public class ReminderTimingTest {
    private static final long MINUTE = 60000L;
    @Test public void allSharedRepeatModesMatchToDo() {
        long due = System.currentTimeMillis() + 60 * MINUTE;
        for (String repeat : new String[]{"ONCE", "DAILY", "WEEKLY", "MONTHLY"}) {
            for (long elapsed : new long[]{-20 * MINUTE, 1, 20 * MINUTE, 31 * MINUTE, 32L * 24 * 60 * MINUTE}) {
                for (boolean follow : new boolean[]{false, true}) {
                    String taskRepeat = "ONCE".equals(repeat) ? "NONE" : repeat;
                    assertEquals(FamilyTaskReminderTiming.occurrence(due, taskRepeat, due + elapsed, follow),
                            ReminderTiming.occurrence(due, repeat, due + elapsed, follow));
                }
            }
        }
    }
    @Test public void advanceDueAndFollowUpMatchToDoForEveryLead() {
        for (int lead : new int[]{0,5,10,15,30,60,1440})
            for (int stage = 0; stage < 4; stage++)
                assertEquals(FamilyTaskReminderTiming.trigger(2000 * MINUTE, lead, stage),
                        ReminderTiming.trigger(2000 * MINUTE, lead, stage));
    }
    @Test public void catchUpAndDuplicateSuppressionMatchToDo() {
        for (int stage = 0; stage < 4; stage++)
            for (long now : new long[]{99 * MINUTE,100 * MINUTE,130 * MINUTE,1541 * MINUTE})
                for (boolean delivered : new boolean[]{false,true})
                    assertEquals(FamilyTaskReminderTiming.deliveryTrigger(100 * MINUTE,stage,now,delivered),
                            ReminderTiming.deliveryTrigger(100 * MINUTE,stage,now,delivered));
    }
    @Test public void existingYearlyRepeatIsPreserved() {
        Calendar c = Calendar.getInstance(); c.clear(); c.set(2026,Calendar.OCTOBER,25,18,0);
        long due = c.getTimeInMillis(); c.add(Calendar.YEAR,1);
        assertEquals(c.getTimeInMillis(), ReminderTiming.occurrence(due,"YEARLY",due+MINUTE,false));
    }
    @Test public void pastChangedDateIsRejectedButUnchangedOverdueEditWorks() {
        assertFalse(ReminderTiming.validReminderDue(90 * MINUTE,80 * MINUTE,true,100 * MINUTE));
        assertTrue(ReminderTiming.validReminderDue(90 * MINUTE,90 * MINUTE,true,100 * MINUTE));
        assertFalse(ReminderTiming.validReminderDue(90 * MINUTE,0,false,100 * MINUTE));
    }
    @Test public void snoozeCatchUpIsBoundedAndDeliveredSnoozeDoesNotRepeat() {
        assertEquals(131 * MINUTE+1000,ReminderTiming.deliveryTrigger(130 * MINUTE,3,131 * MINUTE,false));
        assertEquals(-1,ReminderTiming.deliveryTrigger(130 * MINUTE,3,131 * MINUTE,true));
        assertEquals(-1,ReminderTiming.deliveryTrigger(130 * MINUTE,3,1571 * MINUTE,false));
    }
}
