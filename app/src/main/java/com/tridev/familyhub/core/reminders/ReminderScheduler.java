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
        scheduleAlarm(context, reminder.id, reminder.title, reminder.note, reminder.repeatType, triggerAt);
    }

    public static void cancel(Context context, long reminderId) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = reminderPendingIntent(context, reminderId, null, null, null);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    public static void rescheduleDailyIfEnabled(Context context, long reminderId, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            Reminder reminder = FamilyHubDatabase.getInstance(appContext)
                    .reminderDao()
                    .getById(reminderId);
            if (reminder != null && reminder.isEnabled
                    && Reminder.REPEAT_DAILY.equals(reminder.repeatType)) {
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
        while (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DATE, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static void scheduleAlarm(Context context, long reminderId, String title, String note,
                                      String repeatType, long triggerAt) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = reminderPendingIntent(context, reminderId, title, note, repeatType);
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
                                                        String note, String repeatType) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId);
        if (title != null) {
            intent.putExtra(ReminderReceiver.EXTRA_TITLE, title);
        }
        if (note != null) {
            intent.putExtra(ReminderReceiver.EXTRA_NOTE, note);
        }
        if (repeatType != null) {
            intent.putExtra(ReminderReceiver.EXTRA_REPEAT_TYPE, repeatType);
        }
        return PendingIntent.getBroadcast(
                context,
                (int) (reminderId & 0x7fffffff),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
