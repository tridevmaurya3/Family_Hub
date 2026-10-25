package com.tridev.familyhub.core.reminders;

import com.tridev.familyhub.core.security.AccountDataScope;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.Reminder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local alarm projection using the same stage and delivery rules as Family To-Do. */
public final class ReminderScheduler {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private ReminderScheduler() { }
    public static synchronized void schedule(@NonNull Context context, @NonNull Reminder task) {
        if (!task.isEnabled || "COMPLETED".equalsIgnoreCase(task.collaborationStatus)) {
            cancel(context, task.id); return;
        }
        long now = System.currentTimeMillis();
        boolean follow = ReminderPreferences.followUp(context, task);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        boolean exact = manager != null && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms());
        String signature = task.reminderAt + ":" + task.repeatType + ":" + task.preAlertMinutes + ":" + follow + ":" + exact;
        // Preserve already-due alarms while Android is waiting to deliver them.
        if (ReminderPreferences.updateSchedule(context, task.id, signature)) {
            clearAlarms(context, task.id);
            ReminderAlarmService.stopAlarm(context, task.id);
            closeControls(context, task.id);
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(task.id));
        }
        boolean notificationsReady = ReminderReceiver.canNotify(context, ReminderPreferences.important(context, task));
        boolean dueDelivered = ReminderPreferences.delivered(context, task.id, task.reminderAt, 1, task.reminderAt);
        long catchUp = ReminderTiming.deliveryTrigger(task.reminderAt, 1, now, dueDelivered);
        long due = task.reminderAt <= now && catchUp > 0 && notificationsReady ? task.reminderAt
                : ReminderTiming.occurrence(task.reminderAt, task.repeatType, now, follow);
        long snooze = ReminderPreferences.snooze(context, task.id, task.reminderAt);
        long snoozeTrigger = ReminderTiming.deliveryTrigger(snooze, 3, now,
                ReminderPreferences.delivered(context, task.id, task.reminderAt, 3, snooze));
        if (due > 0) {
            for (int stage = 0; stage <= 2; stage++) {
                if ((stage == 0 && task.preAlertMinutes <= 0) || (stage == 2 && !follow)) continue;
                long intended = ReminderTiming.trigger(due, task.preAlertMinutes, stage);
                if (snoozeTrigger > 0 && intended <= snoozeTrigger) continue;
                boolean delivered = ReminderPreferences.delivered(context, task.id, task.reminderAt, stage, due);
                long trigger = ReminderTiming.deliveryTrigger(intended, stage, now, delivered);
                if (trigger > 0 && (intended > now || notificationsReady)) set(context, trigger, pendingIntent(context, task, stage, due));
            }
        }
        if (snoozeTrigger > 0 && (snooze > now || notificationsReady)) set(context, snoozeTrigger, pendingIntent(context, task, 3, snooze));
    }
    public static synchronized void cancel(@NonNull Context context, long id) {
        ReminderAlarmService.stopAlarm(context, id);
        closeControls(context, id);
        clearAlarms(context, id);
        ReminderPreferences.clearSnooze(context, id);
        ReminderPreferences.clearDelivery(context, id);
        androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(id));
    }
    private static void closeControls(Context context, long id) {
        context.sendBroadcast(new Intent(ReminderAlarmService.CLOSED).setPackage(context.getPackageName())
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id));
    }
    private static void clearAlarms(Context context, long id) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        for (int code : new int[]{(int)(id & 0x7fffffff), ((int)(id & 0x7fffffff)) ^ 0x15151515,
                ((int)(id & 0x7fffffff)) ^ 0x2f2f2f2f}) {
            Intent legacy = AccountDataScope.stamp(context, new Intent(context, ReminderReceiver.class))
                    .setAction(ReminderReceiver.ACTION_FIRE);
            PendingIntent old = PendingIntent.getBroadcast(context, code, AccountDataScope.isolate(context, legacy),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (old != null) { manager.cancel(old); old.cancel(); }
        }
        androidx.core.app.NotificationManagerCompat.from(context).cancel((int)(id & 0x7fffffff));
        // Also cancel the legacy identity so installing the update cannot leave two alarms.
        for (int stage = -1; stage <= 3; stage++) {
            Intent intent = fireIntent(context, id, stage);
            PendingIntent pi = PendingIntent.getBroadcast(context, notificationId(id), AccountDataScope.isolate(context,intent),
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
    public static synchronized void snooze(@NonNull Context context, @NonNull Reminder task, long delayMillis) {
        if (!task.isEnabled || "COMPLETED".equalsIgnoreCase(task.collaborationStatus)) return;
        long trigger = System.currentTimeMillis() + Math.max(60000L, delayMillis);
        ReminderPreferences.snooze(context, task.id, task.reminderAt, trigger);
        schedule(context, task);
    }
    public static void rescheduleAll(@NonNull Context context, @NonNull Runnable done) {
        Context app = context.getApplicationContext();
        String expectedUid = AccountDataScope.uid();
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(app);
        EXECUTOR.execute(() -> {
            try { if (!expectedUid.equals(AccountDataScope.uid())) return;
                for (Reminder task : database.reminderDao().getEnabled()) {
                    if (!expectedUid.equals(AccountDataScope.uid())) return;
                    schedule(app, task);
                }
            }
            finally { done.run(); }
        });
    }
    public static int notificationId(long id) { return ReminderAlarmService.notificationId(id); }
    private static Intent fireIntent(Context context, long id, int stage) {
        Intent intent = AccountDataScope.stamp(context, new Intent(context, ReminderReceiver.class)).setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id);
        if (stage >= 0) intent.setData(Uri.parse("familyhub://reminder-trigger/" + id + "/" + stage));
        return intent;
    }
    private static PendingIntent pendingIntent(Context context, Reminder task, int stage, long occurrence) {
        Intent intent = fireIntent(context, task.id, stage).putExtra("due", task.reminderAt)
                .putExtra("stage", stage).putExtra("occurrence", occurrence);
        return PendingIntent.getBroadcast(context, notificationId(task.id), AccountDataScope.isolate(context,intent),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
