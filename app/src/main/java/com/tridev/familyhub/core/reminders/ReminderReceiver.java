package com.tridev.familyhub.core.reminders;

import com.tridev.familyhub.core.security.AccountDataScope;

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
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.repository.ReminderRepository;
import java.text.DateFormat;
import java.util.Date;

/** Validates the current local task before delivering a reminder or handling an action. */
public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_REMINDER";
    public static final String ACTION_COMPLETE = "com.tridev.familyhub.action.COMPLETE_REMINDER_ALARM";
    public static final String ACTION_SNOOZE = "com.tridev.familyhub.action.SNOOZE_REMINDER";
    public static final String ACTION_DISMISS = "com.tridev.familyhub.action.DISMISS_REMINDER_ALARM";
    public static final String EXTRA_REMINDER_ID = "reminder_id";
    public static final String EXTRA_TITLE = "title", EXTRA_NOTE = "note", EXTRA_REPEAT_TYPE = "repeat_type";
    public static final String EXTRA_SNOOZE_MINUTES = "snooze_minutes", EXTRA_SNOOZE_DELIVERY = "snooze_delivery";
    public static final String EXTRA_PRIORITY = "priority", EXTRA_PRE_ALERT = "pre_alert";
    private static final Object DELIVERY_LOCK = new Object();
    private static final String CHANNEL = "family_hub_reminders";
    private static final String IMPORTANT_CHANNEL = "family_hub_urgent_reminders";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !AccountDataScope.canDeliver(context, intent.getStringExtra(AccountDataScope.EXTRA_UID))) return;
        String action = intent.getAction();
        if (!ACTION_FIRE.equals(action) && !ACTION_COMPLETE.equals(action) && !ACTION_SNOOZE.equals(action) && !ACTION_DISMISS.equals(action)) return;
        long id = intent.getLongExtra(EXTRA_REMINDER_ID, 0);
        if (id <= 0) return;
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean completing = false;
            try {
                if (!AccountDataScope.canDeliver(app,intent.getStringExtra(AccountDataScope.EXTRA_UID))) return;
                Reminder task = FamilyHubDatabase.getInstance(app).reminderDao().getById(id);
                if (!ReminderAlarmPolicy.canDeliver(task, false)) {
                    ReminderScheduler.cancel(app, id); return;
                }
                if (!ACTION_FIRE.equals(action) && intent.hasExtra("due")
                        && intent.getLongExtra("due", -1) != task.reminderAt) {
                    ReminderScheduler.schedule(app, task); return;
                }
                if (!ACTION_FIRE.equals(action)) {
                    if (!task.isEnabled) { ReminderScheduler.cancel(app, id); return; }
                    ReminderAlarmService.stopAlarm(app, id);
                    NotificationManagerCompat.from(app).cancel(ReminderScheduler.notificationId(id));
                    NotificationManagerCompat.from(app).cancel((int)(id & 0x7fffffff));
                    app.sendBroadcast(new Intent(ReminderAlarmService.CLOSED).setPackage(app.getPackageName())
                            .putExtra(EXTRA_REMINDER_ID, id));
                    if (ACTION_DISMISS.equals(action)) return;
                }
                if (ACTION_COMPLETE.equals(action)) {
                    completing = true;
                    task.collaborationStatus = "COMPLETED";
                    task.isEnabled = false; task.completedAt = System.currentTimeMillis();
                    com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                    task.completedByName = user == null || user.getDisplayName() == null ? "" : user.getDisplayName();
                    new ReminderRepository(app).save(task, saved -> {
                        ReminderScheduler.cancel(app, id);
                        ReminderScheduler.rescheduleAll(app, result::finish);
                    });
                    return;
                }
                if (ACTION_SNOOZE.equals(action)) {
                    NotificationManagerCompat.from(app).cancel(ReminderScheduler.notificationId(id));
                    ReminderScheduler.snooze(app, task, Math.max(1, Math.min(1440, intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 10))) * 60000L); return;
                }
                if (!intent.hasExtra("due")) { ReminderScheduler.schedule(app, task); return; }
                synchronized (DELIVERY_LOCK) {
                    if (!task.isEnabled) { ReminderScheduler.cancel(app, id); return; }
                    if (intent.getLongExtra("due", -1) != task.reminderAt) {
                        ReminderScheduler.schedule(app, task); return;
                    }
                    int stage = intent.getIntExtra("stage", 1);
                    long occurrence = intent.getLongExtra("occurrence", task.reminderAt);
                    if (ReminderPreferences.delivered(app, id, task.reminderAt, stage, occurrence)) return;
                    if (stage == 2 && !ReminderPreferences.followUp(app, task)) return;
                    long snooze = ReminderPreferences.snooze(app, id, task.reminderAt);
                    if (stage != 3 && ReminderTiming.deliveryTrigger(snooze, 3, System.currentTimeMillis(),
                            ReminderPreferences.delivered(app, id, task.reminderAt, 3, snooze)) > 0) return;
                    if (stage == 3 && snooze != occurrence) return;
                    // A delayed advance reminder must not replace/cancel the due reminder.
                    if (stage == 0 && occurrence <= System.currentTimeMillis()) {
                        ReminderScheduler.schedule(app, task); return;
                    }
                    int delivery = show(app, task, stage, occurrence);
                    if (delivery == 2) return; // Service confirms only after promotion/fallback succeeds.
                    if (delivery == 1) confirmDelivery(app, ReminderAlarmService.alarmIntent(app, task, stage, occurrence));
                    ReminderScheduler.schedule(app, task);
                }
            } finally { if (!completing) result.finish(); }
        }, "ReminderNotification").start();
    }
    public static String channelId(boolean important) { return important ? IMPORTANT_CHANNEL : CHANNEL; }
    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel normal = new NotificationChannel(CHANNEL,
                context.getString(R.string.reminder_notification_channel_name), NotificationManager.IMPORTANCE_HIGH);
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
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(context.getString(R.string.reminders_title))
                .setContentText(context.getString(R.string.task_reminder_test_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).setAutoCancel(true);
        try { NotificationManagerCompat.from(context).notify(490001, builder.build()); return true; }
        catch (SecurityException ignored) { return false; }
    }
    private int show(Context context, Reminder task, int stage, long occurrence) {
        boolean important = ReminderPreferences.important(context, task);
        if (!canNotify(context, important)) return 0;
        boolean alarmStage = stage == 1 || stage == 3;
        boolean alarmChannelEnabled = alarmStage && ReminderAlarmService.canRing(context);
        // While the phone is being used, post a normal actionable notification.
        // Full-screen eligibility below remains attached so Android handles lock/AOD races.
        if (alarmChannelEnabled && ReminderAlarmService.isLockedOrScreenOff(context)) {
            Intent alarm = ReminderAlarmService.alarmIntent(context, task, stage, occurrence).putExtra("ring", true);
            try {
                ContextCompat.startForegroundService(context, alarm);
                return 2;
            } catch (RuntimeException denied) {
                // The notification below still carries the explicit alarm screen if a service is denied.
            }
        }
        String channelId = channelId(important);
        long id = task.id;
        Intent alarm = ReminderAlarmService.alarmIntent(context, task, stage, occurrence);
        // Notification taps open only this reminder's controls, never the app dashboard.
        PendingIntent open = ReminderAlarmService.screenPendingIntent(context, alarm);
        PendingIntent choose = ReminderAlarmService.snoozePendingIntent(context, alarm);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(stage == 3 ? task.reminderAt : occurrence));
        String detail = context.getString(stage == 0 ? R.string.task_reminder_upcoming
                : stage == 2 ? R.string.task_reminder_followup_notice : R.string.task_reminder_due_notice, time);
        if (!task.note.isEmpty()) detail += "\n" + task.note;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(task.title)
                .setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(alarmStage || important ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL).setAutoCancel(!alarmStage).setContentIntent(open)
                .addAction(0, context.getString(R.string.family_tasks_notification_complete), actionIntent(context, task, ACTION_COMPLETE, 610000))
                .addAction(0, context.getString(R.string.task_alarm_snooze_choose), choose)
                .addAction(0, context.getString(R.string.task_alarm_dismiss), actionIntent(context, task, ACTION_DISMISS, 620000));
        if (alarmChannelEnabled && ReminderAlarmService.fullScreenAllowed(context)
                && ReminderAlarmService.fullScreenChannelReady(context)) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(channelId);
            if (channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH) {
                builder.setFullScreenIntent(open, true);
                manager.cancel(ReminderScheduler.notificationId(id));
            }
        }
        try { NotificationManagerCompat.from(context).notify(ReminderScheduler.notificationId(id), builder.build()); return 1; }
        catch (SecurityException ignored) { return 0; }
    }
    static void confirmDelivery(Context context, Intent alarm) {
        if (!AccountDataScope.canDeliver(context, alarm.getStringExtra(AccountDataScope.EXTRA_UID))) return;
        long id = alarm.getLongExtra(EXTRA_REMINDER_ID, 0), due = alarm.getLongExtra("due", -1);
        int stage = alarm.getIntExtra("stage", 1);
        long occurrence = alarm.getLongExtra("time", due);
        synchronized (DELIVERY_LOCK) {
            ReminderPreferences.markDelivered(context, id, due, stage, occurrence);
            if (stage == 3) {
                ReminderPreferences.clearSnooze(context, id);
                if (due <= System.currentTimeMillis())
                    ReminderPreferences.markDelivered(context, id, due, 1, due);
            }
        }
    }
    private PendingIntent actionIntent(Context context, Reminder task, String action, int base) {
        long id = task.id;
        return PendingIntent.getBroadcast(context, base + (int)(id & 0x0fffffff),
                AccountDataScope.stamp(context, new Intent(context, ReminderReceiver.class)).setAction(action).putExtra(EXTRA_REMINDER_ID, id).putExtra("due", task.reminderAt),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
