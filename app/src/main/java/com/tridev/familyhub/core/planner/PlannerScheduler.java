package com.tridev.familyhub.core.planner;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.PlannerItem;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schedules and restores local Planner event/task notifications. */
public final class PlannerScheduler {

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private PlannerScheduler() {
    }

    public static void schedule(
            @NonNull Context context,
            @NonNull PlannerItem item
    ) {
        if (!item.isReminderEnabled || item.isCompleted) {
            cancel(context, item.id);
            return;
        }
        long triggerAt = nextTriggerTime(item);
        if (triggerAt <= 0L) {
            cancel(context, item.id);
            return;
        }
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) {
            return;
        }
        PendingIntent pendingIntent = pendingIntent(
                context, item.id, item.title, item.notes
        );
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                );
            } else {
                manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                );
            }
        } catch (SecurityException ignored) {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    public static void cancel(@NonNull Context context, long itemId) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) {
            return;
        }
        PendingIntent pendingIntent = pendingIntent(
                context, itemId, null, null
        );
        manager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    public static void handleFired(
            @NonNull Context context,
            long itemId,
            @Nullable Runnable onComplete
    ) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            PlannerItem item = FamilyHubDatabase.getInstance(appContext)
                    .plannerItemDao().getById(itemId);
            if (item != null) {
                if (PlannerItem.REPEAT_NONE.equals(item.repeatType)) {
                    item.isReminderEnabled = false;
                    item.updatedAt = System.currentTimeMillis();
                    FamilyHubDatabase.getInstance(appContext)
                            .plannerItemDao().update(item);
                } else {
                    schedule(appContext, item);
                }
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public static void rescheduleAll(
            @NonNull Context context,
            @Nullable Runnable onComplete
    ) {
        Context appContext = context.getApplicationContext();
        DATABASE_EXECUTOR.execute(() -> {
            List<PlannerItem> items =
                    FamilyHubDatabase.getInstance(appContext)
                            .plannerItemDao().getReminderEnabled();
            for (PlannerItem item : items) {
                schedule(appContext, item);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private static long nextTriggerTime(@NonNull PlannerItem item) {
        long advance = Math.max(0, item.reminderMinutesBefore)
                * 60L * 1000L;
        long triggerAt = item.startAt - advance;
        long now = System.currentTimeMillis();
        if (PlannerItem.REPEAT_NONE.equals(item.repeatType)) {
            return triggerAt > now ? triggerAt : -1L;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(triggerAt);
        while (calendar.getTimeInMillis() <= now) {
            if (PlannerItem.REPEAT_DAILY.equals(item.repeatType)) {
                calendar.add(Calendar.DATE, 1);
            } else if (PlannerItem.REPEAT_WEEKLY.equals(item.repeatType)) {
                calendar.add(Calendar.WEEK_OF_YEAR, 1);
            } else if (PlannerItem.REPEAT_MONTHLY.equals(item.repeatType)) {
                calendar.add(Calendar.MONTH, 1);
            } else if (PlannerItem.REPEAT_YEARLY.equals(item.repeatType)) {
                calendar.add(Calendar.YEAR, 1);
            } else {
                return -1L;
            }
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(
            @NonNull Context context,
            long itemId,
            @Nullable String title,
            @Nullable String notes
    ) {
        Intent intent = new Intent(context, PlannerReceiver.class)
                .setAction(PlannerReceiver.ACTION_FIRE)
                .putExtra(PlannerReceiver.EXTRA_ITEM_ID, itemId);
        if (title != null) {
            intent.putExtra(PlannerReceiver.EXTRA_TITLE, title);
        }
        if (notes != null) {
            intent.putExtra(PlannerReceiver.EXTRA_NOTES, notes);
        }
        return PendingIntent.getBroadcast(
                context,
                (int) (itemId & 0x7fffffff),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
