package com.tridev.familyhub.backup;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tridev.familyhub.core.planner.PlannerScheduler;
import com.tridev.familyhub.core.reminders.ReminderScheduler;
import com.tridev.familyhub.feature.documents.DocumentExpiryScheduler;
import com.tridev.familyhub.geofence.SafePlaceGeofenceSyncScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Rebuilds OS-managed alarms, document alerts and geofences after restore. */
public final class BackupPostRestoreCoordinator {

    private BackupPostRestoreCoordinator() {
    }

    public static void rebuild(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(2);
        ReminderScheduler.rescheduleAll(appContext, latch::countDown);
        PlannerScheduler.rescheduleAll(appContext, latch::countDown);
        SafePlaceGeofenceSyncScheduler.scheduleNow(appContext);
        DocumentExpiryScheduler.sync(appContext);
        DocumentExpiryScheduler.runNow(appContext);
        try {
            latch.await(8L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
