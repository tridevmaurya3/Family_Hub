package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Durable one-shot timer for explicit Family Live sharing sessions. */
public final class LocationSharingExpiryScheduler {

    private static final String UNIQUE_WORK = "family_live_sharing_expiry";

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
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WORK);
    }
}
