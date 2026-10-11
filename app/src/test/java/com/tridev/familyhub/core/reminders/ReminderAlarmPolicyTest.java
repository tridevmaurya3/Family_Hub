package com.tridev.familyhub.core.reminders;
import com.tridev.familyhub.data.local.entity.Reminder;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReminderAlarmPolicyTest {
    @Test public void staleDeletedOrDisabledAlarmDoesNotRing(){assertFalse(ReminderAlarmPolicy.canDeliver(null,false));Reminder r=new Reminder();r.isEnabled=false;assertFalse(ReminderAlarmPolicy.canDeliver(r,false));}
    @Test public void enabledPendingReminderRings(){Reminder r=new Reminder();r.isEnabled=true;assertTrue(ReminderAlarmPolicy.canDeliver(r,false));}
    @Test public void completedReminderNeverRingsEvenFromSnooze(){Reminder r=new Reminder();r.isEnabled=true;r.collaborationStatus="COMPLETED";assertFalse(ReminderAlarmPolicy.canDeliver(r,false));assertFalse(ReminderAlarmPolicy.canDeliver(r,true));}
    @Test public void explicitSnoozeOfDeliveredOneTimeReminderCanRing(){Reminder r=new Reminder();r.isEnabled=false;r.repeatType=Reminder.REPEAT_ONCE;assertTrue(ReminderAlarmPolicy.canDeliver(r,true));}
}
