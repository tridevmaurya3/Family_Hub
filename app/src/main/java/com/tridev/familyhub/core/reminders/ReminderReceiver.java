package com.tridev.familyhub.core.reminders;

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
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.feature.main.MainActivity;

/** Displays the reminder notification and continues daily schedules. */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_REMINDER";
    public static final String EXTRA_REMINDER_ID = "reminder_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_NOTE = "note";
    public static final String EXTRA_REPEAT_TYPE = "repeat_type";
    private static final String CHANNEL_ID = "family_hub_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        long reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String note = intent.getStringExtra(EXTRA_NOTE);
        String repeatType = intent.getStringExtra(EXTRA_REPEAT_TYPE);

        showNotification(context, reminderId, title, note);
        if (Reminder.REPEAT_DAILY.equals(repeatType)) {
            PendingResult pendingResult = goAsync();
            ReminderScheduler.rescheduleDailyIfEnabled(context, reminderId, pendingResult::finish);
        } else {
            PendingResult pendingResult = goAsync();
            ReminderScheduler.disableOneTimeIfPresent(context, reminderId, pendingResult::finish);
        }
    }

    private void showNotification(Context context, long reminderId, String title, String note) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        createNotificationChannel(context);
        Intent openAppIntent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                (int) (reminderId & 0x7fffffff),
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(title == null || title.trim().isEmpty()
                        ? context.getString(R.string.reminder_notification_fallback_title)
                        : title)
                .setContentText(note == null || note.trim().isEmpty()
                        ? context.getString(R.string.reminder_notification_fallback_note)
                        : note)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        NotificationManagerCompat.from(context).notify((int) (reminderId & 0x7fffffff), builder.build());
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.reminder_notification_channel_description));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
