package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Durable one-shot timer for explicit Family Live sharing sessions. */
public final class LocationSharingExpiryScheduler {

    private static final String UNIQUE_WORK = "family_live_sharing_expiry";
    private static final String UNIQUE_WARNING_WORK =
            "family_live_sharing_expiry_warning";
    private static final long WARNING_BEFORE_EXPIRY_MS =
            15L * 60L * 1000L;

    private LocationSharingExpiryScheduler() {
    }

    public static void schedule(
            @NonNull Context context,
            long expiresAt
    ) {
        if (expiresAt <= 0L) {
            cancel(context);
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                LocationSharingExpiryWorker.class
        ).setInitialDelay(
                Math.max(0L, expiresAt - System.currentTimeMillis()),
                TimeUnit.MILLISECONDS
        ).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_WORK,
                        ExistingWorkPolicy.REPLACE,
                        request
                );
        scheduleWarning(context, expiresAt);
    }

    private static void scheduleWarning(
            @NonNull Context context,
            long expiresAt
    ) {
        LocationRecoveryNotifier.cancelExpiryWarning(context);
        long warningDelay = expiresAt
                - System.currentTimeMillis()
                - WARNING_BEFORE_EXPIRY_MS;
        WorkManager workManager = WorkManager.getInstance(
                context.getApplicationContext()
        );
        if (warningDelay <= 0L) {
            workManager.cancelUniqueWork(UNIQUE_WARNING_WORK);
            return;
        }
        Data input = new Data.Builder()
                .putLong(
                        LocationSharingExpiryWarningWorker.KEY_EXPIRES_AT,
                        expiresAt
                )
                .build();
        OneTimeWorkRequest warning = new OneTimeWorkRequest.Builder(
                LocationSharingExpiryWarningWorker.class
        ).setInputData(input)
                .setInitialDelay(warningDelay, TimeUnit.MILLISECONDS)
                .build();
        workManager.enqueueUniqueWork(
                UNIQUE_WARNING_WORK,
                ExistingWorkPolicy.REPLACE,
                warning
        );
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WORK);
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WARNING_WORK);
        LocationRecoveryNotifier.cancelExpiryWarning(context);
    }
}
