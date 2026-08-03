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
        Context appContext = context.getApplicationContext();
        WorkManager manager = WorkManager.getInstance(appContext);

        // A restarted sharing session must cancel a cleanup that has not begun.
        manager.cancelUniqueWork(UNIQUE_CLEANUP_WORK);

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

        // Only the newest point is retained, so queued duplicate workers add no
        // value. REPLACE guarantees one current sync attempt.
        manager.enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                request
        );
    }

    /**
     * Keeps a low-frequency safety net alive while sharing is enabled. Android
     * may stop the app process, but WorkManager will retry the newest point
     * when connectivity and system scheduling allow it.
     */
    public static void enablePeriodicSync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        WorkManager manager = WorkManager.getInstance(appContext);
        manager.cancelUniqueWork(UNIQUE_CLEANUP_WORK);

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

        manager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
    }

    /**
     * Cancels every upload path and schedules a dedicated non-upload cleanup.
     */
    public static void disableAndClear(@NonNull Context context) {
        WorkManager manager = WorkManager.getInstance(
                context.getApplicationContext()
        );
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
        manager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK);
        manager.cancelUniqueWork(UNIQUE_CLEANUP_WORK);

        OneTimeWorkRequest cleanup =
                new OneTimeWorkRequest.Builder(
                        PendingLocationCleanupWorker.class
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
