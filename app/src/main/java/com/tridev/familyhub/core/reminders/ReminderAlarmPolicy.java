package com.tridev.familyhub.core.reminders;
import com.tridev.familyhub.data.local.entity.Reminder;
/** Reject stale broadcasts after deletion, completion or disabling; allow a deliberate snooze. */
final class ReminderAlarmPolicy {
    static boolean canDeliver(Reminder reminder,boolean snooze){return reminder!=null&&!"COMPLETED".equalsIgnoreCase(reminder.collaborationStatus)&&(reminder.isEnabled||snooze);}
}
