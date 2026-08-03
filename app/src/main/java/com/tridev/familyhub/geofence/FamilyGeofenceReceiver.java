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
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.feature.main.MainActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FamilyGeofenceReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "safe_place_alerts";
    private static final long DEDUPLICATION_WINDOW_MS = 5L * 60L * 1000L;

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()
                || event.getTriggeringGeofences() == null) {
            return;
        }
        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER
                && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return;
        }
        boolean entered = transition == Geofence.GEOFENCE_TRANSITION_ENTER;
        List<Geofence> geofences = event.getTriggeringGeofences();
        PendingResult pendingResult = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                String transitionType = entered ? "ENTER" : "EXIT";
                for (Geofence geofence : geofences) {
                    String placeId = SafePlaceGeofencePolicy
                            .placeIdFromRequestId(geofence.getRequestId());
                    if (placeId == null) {
                        continue;
                    }
                    SafePlaceAlert alert = new SafePlaceAlert();
                    alert.placeId = placeId;
                    alert.transitionType = transitionType;
                    alert.occurredAt = now;
                    alert.deduplicationBucket =
                            now / DEDUPLICATION_WINDOW_MS;
                    alert.isRead = false;
                    long inserted = FamilyHubDatabase
                            .getInstance(appContext)
                            .safePlaceAlertDao()
                            .insert(alert);
                    if (inserted != -1L) {
                        showNotification(
                                appContext,
                                placeId,
                                transitionType,
                                entered
                        );
                    }
                }
            } catch (RuntimeException ignored) {
                // A failed history write must not crash the receiver process.
            } finally {
                pendingResult.finish();
                executor.shutdown();
            }
        });
    }

    private void showNotification(
            @NonNull Context context,
            @NonNull String placeId,
            @NonNull String transitionType,
            boolean entered
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
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(
                context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            manager.notify(
                    (placeId + transitionType).hashCode(),
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_family)
                            .setContentTitle(context.getString(
                                    entered
                                            ? R.string.safe_place_entered
                                            : R.string.safe_place_exited
                            ))
                            .setContentText(context.getString(
                                    R.string.safe_place_alert_detail
                            ))
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .build()
            );
        } catch (SecurityException ignored) {
            // History remains available when notification permission is off.
        }
    }
}
