package com.tridev.familyhub.core.tasks;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local alarm projection for canonical Family Tasks. */
public final class FamilyTaskScheduler {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private FamilyTaskScheduler() { }
    public static synchronized void schedule(@NonNull Context context, @NonNull FamilyTask task) {
        if (!task.reminderEnabled || FamilyTask.STATUS_COMPLETED.equals(task.status)) {
            cancel(context, task.id); return;
        }
        long now = System.currentTimeMillis();
        boolean follow = FamilyTaskReminderPreferences.followUp(context, task);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        boolean exact = manager != null && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms());
        String signature = task.dueAt + ":" + task.repeatType + ":" + task.reminderMinutesBefore + ":" + follow + ":" + exact;
        // Preserve already-due alarms while Android is waiting to deliver them.
        if (FamilyTaskReminderPreferences.updateSchedule(context, task.id, signature)) clearAlarms(context, task.id);
        boolean notificationsReady = FamilyTaskReceiver.canNotify(context, FamilyTaskReminderPreferences.important(context, task));
        boolean dueDelivered = FamilyTaskReminderPreferences.delivered(context, task.id, task.dueAt, 1, task.dueAt);
        long catchUp = FamilyTaskReminderTiming.deliveryTrigger(task.dueAt, 1, now, dueDelivered);
        long due = task.dueAt <= now && catchUp > 0 && notificationsReady ? task.dueAt
                : FamilyTaskReminderTiming.occurrence(task.dueAt, task.repeatType, now, follow);
        long snooze = FamilyTaskReminderPreferences.snooze(context, task.id, task.dueAt);
        long snoozeTrigger = FamilyTaskReminderTiming.deliveryTrigger(snooze, 3, now,
                FamilyTaskReminderPreferences.delivered(context, task.id, task.dueAt, 3, snooze));
        if (due > 0) {
            for (int stage = 0; stage <= 2; stage++) {
                if ((stage == 0 && task.reminderMinutesBefore <= 0) || (stage == 2 && !follow)) continue;
                long intended = FamilyTaskReminderTiming.trigger(due, task.reminderMinutesBefore, stage);
                if (snoozeTrigger > 0 && intended <= snoozeTrigger) continue;
                boolean delivered = FamilyTaskReminderPreferences.delivered(context, task.id, task.dueAt, stage, due);
                long trigger = FamilyTaskReminderTiming.deliveryTrigger(intended, stage, now, delivered);
                if (trigger > 0 && (intended > now || notificationsReady)) set(context, trigger, pendingIntent(context, task, stage, due));
            }
        }
        if (snoozeTrigger > 0 && (snooze > now || notificationsReady)) set(context, snoozeTrigger, pendingIntent(context, task, 3, snooze));
    }
    public static synchronized void cancel(@NonNull Context context, long id) {
        clearAlarms(context, id);
        FamilyTaskReminderPreferences.clearSnooze(context, id);
        FamilyTaskReminderPreferences.clearDelivery(context, id);
        androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(id));
    }
    private static void clearAlarms(Context context, long id) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        // Also cancel the legacy identity so installing the update cannot leave two alarms.
        for (int stage = -1; stage <= 3; stage++) {
            Intent intent = fireIntent(context, id, stage);
            PendingIntent pi = PendingIntent.getBroadcast(context, notificationId(id), intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) { manager.cancel(pi); pi.cancel(); }
        }
    }
    private static void set(Context context, long trigger, PendingIntent pi) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        } catch (SecurityException ignored) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi); }
    }
    public static synchronized void snooze(@NonNull Context context, @NonNull FamilyTask task, long delayMillis) {
        if (!task.reminderEnabled || FamilyTask.STATUS_COMPLETED.equals(task.status)) return;
        long trigger = System.currentTimeMillis() + Math.max(60000L, delayMillis);
        FamilyTaskReminderPreferences.snooze(context, task.id, task.dueAt, trigger);
        schedule(context, task);
    }
    public static void rescheduleAll(@NonNull Context context, @NonNull Runnable done) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try { for (FamilyTask task : FamilyHubDatabase.getInstance(app).familyTaskDao().getReminderEnabled()) schedule(app, task); }
            finally { done.run(); }
        });
    }
    public static int notificationId(long id) { return 500000 + (int)(id & 0x0fffffff); }
    private static Intent fireIntent(Context context, long id, int stage) {
        Intent intent = new Intent(context, FamilyTaskReceiver.class).setAction(FamilyTaskReceiver.ACTION_FIRE)
                .putExtra(FamilyTaskReceiver.EXTRA_ID, id);
        if (stage >= 0) intent.setData(Uri.parse("familyhub://task-reminder/" + id + "/" + stage));
        return intent;
    }
    private static PendingIntent pendingIntent(Context context, FamilyTask task, int stage, long occurrence) {
        Intent intent = fireIntent(context, task.id, stage).putExtra("due", task.dueAt)
                .putExtra("stage", stage).putExtra("occurrence", occurrence);
        return PendingIntent.getBroadcast(context, notificationId(task.id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

