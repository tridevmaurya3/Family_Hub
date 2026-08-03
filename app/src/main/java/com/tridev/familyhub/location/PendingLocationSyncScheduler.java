package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules durable automatic sync for encrypted Family Live updates.
 */
public final class PendingLocationSyncScheduler {

    private static final String UNIQUE_ONE_TIME_WORK =
            "family_live_pending_location_sync_once";
    private static final String UNIQUE_PERIODIC_WORK =
            "family_live_pending_location_sync_periodic";
    private static final String UNIQUE_CLEANUP_WORK =
            "family_live_pending_location_cleanup";

    private PendingLocationSyncScheduler() {
    }

    /** Runs as soon as a network is available. */
    public static void schedule(@NonNull Context context) {
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(
                        PendingLocationSyncWorker.class
                )
                        .setConstraints(networkConstraints())
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                30L,
                                TimeUnit.SECONDS
                        )
                        .addTag(UNIQUE_ONE_TIME_WORK)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_ONE_TIME_WORK,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request
                );
    }

    /**
     * Keeps a low-frequency safety net alive while sharing is enabled. Android
     * may stop the app process, but WorkManager will retry the newest point
     * when connectivity and system scheduling allow it.
     */
    public static void enablePeriodicSync(@NonNull Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(
                        PendingLocationSyncWorker.class,
                        15L,
                        TimeUnit.MINUTES
                )
                        .setConstraints(networkConstraints())
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                30L,
                                TimeUnit.SECONDS
                        )
                        .addTag(UNIQUE_PERIODIC_WORK)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        UNIQUE_PERIODIC_WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                );
    }

    /**
     * Removes the encrypted pending point after the user explicitly stops
     * sharing. Cleanup requires no network and cannot upload anything.
     */
    public static void disableAndClear(@NonNull Context context) {
        WorkManager manager = WorkManager.getInstance(
                context.getApplicationContext()
        );
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
        manager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK);

        OneTimeWorkRequest cleanup =
                new OneTimeWorkRequest.Builder(
                        PendingLocationSyncWorker.class
                )
                        .addTag(UNIQUE_CLEANUP_WORK)
                        .build();
        manager.enqueueUniqueWork(
                UNIQUE_CLEANUP_WORK,
                ExistingWorkPolicy.REPLACE,
                cleanup
        );
    }

    @NonNull
    private static Constraints networkConstraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
