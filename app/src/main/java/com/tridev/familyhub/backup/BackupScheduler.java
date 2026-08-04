package com.tridev.familyhub.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Maintains exactly one automatic encrypted backup schedule. */
public final class BackupScheduler {

    private static final String UNIQUE_WORK =
            "family_hub_encrypted_automatic_backup";

    private BackupScheduler() {
    }

    public static void sync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        BackupPreferences preferences = new BackupPreferences(appContext);
        WorkManager workManager = WorkManager.getInstance(appContext);

        if (!preferences.isReadyForAutomaticBackup()) {
            workManager.cancelUniqueWork(UNIQUE_WORK);
            return;
        }

        long intervalDays = BackupPreferences.FREQUENCY_DAILY.equals(
                preferences.frequency()
        ) ? 1L : 7L;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(preferences.wifiOnly()
                        ? NetworkType.UNMETERED
                        : NetworkType.CONNECTED)
                .setRequiresCharging(preferences.chargingOnly())
                .setRequiresStorageNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                EncryptedBackupWorker.class,
                intervalDays,
                TimeUnit.DAYS
        )
                .setConstraints(constraints)
                .addTag(UNIQUE_WORK)
                .build();

        workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    public static void disable(@NonNull Context context) {
        BackupPreferences preferences = new BackupPreferences(context);
        preferences.setAutoBackupEnabled(false);
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WORK);
    }
}
