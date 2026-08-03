package com.tridev.familyhub.geofence;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules one latest transition confirmation per Safe Place. */
public final class SafePlaceTransitionConfirmationScheduler {

    public static final String INPUT_PLACE_ID = "safe_place_id";
    public static final String INPUT_ALERT_TYPE = "safe_place_alert_type";
    public static final String INPUT_TRIGGERED_AT = "safe_place_triggered_at";

    private static final String UNIQUE_PREFIX =
            "family_hub_safe_place_transition_";

    private SafePlaceTransitionConfirmationScheduler() {
    }

    public static void schedule(
            @NonNull Context context,
            long placeId,
            @NonNull String alertType,
            long triggeredAt
    ) {
        if (placeId <= 0L
                || (!SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)
                && !SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType))) {
            return;
        }

        long delay = SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)
                ? SafePlaceSmartAlertPolicy.EXIT_CONFIRMATION_DELAY_MS
                : SafePlaceSmartAlertPolicy.ARRIVAL_CONFIRMATION_DELAY_MS;

        Data input = new Data.Builder()
                .putLong(INPUT_PLACE_ID, placeId)
                .putString(INPUT_ALERT_TYPE, alertType)
                .putLong(INPUT_TRIGGERED_AT, triggeredAt)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                SafePlaceTransitionConfirmationWorker.class
        )
                .setInputData(input)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                )
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        uniqueName(placeId),
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }

    public static void cancel(@NonNull Context context, long placeId) {
        if (placeId <= 0L) {
            return;
        }
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(uniqueName(placeId));
    }

    @NonNull
    private static String uniqueName(long placeId) {
        return UNIQUE_PREFIX + placeId;
    }
}
