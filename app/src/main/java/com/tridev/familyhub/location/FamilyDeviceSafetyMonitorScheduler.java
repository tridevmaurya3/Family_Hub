package com.tridev.familyhub.location;

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

/** Runs family device-health evaluation immediately and every 15 minutes. */
public final class FamilyDeviceSafetyMonitorScheduler {

    private static final String UNIQUE_PERIODIC =
            "family_device_safety_monitor_periodic";
    private static final String UNIQUE_IMMEDIATE =
            "family_device_safety_monitor_immediate";

    private FamilyDeviceSafetyMonitorScheduler() {
    }

    public static void enable(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                FamilyDeviceSafetyMonitorWorker.class,
                15L,
                TimeUnit.MINUTES
        )
                .setConstraints(connectedConstraint())
                .addTag(UNIQUE_PERIODIC)
                .build();
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
        );
        scheduleNow(appContext);
    }

    public static void scheduleNow(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                FamilyDeviceSafetyMonitorWorker.class
        )
                .setConstraints(connectedConstraint())
                .addTag(UNIQUE_IMMEDIATE)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_IMMEDIATE,
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }

    @NonNull
    private static Constraints connectedConstraint() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
