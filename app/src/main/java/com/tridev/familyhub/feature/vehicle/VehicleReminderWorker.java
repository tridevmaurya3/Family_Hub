package com.tridev.familyhub.feature.vehicle;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class VehicleReminderWorker extends Worker {
    public static final String KEY_VEHICLE_ID = "vehicle_id";
    public static final String KEY_VEHICLE = "vehicle";
    public static final String KEY_DUE_TYPE = "due_type";
    public static final String KEY_DUE_AT = "due_at";
    private static final String CHANNEL = "family_hub_vehicle_reminders";

    public VehicleReminderWorker(@NonNull Context context,
                                 @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return Result.success();
        createChannel(context);
        long id = getInputData().getLong(KEY_VEHICLE_ID, 0L);
        String vehicle = value(KEY_VEHICLE);
        String dueType = value(KEY_DUE_TYPE);
        String date = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(new Date(getInputData().getLong(KEY_DUE_AT, 0L)));
        String message = context.getString(R.string.vehicle_reminder_message,
                vehicle, dueType, date);
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_VEHICLES)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context,
                (vehicle + dueType).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_vehicle)
                .setContentTitle(context.getString(R.string.vehicle_reminder_title))
                .setContentText(message).setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pending);
        NotificationManagerCompat.from(context).notify(
                ("vehicle_" + id + dueType).hashCode(), notification.build());
        return Result.success();
    }

    @NonNull private String value(@NonNull String key) {
        String value = getInputData().getString(key);
        return value == null ? "" : value;
    }

    private void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.vehicle_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.vehicle_reminder_channel_detail));
        manager.createNotificationChannel(channel);
    }
}
