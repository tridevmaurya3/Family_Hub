package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.ReminderDao;
import com.tridev.familyhub.data.local.entity.Reminder;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thread-safe Room boundary for the reminder feature. */
public class ReminderRepository {

    public interface RemindersCallback {
        void onRemindersLoaded(List<Reminder> reminders);
    }

    public interface ActionCallback {
        void onComplete(Reminder reminder);
    }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final ReminderDao reminderDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ReminderRepository(Context context) {
        reminderDao = FamilyHubDatabase.getInstance(context).reminderDao();
    }

    public void loadReminders(@NonNull String searchQuery, @NonNull RemindersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<Reminder> reminders = searchQuery.trim().isEmpty()
                    ? reminderDao.getAll()
                    : reminderDao.search(searchQuery.trim());
            mainHandler.post(() -> callback.onRemindersLoaded(reminders));
        });
    }

    public void loadEnabledReminders(@NonNull RemindersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<Reminder> reminders = reminderDao.getEnabled();
            mainHandler.post(() -> callback.onRemindersLoaded(reminders));
        });
    }

    public void save(Reminder reminder, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            reminder.updatedAt = System.currentTimeMillis();
            if (reminder.id == 0) {
                reminder.createdAt = reminder.updatedAt;
                reminder.id = reminderDao.insert(reminder);
            } else {
                reminderDao.update(reminder);
            }
            if (reminder.isShared) {
                publish(reminder);
            } else {
                FamilyCollaborationPublisher.remove("reminders", reminder.familyId, reminder.cloudId);
            }
            mainHandler.post(() -> callback.onComplete(reminder));
        });
    }

    private void publish(@NonNull Reminder reminder) {
        Map<String, Object> values = new HashMap<>();
        values.put("title", reminder.title);
        values.put("note", reminder.note);
        values.put("reminderAt", reminder.reminderAt);
        values.put("repeatType", reminder.repeatType);
        values.put("enabled", reminder.isEnabled);
        values.put("assignedMemberId", reminder.assignedMemberId == 0 ? "" : String.valueOf(reminder.assignedMemberId));
        values.put("assignedMemberName", reminder.assignedMemberName);
        values.put("collaborationStatus", reminder.collaborationStatus);
        FamilyCollaborationPublisher.publish("reminders", reminder.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    reminder.cloudId = cloudId;
                    reminder.familyId = familyId;
                    reminder.updatedByUid = uid;
                    reminderDao.update(reminder);
                }));
    }

    public void delete(Reminder reminder, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("reminders", reminder.familyId, reminder.cloudId);
            reminderDao.delete(reminder);
            mainHandler.post(() -> callback.onComplete(reminder));
        });
    }
}
