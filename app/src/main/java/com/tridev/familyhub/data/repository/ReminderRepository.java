package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;

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

    public interface RealtimeCallback {
        void onChanged(@NonNull Reminder reminder);
        void onRemoved(long localId);
    }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final ReminderDao reminderDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public ReminderRepository(Context context) {
        reminderDao = FamilyHubDatabase.getInstance(context).reminderDao();
    }

    public void startRealtimeSync(@NonNull RealtimeCallback callback) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("reminders",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, callback);
                    }
                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            Reminder local = reminderDao.getByCloudId(cloudId);
                            if (local == null) return;
                            long id = local.id;
                            reminderDao.delete(local);
                            mainHandler.post(() -> callback.onRemoved(id));
                        });
                    }
                });
        subscriber.start();
    }

    public void stopRealtimeSync() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
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
        values.put("shared", true);
        values.put("createdAt", reminder.createdAt);
        FamilyCollaborationPublisher.publish("reminders", reminder.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    reminder.cloudId = cloudId;
                    reminder.familyId = familyId;
                    reminder.updatedByUid = uid;
                    reminderDao.update(reminder);
                }));
    }

    private void mergeRemote(@NonNull String familyId, @NonNull DataSnapshot s,
                             @NonNull RealtimeCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(s, "cloudId");
            if (cloudId.isEmpty()) return;
            long updatedAt = number(s, "updatedAt");
            Reminder reminder = reminderDao.getByCloudId(cloudId);
            if (reminder != null && reminder.updatedAt > updatedAt) return;
            boolean insert = reminder == null;
            if (insert) reminder = new Reminder();
            reminder.cloudId=cloudId; reminder.familyId=familyId;
            reminder.title=text(s,"title"); reminder.note=text(s,"note");
            reminder.reminderAt=number(s,"reminderAt");
            reminder.repeatType=fallback(text(s,"repeatType"), Reminder.REPEAT_ONCE);
            reminder.isEnabled=bool(s,"enabled");
            reminder.assignedMemberId=parseLong(text(s,"assignedMemberId"));
            reminder.assignedMemberName=text(s,"assignedMemberName");
            reminder.collaborationStatus=fallback(text(s,"collaborationStatus"),"PENDING");
            reminder.isShared=true; reminder.createdAt=number(s,"createdAt");
            if(reminder.createdAt==0L) reminder.createdAt=updatedAt;
            reminder.updatedAt=updatedAt; reminder.updatedByUid=text(s,"updatedByUid");
            if(insert) reminder.id=reminderDao.insert(reminder); else reminderDao.update(reminder);
            Reminder changed=reminder;
            mainHandler.post(() -> callback.onChanged(changed));
        });
    }

    @NonNull private static String text(DataSnapshot s,String key){String v=s.child(key).getValue(String.class);return v==null?"":v;}
    private static long number(DataSnapshot s,String key){Number v=s.child(key).getValue(Number.class);return v==null?0L:v.longValue();}
    private static boolean bool(DataSnapshot s,String key){Boolean v=s.child(key).getValue(Boolean.class);return v!=null&&v;}
    private static long parseLong(String v){try{return Long.parseLong(v);}catch(NumberFormatException e){return 0L;}}
    @NonNull private static String fallback(String v,String fallback){return v.isEmpty()?fallback:v;}

    public void delete(Reminder reminder, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("reminders", reminder.familyId, reminder.cloudId);
            reminderDao.delete(reminder);
            mainHandler.post(() -> callback.onComplete(reminder));
        });
    }
}
