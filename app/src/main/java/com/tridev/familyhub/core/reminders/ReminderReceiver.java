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
    public static final String ACTION_SNOOZE = "com.tridev.familyhub.action.SNOOZE_REMINDER";
    public static final String EXTRA_REMINDER_ID = "reminder_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_NOTE = "note";
    public static final String EXTRA_REPEAT_TYPE = "repeat_type";
    public static final String EXTRA_SNOOZE_MINUTES = "snooze_minutes";
    public static final String EXTRA_SNOOZE_DELIVERY = "snooze_delivery";
    private static final String CHANNEL_ID = "family_hub_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_SNOOZE.equals(intent.getAction())) {
            ReminderScheduler.scheduleSnooze(context,
                    intent.getLongExtra(EXTRA_REMINDER_ID, 0L),
                    intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_NOTE),
                    intent.getStringExtra(EXTRA_REPEAT_TYPE),
                    intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 10));
            NotificationManagerCompat.from(context).cancel((int)
                    (intent.getLongExtra(EXTRA_REMINDER_ID, 0L) & 0x7fffffff));
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        long reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String note = intent.getStringExtra(EXTRA_NOTE);
        String repeatType = intent.getStringExtra(EXTRA_REPEAT_TYPE);

        showNotification(context, reminderId, title, note, repeatType);
        if (intent.getBooleanExtra(EXTRA_SNOOZE_DELIVERY, false)) return;
        if (!Reminder.REPEAT_ONCE.equals(repeatType)) {
            PendingResult pendingResult = goAsync();
            ReminderScheduler.rescheduleRepeatingIfEnabled(context, reminderId, pendingResult::finish);
        } else {
            PendingResult pendingResult = goAsync();
            ReminderScheduler.disableOneTimeIfPresent(context, reminderId, pendingResult::finish);
        }
    }

    private void showNotification(Context context, long reminderId, String title, String note,
                                  String repeatType) {
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
                .setContentIntent(contentIntent)
                .addAction(0, "Snooze 10 min", snoozeIntent(context, reminderId,
                        title, note, repeatType, 10))
                .addAction(0, "Snooze 1 hour", snoozeIntent(context, reminderId,
                        title, note, repeatType, 60));
        NotificationManagerCompat.from(context).notify((int) (reminderId & 0x7fffffff), builder.build());
    }

    private PendingIntent snoozeIntent(Context context, long reminderId, String title,
                                       String note, String repeatType, int minutes) {
        Intent snooze = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_REPEAT_TYPE, repeatType)
                .putExtra(EXTRA_SNOOZE_MINUTES, minutes);
        return PendingIntent.getBroadcast(context,
                (((int) (reminderId & 0x7fffffff)) ^ minutes), snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
