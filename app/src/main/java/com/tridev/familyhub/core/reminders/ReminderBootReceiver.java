package com.tridev.familyhub.core.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores enabled reminders after the device has restarted. */
public class ReminderBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        PendingResult pendingResult = goAsync();
        ReminderScheduler.rescheduleAll(context, pendingResult::finish);
    }
}
