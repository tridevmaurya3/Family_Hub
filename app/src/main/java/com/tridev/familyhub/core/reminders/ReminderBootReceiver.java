package com.tridev.familyhub.core.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.tridev.familyhub.core.planner.PlannerScheduler;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;

/** Restores enabled reminders after the device has restarted. */
public class ReminderBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                    || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
                PendingResult result = goAsync();
                FamilyTaskScheduler.rescheduleAll(context, result::finish);
            }
            return;
        }
        PendingResult pendingResult = goAsync();
        ReminderScheduler.rescheduleAll(
                context,
                () -> PlannerScheduler.rescheduleAll(context,
                        () -> FamilyTaskScheduler.rescheduleAll(context, pendingResult::finish))
        );
    }
}

