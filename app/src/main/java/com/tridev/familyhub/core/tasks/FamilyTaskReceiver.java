package com.tridev.familyhub.core.tasks;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.tasks.FamilyTaskSubtasks;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import java.text.DateFormat;
import java.util.Date;

/** Validates the current local task before delivering a reminder or handling an action. */
public final class FamilyTaskReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_FAMILY_TASK";
    private static final String ACTION_COMPLETE = "com.tridev.familyhub.action.COMPLETE_FAMILY_TASK";
    private static final String ACTION_SNOOZE = "com.tridev.familyhub.action.SNOOZE_FAMILY_TASK";
    static final String EXTRA_ID = "family_task_id";
    private static final Object DELIVERY_LOCK = new Object();
    private static final String CHANNEL = "family_hub_tasks";
    private static final String IMPORTANT_CHANNEL = "family_hub_tasks_important";
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_FIRE.equals(action) && !ACTION_COMPLETE.equals(action) && !ACTION_SNOOZE.equals(action)) return;
        long id = intent.getLongExtra(EXTRA_ID, 0);
        if (id <= 0) return;
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean completing = false;
            try {
                FamilyTask task = FamilyHubDatabase.getInstance(app).familyTaskDao().getById(id);
                if (task == null || FamilyTask.STATUS_COMPLETED.equals(task.status)) {
                    FamilyTaskScheduler.cancel(app, id); return;
                }
                if (ACTION_COMPLETE.equals(action)) {
                    completing = true;
                    new FamilyTaskRepository(app).setCompleted(task, true, () -> {
                        FamilyTaskScheduler.cancel(app, id);
                        FamilyTaskScheduler.rescheduleAll(app, result::finish);
                    });
                    return;
                }
                if (ACTION_SNOOZE.equals(action)) {
                    NotificationManagerCompat.from(app).cancel(FamilyTaskScheduler.notificationId(id));
                    FamilyTaskScheduler.snooze(app, task, 10L * 60000L); return;
                }
                synchronized (DELIVERY_LOCK) {
                    if (!task.reminderEnabled) { FamilyTaskScheduler.cancel(app, id); return; }
                    if (intent.getLongExtra("due", -1) != task.dueAt) {
                        FamilyTaskScheduler.schedule(app, task); return;
                    }
                    int stage = intent.getIntExtra("stage", 1);
                    long occurrence = intent.getLongExtra("occurrence", task.dueAt);
                    if (FamilyTaskReminderPreferences.delivered(app, id, task.dueAt, stage, occurrence)) return;
                    if (stage == 2 && !FamilyTaskReminderPreferences.followUp(app, task)) return;
                    long snooze = FamilyTaskReminderPreferences.snooze(app, id, task.dueAt);
                    if (stage != 3 && FamilyTaskReminderTiming.deliveryTrigger(snooze, 3, System.currentTimeMillis(),
                            FamilyTaskReminderPreferences.delivered(app, id, task.dueAt, 3, snooze)) > 0) return;
                    if (stage == 3 && snooze != occurrence) return;
                    // A delayed advance reminder must not replace/cancel the due reminder.
                    if (stage == 0 && occurrence <= System.currentTimeMillis()) {
                        FamilyTaskScheduler.schedule(app, task); return;
                    }
                    if (show(app, task, stage, occurrence)) {
                        FamilyTaskReminderPreferences.markDelivered(app, id, task.dueAt, stage, occurrence);
                        if (stage == 3) {
                            FamilyTaskReminderPreferences.clearSnooze(app, id);
                            if (task.dueAt <= System.currentTimeMillis())
                                FamilyTaskReminderPreferences.markDelivered(app, id, task.dueAt, 1, task.dueAt);
                        }
                    }
                    FamilyTaskScheduler.schedule(app, task);
                }
            } finally { if (!completing) result.finish(); }
        }, "FamilyTaskNotification").start();
    }
    public static String channelId(boolean important) { return important ? IMPORTANT_CHANNEL : CHANNEL; }
    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel normal = new NotificationChannel(CHANNEL,
                context.getString(R.string.family_tasks_notification_channel), NotificationManager.IMPORTANCE_HIGH);
        normal.enableVibration(true);
        manager.createNotificationChannel(normal);
        NotificationChannel important = new NotificationChannel(IMPORTANT_CHANNEL,
                context.getString(R.string.task_reminder_important), NotificationManager.IMPORTANCE_HIGH);
        important.enableVibration(true);
        important.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        manager.createNotificationChannel(important);
    }
    public static boolean canNotify(Context context, boolean important) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(channelId(important));
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    public static boolean testNotification(Context context, boolean important) {
        if (!canNotify(context, important)) return false;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId(important))
                .setSmallIcon(R.drawable.ic_family_task).setContentTitle(context.getString(R.string.family_tasks_title))
                .setContentText(context.getString(R.string.task_reminder_test_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).setAutoCancel(true);
        try { NotificationManagerCompat.from(context).notify(490001, builder.build()); return true; }
        catch (SecurityException ignored) { return false; }
    }
    private boolean show(Context context, FamilyTask task, int stage, long occurrence) {
        boolean important = FamilyTaskReminderPreferences.important(context, task);
        if (!canNotify(context, important)) return false;
        String channelId = channelId(important);
        long id = task.id;
        PendingIntent open = PendingIntent.getActivity(context, FamilyTaskScheduler.notificationId(id),
                new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TASKS)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        FamilyTaskSubtasks.Content content = FamilyTaskSubtasks.decode(task.notes);
        int pending = 0;
        for (Boolean checked : content.completed) if (!Boolean.TRUE.equals(checked)) pending++;
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(stage == 3 ? task.dueAt : occurrence));
        String detail = context.getString(stage == 0 ? R.string.task_reminder_upcoming
                : stage == 2 ? R.string.task_reminder_followup_notice : R.string.task_reminder_due_notice, time);
        if (!content.items.isEmpty()) detail += " · " + context.getString(R.string.task_reminder_subtasks_remaining, pending);
        if (!content.notes.isEmpty()) detail += "\n" + content.notes;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_family_task).setContentTitle(task.title)
                .setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(important ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL).setAutoCancel(true).setContentIntent(open)
                .addAction(0, context.getString(R.string.family_tasks_notification_complete), actionIntent(context, id, ACTION_COMPLETE, 610000))
                .addAction(0, context.getString(R.string.family_tasks_notification_snooze), actionIntent(context, id, ACTION_SNOOZE, 620000))
                .addAction(0, context.getString(R.string.task_reminder_open), open);
        try { NotificationManagerCompat.from(context).notify(FamilyTaskScheduler.notificationId(id), builder.build()); return true; }
        catch (SecurityException ignored) { return false; }
    }
    private PendingIntent actionIntent(Context context, long id, String action, int base) {
        return PendingIntent.getBroadcast(context, base + (int)(id & 0x0fffffff),
                new Intent(context, FamilyTaskReceiver.class).setAction(action).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

