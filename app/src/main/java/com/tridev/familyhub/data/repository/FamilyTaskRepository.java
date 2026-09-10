package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyTaskDao;
import com.tridev.familyhub.data.local.entity.FamilyTask;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-first, idempotent realtime boundary for Family To-Do. */
public final class FamilyTaskRepository {
    public interface ItemsCallback { void onLoaded(@NonNull List<FamilyTask> tasks); }
    public interface ActionCallback { void onComplete(); }
    public interface RealtimeCallback {
        void onChanged(@NonNull FamilyTask task);
        void onRemoved(long localId);
    }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();
    private final FamilyTaskDao dao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public FamilyTaskRepository(@NonNull Context context) {
        dao = FamilyHubDatabase.getInstance(context).familyTaskDao();
    }

    public void loadAll(@NonNull String query, @NonNull ItemsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String clean = query.trim();
            List<FamilyTask> tasks = clean.isEmpty() ? dao.getAll() : dao.search(clean);
            mainHandler.post(() -> callback.onLoaded(tasks));
        });
    }

    public void startRealtimeSync(@NonNull RealtimeCallback callback) {
        stopRealtimeSync();
        retryPending();
        subscriber = new FamilyCollaborationSubscriber("tasks",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, callback);
                    }
                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            FamilyTask local = dao.getByCloudId(cloudId);
                            if (local == null) return;
                            long id = local.id;
                            dao.delete(local);
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

    public void save(@NonNull FamilyTask task, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (task.createdAt == 0L) task.createdAt = now;
            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();
            if (task.createdByUid.isEmpty() && user != null) task.createdByUid = user.getUid();
            task.updatedAt = now;
            if (task.id == 0L) task.id = dao.insert(task); else dao.update(task);
            if (task.shared) publish(task);
            mainHandler.post(callback::onComplete);
        });
    }

    public void setCompleted(@NonNull FamilyTask task, boolean completed,
                             @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            long previousCompletedAt = task.completedAt;
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();
            if (task.createdAt == 0L) task.createdAt = now;
            if (task.createdByUid.isEmpty() && user != null) {
                task.createdByUid = user.getUid();
            }
            task.status = completed
                    ? FamilyTask.STATUS_COMPLETED
                    : FamilyTask.STATUS_PENDING;
            task.completedAt = completed ? now : 0L;
            task.completedByName = completed ? displayName() : "";
            task.updatedAt = now;
            if (task.id == 0L) task.id = dao.insert(task); else dao.update(task);
            if (task.shared) publish(task);

            if (!FamilyTask.REPEAT_NONE.equals(task.repeatType)) {
                long nextDueAt = nextDueAt(task.dueAt, task.repeatType);
                String seriesId = task.sourceRecordId.isEmpty()
                        ? task.cloudId : task.sourceRecordId;
                String nextCloudId = occurrenceId(seriesId, nextDueAt);
                FamilyTask next = dao.getByCloudId(nextCloudId);
                if (completed) {
                    if (next == null) {
                        next = nextOccurrence(task, seriesId, nextCloudId,
                                nextDueAt, now);
                        next.id = dao.insert(next);
                    }
                    if (next.shared) publish(next);
                } else if (next != null
                        && FamilyTask.STATUS_PENDING.equals(next.status)
                        && next.createdAt == previousCompletedAt) {
                    FamilyCollaborationPublisher.remove(
                            "tasks", next.familyId, next.cloudId);
                    dao.delete(next);
                }
            }
            mainHandler.post(callback::onComplete);
        });
    }

    @NonNull
    private static FamilyTask nextOccurrence(
            @NonNull FamilyTask completed,
            @NonNull String seriesId,
            @NonNull String cloudId,
            long dueAt,
            long createdAt
    ) {
        FamilyTask next = new FamilyTask();
        next.cloudId = cloudId;
        next.familyId = completed.familyId;
        next.title = completed.title;
        next.notes = completed.notes;
        next.priority = completed.priority;
        next.repeatType = completed.repeatType;
        next.assignedMemberId = completed.assignedMemberId;
        next.assignedMemberName = completed.assignedMemberName;
        next.dueAt = dueAt;
        next.reminderEnabled = completed.reminderEnabled;
        next.reminderMinutesBefore = completed.reminderMinutesBefore;
        next.createdAt = createdAt;
        next.updatedAt = createdAt;
        next.createdByUid = completed.createdByUid;
        next.sourceType = "RECURRING_TASK";
        next.sourceRecordId = seriesId;
        next.shared = completed.shared;
        return next;
    }

    private static long nextDueAt(long dueAt, @NonNull String repeatType) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dueAt);
        if (FamilyTask.REPEAT_DAILY.equals(repeatType)) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        } else if (FamilyTask.REPEAT_WEEKLY.equals(repeatType)) {
            calendar.add(Calendar.WEEK_OF_YEAR, 1);
        } else if (FamilyTask.REPEAT_MONTHLY.equals(repeatType)) {
            calendar.add(Calendar.MONTH, 1);
        }
        return calendar.getTimeInMillis();
    }

    @NonNull
    private static String occurrenceId(@NonNull String seriesId, long dueAt) {
        String key = "family-task:" + seriesId + ":" + dueAt;
        return UUID.nameUUIDFromBytes(
                key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void delete(@NonNull FamilyTask task, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("tasks", task.familyId, task.cloudId);
            dao.delete(task);
            mainHandler.post(callback::onComplete);
        });
    }

    private void retryPending() {
        DATABASE_EXECUTOR.execute(() -> {
            for (FamilyTask task : dao.getPendingShared()) publish(task);
        });
    }

    private void publish(@NonNull FamilyTask task) {
        Map<String, Object> values = new HashMap<>();
        values.put("title", task.title);
        values.put("notes", task.notes);
        values.put("status", task.status);
        values.put("priority", task.priority);
        values.put("repeatType", task.repeatType);
        values.put("assignedMemberId", task.assignedMemberId);
        values.put("assignedMemberName", task.assignedMemberName);
        values.put("collaborationStatus", task.status);
        values.put("dueAt", task.dueAt);
        values.put("reminderEnabled", task.reminderEnabled);
        values.put("reminderMinutesBefore", task.reminderMinutesBefore);
        values.put("createdAt", task.createdAt);
        values.put("completedAt", task.completedAt);
        values.put("createdByUid", task.createdByUid);
        values.put("completedByName", task.completedByName);
        values.put("sourceType", task.sourceType);
        values.put("sourceRecordId", task.sourceRecordId);
        values.put("shared", true);
        FamilyCollaborationPublisher.publish("tasks", task.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    task.cloudId = cloudId;
                    task.familyId = familyId;
                    task.updatedByUid = uid;
                    dao.update(task);
                }));
    }

    private void mergeRemote(@NonNull String familyId, @NonNull DataSnapshot s,
                             @NonNull RealtimeCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(s, "cloudId");
            if (cloudId.isEmpty()) return;
            long remoteUpdatedAt = number(s, "updatedAt");
            FamilyTask task = dao.getByCloudId(cloudId);
            if (task != null && task.updatedAt > remoteUpdatedAt) return;
            boolean insert = task == null;
            if (insert) task = new FamilyTask();
            task.cloudId = cloudId;
            task.familyId = familyId;
            task.title = text(s, "title");
            task.notes = text(s, "notes");
            task.status = fallback(text(s, "status"), FamilyTask.STATUS_PENDING);
            task.priority = fallback(text(s, "priority"), FamilyTask.PRIORITY_NORMAL);
            task.repeatType = fallback(text(s, "repeatType"), FamilyTask.REPEAT_NONE);
            task.assignedMemberId = text(s, "assignedMemberId");
            task.assignedMemberName = text(s, "assignedMemberName");
            task.dueAt = number(s, "dueAt");
            task.reminderEnabled = bool(s, "reminderEnabled");
            task.reminderMinutesBefore = (int) number(s, "reminderMinutesBefore");
            task.createdAt = number(s, "createdAt");
            task.updatedAt = remoteUpdatedAt;
            task.completedAt = number(s, "completedAt");
            task.createdByUid = text(s, "createdByUid");
            task.updatedByUid = text(s, "updatedByUid");
            task.completedByName = text(s, "completedByName");
            task.sourceType = fallback(text(s, "sourceType"), "FAMILY_TASK");
            task.sourceRecordId = text(s, "sourceRecordId");
            task.shared = true;
            if (insert) task.id = dao.insert(task); else dao.update(task);
            FamilyTask changed = task;
            mainHandler.post(() -> callback.onChanged(changed));
        });
    }

    @NonNull private static String displayName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()) {
            return "Family member";
        }
        return user.getDisplayName().trim();
    }
    @NonNull private static String text(DataSnapshot s, String key) {
        String value = s.child(key).getValue(String.class);
        return value == null ? "" : value;
    }
    private static long number(DataSnapshot s, String key) {
        Number value = s.child(key).getValue(Number.class);
        return value == null ? 0L : value.longValue();
    }
    private static boolean bool(DataSnapshot s, String key) {
        Boolean value = s.child(key).getValue(Boolean.class);
        return value != null && value;
    }
    @NonNull private static String fallback(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
