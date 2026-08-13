package com.tridev.familyhub.geofence;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.feature.familylive.SafePlaceAlertHistoryActivity;

/** Stores and presents only confirmed, de-duplicated Safe Place alerts. */
public final class SafePlaceAlertDispatcher {

    private static final String CHANNEL_ID = "safe_place_alerts";

    private SafePlaceAlertDispatcher() {
    }

    public static boolean dispatch(
            @NonNull Context context,
            @NonNull SafePlace place,
            @NonNull String alertType,
            long occurredAt
    ) {
        if (!SafePlaceGeofencePolicy.isValid(place)
                || !SafePlaceSmartAlertPolicy.isSupportedAlert(alertType)
                || occurredAt <= 0L) {
            return false;
        }

        Context appContext = context.getApplicationContext();
        SafePlaceAlertStateStore stateStore =
                new SafePlaceAlertStateStore(appContext);
        FamilySafetyAlertPreferences preferences =
                new FamilySafetyAlertPreferences(appContext);

        String currentState = stateStore.state(place.id);
        if (SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)
                && SafePlaceAlertStateStore.STATE_INSIDE.equals(currentState)) {
            return false;
        }
        if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)
                && SafePlaceAlertStateStore.STATE_OUTSIDE.equals(currentState)) {
            return false;
        }
        if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(alertType)
                && !SafePlaceAlertStateStore.STATE_INSIDE.equals(currentState)) {
            return false;
        }

        long lastSameAt = stateStore.lastAlertAt(place.id, alertType);
        String opposite = SafePlaceSmartAlertPolicy.oppositeOf(alertType);
        long lastOppositeAt = opposite == null
                ? 0L
                : stateStore.lastAlertAt(place.id, opposite);

        if (!SafePlaceSmartAlertPolicy.shouldDispatch(
                alertType,
                occurredAt,
                lastSameAt,
                lastOppositeAt
        )) {
            return false;
        }

        if (!preferences.isAlertTypeEnabled(alertType)) {
            rememberConfirmedState(stateStore, place.id, alertType);
            return false;
        }

        SafePlaceAlert alert = new SafePlaceAlert();
        alert.placeId = String.valueOf(place.id);
        alert.transitionType = alertType;
        alert.occurredAt = occurredAt;
        alert.deduplicationBucket =
                SafePlaceSmartAlertPolicy.deduplicationBucket(
                        alertType,
                        occurredAt
                );
        alert.isRead = false;

        long inserted;
        try {
            inserted = FamilyHubDatabase.getInstance(appContext)
                    .safePlaceAlertDao()
                    .insert(alert);
        } catch (RuntimeException error) {
            return false;
        }

        stateStore.recordAlert(place.id, alertType, occurredAt);
        if (inserted == -1L) {
            return false;
        }
        FamilyAlertCloudSyncScheduler.scheduleNow(appContext);

        if (preferences.shouldShowNotification(alertType, occurredAt)) {
            showNotification(appContext, place, alertType);
        }
        return true;
    }

    private static void rememberConfirmedState(
            @NonNull SafePlaceAlertStateStore stateStore,
            long placeId,
            @NonNull String alertType
    ) {
        if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)) {
            stateStore.markConfirmedState(placeId, false);
            return;
        }
        stateStore.markConfirmedState(placeId, true);
        if (SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)) {
            stateStore.clearPendingEnter(placeId);
        }
    }

    private static void showNotification(
            @NonNull Context context,
            @NonNull SafePlace place,
            @NonNull String alertType
    ) {
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.safe_place_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(context.getString(
                    R.string.safe_place_smart_alert_channel_description
            ));
            manager.createNotificationChannel(channel);
        }

        PendingIntent openHistory = PendingIntent.getActivity(
                context,
                Math.toIntExact(Math.min(Integer.MAX_VALUE, place.id)),
                new Intent(context, SafePlaceAlertHistoryActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        int titleRes;
        int detailRes;
        if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)) {
            titleRes = R.string.safe_place_smart_left_title;
            detailRes = R.string.safe_place_smart_left_detail;
        } else if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(alertType)) {
            titleRes = R.string.safe_place_smart_dwell_title;
            detailRes = R.string.safe_place_smart_dwell_detail;
        } else {
            titleRes = R.string.safe_place_smart_arrived_title;
            detailRes = R.string.safe_place_smart_arrived_detail;
        }

        try {
            manager.notify(
                    (place.id + ":" + alertType).hashCode(),
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_family)
                            .setContentTitle(context.getString(
                                    titleRes,
                                    safePlaceName(context, place)
                            ))
                            .setContentText(context.getString(
                                    detailRes,
                                    safePlaceName(context, place)
                            ))
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(context.getString(
                                            detailRes,
                                            safePlaceName(context, place)
                                    )))
                            .setContentIntent(openHistory)
                            .setCategory(NotificationCompat.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .build()
            );
        } catch (SecurityException ignored) {
            // Local alert history remains available when notifications are off.
        }
    }

    @NonNull
    private static String safePlaceName(
            @NonNull Context context,
            @Nullable SafePlace place
    ) {
        if (place == null || place.name.trim().isEmpty()) {
            return context.getString(R.string.safe_place_unknown_name);
        }
        return place.name.trim();
    }
}
