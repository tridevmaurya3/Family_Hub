package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules a durable one-time recovery check after reboot, app update or
 * process recreation. The worker performs only consent-based restoration.
 */
public final class LocationServiceRecoveryScheduler {

    private static final String UNIQUE_WORK =
            "family_live_location_service_recovery";

    private LocationServiceRecoveryScheduler() {
    }

    public static void scheduleAfterSystemEvent(
            @NonNull Context context
    ) {
        schedule(context, 35L);
    }

    public static void scheduleNow(@NonNull Context context) {
        schedule(context, 0L);
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WORK);
    }

    private static void schedule(
            @NonNull Context context,
            long delaySeconds
    ) {
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(
                        LocationServiceRecoveryWorker.class
                )
                        .setInitialDelay(
                                Math.max(0L, delaySeconds),
                                TimeUnit.SECONDS
                        )
                        .addTag(UNIQUE_WORK)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_WORK,
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }
}
