package com.tridev.familyhub.core.planner;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

/** Shows Planner notifications and continues repeating schedules. */
public class PlannerReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE =
            "com.tridev.familyhub.action.FIRE_PLANNER";
    public static final String EXTRA_ITEM_ID = "planner_item_id";
    public static final String EXTRA_TITLE = "planner_title";
    public static final String EXTRA_NOTES = "planner_notes";
    private static final String CHANNEL_ID = "family_hub_planner";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0L);
        showNotification(
                context,
                itemId,
                intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_NOTES)
        );
        PendingResult result = goAsync();
        PlannerScheduler.handleFired(context, itemId, result::finish);
    }

    private void showNotification(
            Context context,
            long itemId,
            String title,
            String notes
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        createChannel(context);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                (int) (itemId & 0x7fffffff),
                new Intent(context, MainActivity.class).setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_planner)
                        .setContentTitle(
                                title == null || title.trim().isEmpty()
                                        ? context.getString(
                                        R.string.planner_notification_title
                                ) : title
                        )
                        .setContentText(
                                notes == null || notes.trim().isEmpty()
                                        ? context.getString(
                                        R.string.planner_notification_detail
                                ) : notes
                        )
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent);
        NotificationManagerCompat.from(context).notify(
                100000 + (int) (itemId & 0x0fffffff),
                builder.build()
        );
    }

    private void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.planner_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(
                context.getString(
                        R.string.planner_notification_channel_detail
                )
        );
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
