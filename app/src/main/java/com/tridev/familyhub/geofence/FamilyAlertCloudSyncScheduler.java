package com.tridev.familyhub.geofence;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Durable, network-aware synchronization for Family Safety Alert history. */
public final class FamilyAlertCloudSyncScheduler {

    private static final String PERIODIC = "family_alert_cloud_sync_periodic";
    private static final String IMMEDIATE = "family_alert_cloud_sync_now";

    private FamilyAlertCloudSyncScheduler() {
    }

    public static void enable(@NonNull Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                FamilyAlertCloudSyncWorker.class, 15L, TimeUnit.MINUTES
        ).setConstraints(connected()).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        PERIODIC,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                );
        scheduleNow(context);
    }

    public static void scheduleNow(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                FamilyAlertCloudSyncWorker.class
        ).setConstraints(connected()).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        IMMEDIATE,
                        ExistingWorkPolicy.KEEP,
                        request
                );
    }

    @NonNull
    private static Constraints connected() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
