package com.tridev.familyhub.core.security;

import android.content.Context;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Restores an account's future alarms when it returns after another account was used. */
public final class AccountReminderRestore {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AccountReminderRestore() { }
    public static void clearNotifications(Context context) {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager == null) return;
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            if (AccountScopePolicy.accountNotificationChannel(notification.getNotification().getChannelId()))
                manager.cancel(notification.getTag(),notification.getId());
        }
    }
    public static void restore(Context context, String uid) {
        if (uid.isEmpty()) return;
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            if (!uid.equals(AccountDataScope.uid())) return;
            FamilyHubDatabase db = FamilyHubDatabase.getInstance(app);
            com.tridev.familyhub.core.planner.PlannerScheduler.rescheduleAll(app, () -> { });
            com.tridev.familyhub.core.reminders.ReminderScheduler.rescheduleAll(app, () -> { });
            com.tridev.familyhub.core.tasks.FamilyTaskScheduler.rescheduleAll(app, () -> { });
            java.util.List<NoteEntry> notes = db.noteDao().getActive(); notes.addAll(db.noteDao().getArchived());
            for (NoteEntry note : notes) {
                if (!uid.equals(AccountDataScope.uid())) return;
                com.tridev.familyhub.feature.notes.NoteReminderScheduler.sync(app,note);
            }
            for (HealthRecordWithMember row : db.healthRecordDao().getAllWithMember()) {
                if (!uid.equals(AccountDataScope.uid())) return;
                com.tridev.familyhub.feature.health.HealthReminderScheduler.sync(app,row.record);
            }
            for (VehicleWithOwner row : db.vehicleDao().getAllWithOwner()) {
                if (!uid.equals(AccountDataScope.uid())) return;
                com.tridev.familyhub.feature.vehicle.VehicleReminderScheduler.sync(app,row.vehicle);
            }
        });
    }
}
