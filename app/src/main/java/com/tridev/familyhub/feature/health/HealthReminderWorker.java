package com.tridev.familyhub.feature.health;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

/** Shows a privacy-conscious notification for an upcoming Health item. */
public final class HealthReminderWorker extends Worker {

    public static final String KEY_TITLE = "title";
    public static final String KEY_MEMBER = "member";
    public static final String KEY_TYPE = "type";
    public static final String KEY_RECORD_ID = "record_id";
    private static final String CHANNEL_ID = "family_hub_health_reminders";

    public HealthReminderWorker(@NonNull Context context,
                                @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.success();
        }
        createChannel(context);
        String title = value(KEY_TITLE);
        String member = value(KEY_MEMBER);
        String type = value(KEY_TYPE);
        long recordId = getInputData().getLong(KEY_RECORD_ID, 0L);

        Intent open = new Intent(context, MainActivity.class)
                .putExtra("open_health", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                Math.max(1, (int) (recordId % Integer.MAX_VALUE)), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String message = context.getString(R.string.health_reminder_message,
                member, type, title);
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_health)
                        .setContentTitle(context.getString(
                                R.string.health_reminder_title))
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(message))
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);
        NotificationManagerCompat.from(context).notify(
                ("health_" + recordId).hashCode(), notification.build());
        return Result.success();
    }

    @NonNull private String value(@NonNull String key) {
        String value = getInputData().getString(key);
        return value == null ? "" : value;
    }

    private void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(
                NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.health_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(
                R.string.health_reminder_channel_detail));
        manager.createNotificationChannel(channel);
    }
}
