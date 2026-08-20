package com.tridev.familyhub.core.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.Reminder;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schedules local reminder broadcasts and restores them after a device restart. */
public final class ReminderScheduler {

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private ReminderScheduler() {
    }

    public static void schedule(Context context, Reminder reminder) {
        if (!reminder.isEnabled) {
            cancel(context, reminder.id);
            return;
        }

        long triggerAt = nextTriggerTime(reminder);
        if (triggerAt <= 0) {
            cancel(context, reminder.id);
            return;
        }
        scheduleAlarm(context, reminder.id, reminder.title, reminder.note, reminder.repeatType,
                reminder.priority, triggerAt, false);
        long preAlertAt = triggerAt - reminder.preAlertMinutes * 60_000L;
        if (reminder.preAlertMinutes > 0 && preAlertAt > System.currentTimeMillis()) {
            scheduleAlarm(context, reminder.id, reminder.title, reminder.note, reminder.repeatType,
                    reminder.priority, preAlertAt, true);
        }
    }

    public static void cancel(Context context, long reminderId) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = reminderPendingIntent(context, reminderId,
                null, null, null, null, false);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
        PendingIntent preAlert = reminderPendingIntent(context, reminderId,
                null, null, null, null, true);
        alarmManager.cancel(preAlert);
        preAlert.cancel();
    }

    public static void rescheduleRepeatingIfEnabled(Context context, long reminderId, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            Reminder reminder = FamilyHubDatabase.getInstance(appContext)
                    .reminderDao()
                    .getById(reminderId);
            if (reminder != null && reminder.isEnabled
                    && !Reminder.REPEAT_ONCE.equals(reminder.repeatType)) {
                schedule(appContext, reminder);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public static void disableOneTimeIfPresent(Context context, long reminderId, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            Reminder reminder = FamilyHubDatabase.getInstance(appContext)
                    .reminderDao()
                    .getById(reminderId);
            if (reminder != null && Reminder.REPEAT_ONCE.equals(reminder.repeatType)) {
                reminder.isEnabled = false;
                FamilyHubDatabase.getInstance(appContext).reminderDao().update(reminder);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public static void rescheduleAll(Context context, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            List<Reminder> reminders = FamilyHubDatabase.getInstance(appContext)
                    .reminderDao()
                    .getEnabled();
            for (Reminder reminder : reminders) {
                schedule(appContext, reminder);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private static long nextTriggerTime(Reminder reminder) {
        long now = System.currentTimeMillis();
        if (Reminder.REPEAT_ONCE.equals(reminder.repeatType)) {
            return reminder.reminderAt > now ? reminder.reminderAt : -1;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(reminder.reminderAt);
        int field = Calendar.DATE;
        if (Reminder.REPEAT_WEEKLY.equals(reminder.repeatType)) field = Calendar.WEEK_OF_YEAR;
        else if (Reminder.REPEAT_MONTHLY.equals(reminder.repeatType)) field = Calendar.MONTH;
        else if (Reminder.REPEAT_YEARLY.equals(reminder.repeatType)) field = Calendar.YEAR;
        while (calendar.getTimeInMillis() <= now) {
            calendar.add(field, 1);
        }
        return calendar.getTimeInMillis();
    }

    public static void scheduleSnooze(Context context, long reminderId, String title,
                                      String note, String repeatType, int minutes) {
        long triggerAt = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
                .putExtra(ReminderReceiver.EXTRA_TITLE, title)
                .putExtra(ReminderReceiver.EXTRA_NOTE, note)
                .putExtra(ReminderReceiver.EXTRA_REPEAT_TYPE, repeatType)
                .putExtra(ReminderReceiver.EXTRA_SNOOZE_DELIVERY, true);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                ((int) (reminderId & 0x7fffffff)) ^ 0x2f2f2f2f, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } catch (SecurityException ignored) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private static void scheduleAlarm(Context context, long reminderId, String title, String note,
                                      String repeatType, String priority, long triggerAt,
                                      boolean preAlert) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = reminderPendingIntent(context, reminderId, title, note,
                repeatType, priority, preAlert);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException ignored) {
            // Devices without alarm special-access still receive an inexact, battery-friendly reminder.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private static PendingIntent reminderPendingIntent(Context context, long reminderId, String title,
                                                        String note, String repeatType,
                                                        String priority, boolean preAlert) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
                .putExtra(ReminderReceiver.EXTRA_PRE_ALERT, preAlert);
        if (title != null) {
            intent.putExtra(ReminderReceiver.EXTRA_TITLE, title);
        }
        if (note != null) {
            intent.putExtra(ReminderReceiver.EXTRA_NOTE, note);
        }
        if (repeatType != null) {
            intent.putExtra(ReminderReceiver.EXTRA_REPEAT_TYPE, repeatType);
        }
        if (priority != null) intent.putExtra(ReminderReceiver.EXTRA_PRIORITY, priority);
        return PendingIntent.getBroadcast(
                context,
                ((int) (reminderId & 0x7fffffff)) ^ (preAlert ? 0x15151515 : 0),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
