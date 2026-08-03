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

/**
 * Maintains a rolling five-minute diagnostic check plus WorkManager's
 * fifteen-minute periodic safety net.
 */
public final class LocationServiceWatchdogScheduler {

    private static final String UNIQUE_ROLLING_WORK =
            "family_live_service_watchdog_rolling";
    private static final String UNIQUE_PERIODIC_WORK =
            "family_live_service_watchdog_periodic";

    private LocationServiceWatchdogScheduler() {
    }

    public static void enable(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        PeriodicWorkRequest periodic =
                new PeriodicWorkRequest.Builder(
                        LocationServiceWatchdogWorker.class,
                        15L,
                        TimeUnit.MINUTES
                )
                        .setConstraints(connectedConstraint())
                        .addTag(UNIQUE_PERIODIC_WORK)
                        .build();

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
        );

        scheduleInitialCheck(appContext);
    }

    public static void scheduleNext(
            @NonNull Context context,
            long delayMs
    ) {
        OneTimeWorkRequest request = buildOneTimeRequest(delayMs);
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_ROLLING_WORK,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request
                );
    }

    public static void scheduleRecoveryVerification(
            @NonNull Context context
    ) {
        scheduleNext(
                context,
                LocationHeartbeatPolicy.RECOVERY_RECHECK_DELAY_MS
        );
    }

    public static void disable(@NonNull Context context) {
        WorkManager manager = WorkManager.getInstance(
                context.getApplicationContext()
        );
        manager.cancelUniqueWork(UNIQUE_ROLLING_WORK);
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
    }

    private static void scheduleInitialCheck(@NonNull Context context) {
        OneTimeWorkRequest request = buildOneTimeRequest(
                LocationHeartbeatPolicy.REGULAR_CHECK_DELAY_MS
        );
        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        UNIQUE_ROLLING_WORK,
                        ExistingWorkPolicy.KEEP,
                        request
                );
    }

    @NonNull
    private static OneTimeWorkRequest buildOneTimeRequest(long delayMs) {
        return new OneTimeWorkRequest.Builder(
                LocationServiceWatchdogWorker.class
        )
                .setInitialDelay(
                        Math.max(0L, delayMs),
                        TimeUnit.MILLISECONDS
                )
                .setConstraints(connectedConstraint())
                .addTag(UNIQUE_ROLLING_WORK)
                .build();
    }

    @NonNull
    private static Constraints connectedConstraint() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
