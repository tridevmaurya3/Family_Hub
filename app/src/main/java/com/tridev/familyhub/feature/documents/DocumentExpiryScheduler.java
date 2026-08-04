package com.tridev.familyhub.feature.documents;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** Maintains one battery-friendly daily expiry check for the Documents Vault. */
public final class DocumentExpiryScheduler {

    private static final String UNIQUE_PERIODIC_WORK =
            "family_hub_document_expiry_daily";
    private static final String UNIQUE_IMMEDIATE_WORK =
            "family_hub_document_expiry_now";

    private DocumentExpiryScheduler() {
    }

    public static void sync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        DocumentVaultPreferences preferences =
                new DocumentVaultPreferences(appContext);
        WorkManager manager = WorkManager.getInstance(appContext);
        if (!preferences.expiryAlertsEnabled()) {
            manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
            manager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DocumentExpiryWorker.class,
                1L,
                TimeUnit.DAYS
        )
                .setInitialDelay(delayUntilMorning(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(UNIQUE_PERIODIC_WORK)
                .build();

        manager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    public static void runNow(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (!new DocumentVaultPreferences(appContext)
                .expiryAlertsEnabled()) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                DocumentExpiryWorker.class
        ).addTag(UNIQUE_IMMEDIATE_WORK).build();
        WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
        );
    }

    private static long delayUntilMorning() {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, 9);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Math.max(0L, next.getTimeInMillis() - now.getTimeInMillis());
    }
}
