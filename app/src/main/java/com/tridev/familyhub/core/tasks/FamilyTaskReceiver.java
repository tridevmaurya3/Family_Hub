package com.tridev.familyhub.core.tasks;

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

/** Delivers a Family To-Do reminder without opening another app. */
public final class FamilyTaskReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_FAMILY_TASK";
    static final String EXTRA_ID = "family_task_id";
    static final String EXTRA_TITLE = "family_task_title";
    static final String EXTRA_NOTES = "family_task_notes";
    private static final String CHANNEL = "family_hub_tasks";
    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_FIRE.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.family_tasks_notification_channel), NotificationManager.IMPORTANCE_HIGH));
        long id = intent.getLongExtra(EXTRA_ID, 0);
        PendingIntent open = PendingIntent.getActivity(context, 500000 + (int)(id & 0x0fffffff), new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TASKS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String notes = intent.getStringExtra(EXTRA_NOTES);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_family_task).setContentTitle(title == null || title.isEmpty() ? context.getString(R.string.family_tasks_title) : title).setContentText(notes == null || notes.isEmpty() ? context.getString(R.string.family_tasks_notification_detail) : notes).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open);
        NotificationManagerCompat.from(context).notify(500000 + (int)(id & 0x0fffffff), builder.build());
    }
}
