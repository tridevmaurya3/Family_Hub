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
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;

/** Delivers a Family To-Do reminder without opening another app. */
public final class FamilyTaskReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_FAMILY_TASK";
    private static final String ACTION_COMPLETE = "com.tridev.familyhub.action.COMPLETE_FAMILY_TASK";
    private static final String ACTION_SNOOZE = "com.tridev.familyhub.action.SNOOZE_FAMILY_TASK";
    static final String EXTRA_ID = "family_task_id";
    static final String EXTRA_TITLE = "family_task_title";
    static final String EXTRA_NOTES = "family_task_notes";
    private static final String CHANNEL = "family_hub_tasks";
    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_COMPLETE.equals(intent.getAction())
                || ACTION_SNOOZE.equals(intent.getAction())) {
            handleQuickAction(context, intent);
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.family_tasks_notification_channel), NotificationManager.IMPORTANCE_HIGH));
        long id = intent.getLongExtra(EXTRA_ID, 0);
        PendingIntent open = PendingIntent.getActivity(context, 500000 + (int)(id & 0x0fffffff), new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TASKS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String notes = intent.getStringExtra(EXTRA_NOTES);
        PendingIntent complete = actionIntent(context, id, ACTION_COMPLETE, 610000);
        PendingIntent snooze = actionIntent(context, id, ACTION_SNOOZE, 620000);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_family_task).setContentTitle(title == null || title.isEmpty() ? context.getString(R.string.family_tasks_title) : title).setContentText(notes == null || notes.isEmpty() ? context.getString(R.string.family_tasks_notification_detail) : notes).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open)
                .addAction(0, context.getString(R.string.family_tasks_notification_complete), complete)
                .addAction(0, context.getString(R.string.family_tasks_notification_snooze), snooze);
        NotificationManagerCompat.from(context).notify(500000 + (int)(id & 0x0fffffff), builder.build());
    }

    private PendingIntent actionIntent(Context context, long id, String action,
                                       int baseCode) {
        Intent intent = new Intent(context, FamilyTaskReceiver.class)
                .setAction(action).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context,
                baseCode + (int) (id & 0x0fffffff), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void handleQuickAction(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_ID, 0L);
        if (id <= 0L) return;
        NotificationManagerCompat.from(context).cancel(
                500000 + (int) (id & 0x0fffffff));
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            FamilyTask task = FamilyHubDatabase.getInstance(app)
                    .familyTaskDao().getById(id);
            if (task == null) { result.finish(); return; }
            if (ACTION_SNOOZE.equals(intent.getAction())) {
                FamilyTaskScheduler.snooze(app, task, 10L * 60L * 1000L);
                result.finish();
            } else {
                new FamilyTaskRepository(app).setCompleted(
                        task, true, () -> FamilyTaskScheduler.rescheduleAll(
                                app, result::finish));
            }
        }, "FamilyTaskNotificationAction").start();
    }
}
