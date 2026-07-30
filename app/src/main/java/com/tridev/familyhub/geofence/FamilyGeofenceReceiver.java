package com.tridev.familyhub.geofence;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

public class FamilyGeofenceReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "safe_place_alerts";

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()
                || event.getTriggeringGeofences() == null) {
            return;
        }
        boolean entered = event.getGeofenceTransition()
                == Geofence.GEOFENCE_TRANSITION_ENTER;
        String placeId = event.getTriggeringGeofences().get(0).getRequestId();
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.safe_place_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(
                context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        manager.notify(placeId.hashCode(), new NotificationCompat.Builder(
                context, CHANNEL_ID
        ).setSmallIcon(R.drawable.ic_family)
                .setContentTitle(context.getString(
                        entered ? R.string.safe_place_entered
                                : R.string.safe_place_exited
                ))
                .setContentText(context.getString(
                        R.string.safe_place_alert_detail
                ))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
