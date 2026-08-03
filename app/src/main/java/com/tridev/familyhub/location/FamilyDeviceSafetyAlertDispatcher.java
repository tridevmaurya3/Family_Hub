package com.tridev.familyhub.location;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.feature.familylive.SafePlaceAlertHistoryActivity;
import com.tridev.familyhub.geofence.FamilySafetyAlertPreferences;

/** Saves device-health alerts in the shared Safety Alert Centre. */
public final class FamilyDeviceSafetyAlertDispatcher {

    private static final String CHANNEL_ID = "family_device_safety_alerts";

    private FamilyDeviceSafetyAlertDispatcher() {
    }

    public static boolean dispatch(
            @NonNull Context context,
            @NonNull String memberUid,
            @NonNull String memberName,
            @NonNull String alertType,
            long occurredAt
    ) {
        if (memberUid.trim().isEmpty()
                || !FamilyDeviceSafetyAlertPolicy.isSupported(alertType)
                || occurredAt <= 0L) {
            return false;
        }

        Context appContext = context.getApplicationContext();
        FamilySafetyAlertPreferences preferences =
                new FamilySafetyAlertPreferences(appContext);
        if (!preferences.isAlertTypeEnabled(alertType)) {
            return false;
        }

        FamilyDeviceSafetyAlertStateStore stateStore =
                new FamilyDeviceSafetyAlertStateStore(appContext);
        stateStore.rememberMemberName(memberUid, memberName);

        SafePlaceAlert alert = new SafePlaceAlert();
        alert.placeId = FamilyDeviceSafetyAlertPolicy.memberPlaceId(memberUid);
        alert.transitionType = alertType;
        alert.occurredAt = occurredAt;
        alert.deduplicationBucket =
                FamilyDeviceSafetyAlertPolicy.deduplicationBucket(
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

        if (inserted == -1L) {
            return false;
        }

        if (preferences.shouldShowNotification(alertType, occurredAt)) {
            showNotification(
                    appContext,
                    memberUid,
                    safeMemberName(appContext, memberName),
                    alertType
            );
        }
        return true;
    }

    private static void showNotification(
            @NonNull Context context,
            @NonNull String memberUid,
            @NonNull String memberName,
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
                    context.getString(
                            R.string.family_device_alert_channel_name
                    ),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(context.getString(
                    R.string.family_device_alert_channel_description
            ));
            manager.createNotificationChannel(channel);
        }

        int titleRes;
        int detailRes;
        if (FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY.equals(
                alertType
        )) {
            titleRes = R.string.family_device_alert_low_battery_title;
            detailRes = R.string.family_device_alert_low_battery_detail;
        } else if (FamilyDeviceSafetyAlertPolicy.ALERT_DEVICE_OFFLINE.equals(
                alertType
        )) {
            titleRes = R.string.family_device_alert_offline_title;
            detailRes = R.string.family_device_alert_offline_detail;
        } else {
            titleRes = R.string.family_device_alert_no_update_title;
            detailRes = R.string.family_device_alert_no_update_detail;
        }

        PendingIntent openCentre = PendingIntent.getActivity(
                context,
                (memberUid + alertType).hashCode(),
                new Intent(context, SafePlaceAlertHistoryActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
        String detail = context.getString(detailRes, memberName);

        try {
            manager.notify(
                    (memberUid + ":" + alertType).hashCode(),
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_family)
                            .setContentTitle(context.getString(
                                    titleRes,
                                    memberName
                            ))
                            .setContentText(detail)
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(detail))
                            .setContentIntent(openCentre)
                            .setCategory(NotificationCompat.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .build()
            );
        } catch (SecurityException ignored) {
            // History remains available when notification permission is off.
        }
    }

    @NonNull
    private static String safeMemberName(
            @NonNull Context context,
            @NonNull String memberName
    ) {
        String trimmed = memberName.trim();
        return trimmed.isEmpty()
                ? context.getString(R.string.family_account_member_fallback)
                : trimmed;
    }
}
