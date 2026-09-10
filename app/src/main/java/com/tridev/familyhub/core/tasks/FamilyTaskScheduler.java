package com.tridev.familyhub.core.tasks;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local alarm projection for canonical Family Tasks. */
public final class FamilyTaskScheduler {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private FamilyTaskScheduler() { }
    public static void schedule(@NonNull Context context, @NonNull FamilyTask task) {
        if (!task.reminderEnabled || FamilyTask.STATUS_COMPLETED.equals(task.status)) { cancel(context, task.id); return; }
        long trigger = nextTrigger(task);
        if (trigger <= 0) { cancel(context, task.id); return; }
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent pi = pendingIntent(context, task);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        } catch (SecurityException ignored) { manager.set(AlarmManager.RTC_WAKEUP, trigger, pi); }
    }
    public static void cancel(@NonNull Context context, long id) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        PendingIntent pi = pendingIntent(context, id, null, null);
        manager.cancel(pi); pi.cancel();
    }
    public static void snooze(@NonNull Context context, @NonNull FamilyTask task,
                              long delayMillis) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return;
        long trigger = System.currentTimeMillis() + Math.max(60000L, delayMillis);
        PendingIntent pi = pendingIntent(context, task);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException ignored) {
            manager.set(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }
    public static void rescheduleAll(@NonNull Context context, @NonNull Runnable done) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> { for (FamilyTask task : FamilyHubDatabase.getInstance(app).familyTaskDao().getReminderEnabled()) schedule(app, task); done.run(); });
    }
    private static long nextTrigger(FamilyTask task) {
        long trigger = task.dueAt - Math.max(0, task.reminderMinutesBefore) * 60000L;
        if (FamilyTask.REPEAT_NONE.equals(task.repeatType)) return trigger > System.currentTimeMillis() ? trigger : -1;
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(trigger);
        while (c.getTimeInMillis() <= System.currentTimeMillis()) {
            if (FamilyTask.REPEAT_DAILY.equals(task.repeatType)) c.add(Calendar.DAY_OF_YEAR, 1);
            else if (FamilyTask.REPEAT_WEEKLY.equals(task.repeatType)) c.add(Calendar.WEEK_OF_YEAR, 1);
            else if (FamilyTask.REPEAT_MONTHLY.equals(task.repeatType)) c.add(Calendar.MONTH, 1);
            else return -1;
        }
        return c.getTimeInMillis();
    }
    private static PendingIntent pendingIntent(Context context, FamilyTask task) { return pendingIntent(context, task.id, task.title, task.notes); }
    private static PendingIntent pendingIntent(Context context, long id, String title, String notes) {
        Intent intent = new Intent(context, FamilyTaskReceiver.class).setAction(FamilyTaskReceiver.ACTION_FIRE).putExtra(FamilyTaskReceiver.EXTRA_ID, id);
        if (title != null) intent.putExtra(FamilyTaskReceiver.EXTRA_TITLE, title);
        if (notes != null) intent.putExtra(FamilyTaskReceiver.EXTRA_NOTES, notes);
        return PendingIntent.getBroadcast(context, 500000 + (int)(id & 0x0fffffff), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
