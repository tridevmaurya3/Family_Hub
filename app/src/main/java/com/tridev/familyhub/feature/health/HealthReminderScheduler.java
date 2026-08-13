package com.tridev.familyhub.feature.health;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.tridev.familyhub.data.local.entity.HealthRecord;

import java.util.concurrent.TimeUnit;

/** Schedules one deduplicated alert for an upcoming Health timeline item. */
public final class HealthReminderScheduler {

    private static final long ADVANCE_MILLIS = TimeUnit.HOURS.toMillis(24);

    private HealthReminderScheduler() { }

    public static void sync(@NonNull Context context,
                            @NonNull HealthRecord record) {
        cancel(context, record.id);
        if (!supportsReminder(record.recordType) || record.recordedAt <= 0L) {
            return;
        }
        long delay = record.recordedAt - ADVANCE_MILLIS
                - System.currentTimeMillis();
        if (delay < 0L) return;

        Data input = new Data.Builder()
                .putString(HealthReminderWorker.KEY_TITLE, record.title)
                .putString(HealthReminderWorker.KEY_MEMBER,
                        record.assignedMemberName)
                .putString(HealthReminderWorker.KEY_TYPE, record.recordType)
                .putLong(HealthReminderWorker.KEY_RECORD_ID, record.id)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                HealthReminderWorker.class)
                .setInputData(input)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(workName(record.id),
                        ExistingWorkPolicy.REPLACE, request);
    }

    public static void cancel(@NonNull Context context, long recordId) {
        if (recordId <= 0L) return;
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(workName(recordId));
    }

    private static boolean supportsReminder(@NonNull String type) {
        return HealthRecord.TYPE_MEDICINE.equals(type)
                || HealthRecord.TYPE_APPOINTMENT.equals(type)
                || HealthRecord.TYPE_VACCINATION.equals(type);
    }

    @NonNull private static String workName(long id) {
        return "health_reminder_" + id;
    }
}
