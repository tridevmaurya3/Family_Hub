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
    private final Context appContext;
    private final FamilyTaskDao dao;
    private final FamilyTaskActivityRepository activityRepository;
    private final FamilyTaskLinkRepository linkRepository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public FamilyTaskRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        dao = FamilyHubDatabase.getInstance(appContext).familyTaskDao();
        activityRepository = new FamilyTaskActivityRepository(appContext);
        linkRepository = new FamilyTaskLinkRepository(appContext);
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
                            activityRepository.recordRemoteAt(local,
                                    FamilyTaskActivityRepository.EVENT_DELETE, "",
                                    local.updatedByUid, local.updatedByName,
                                    System.currentTimeMillis());
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
            boolean inserting = task.id == 0L;
            FamilyTask previous = inserting ? null : dao.getById(task.id);
            boolean assignmentChanged = previous != null && assignmentChanged(previous, task);
            boolean detailsChanged = previous != null && detailsChanged(previous, task);

            long now = System.currentTimeMillis();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (task.createdAt == 0L) task.createdAt = now;
            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();
            if (task.createdByUid.isEmpty() && user != null) task.createdByUid = user.getUid();
            if (task.createdByName.isEmpty()) task.createdByName = displayName();
            task.updatedByName = displayName();
            task.updatedAt = now;
            if (inserting) task.id = dao.insert(task); else dao.update(task);
            if (task.shared) publish(task);

            if (inserting) {
                activityRepository.record(task, FamilyTaskActivityRepository.EVENT_CREATE, "");
                if (!task.assignedMemberId.isEmpty() || !task.assignedMemberName.isEmpty()) {
                    activityRepository.record(task, FamilyTaskActivityRepository.EVENT_ASSIGN,
                            assignmentLabel(task));
                }
            } else {
                if (detailsChanged) {
                    activityRepository.record(task, FamilyTaskActivityRepository.EVENT_EDIT, "");
                }
                if (assignmentChanged) {
                    activityRepository.record(task, FamilyTaskActivityRepository.EVENT_ASSIGN,
                            assignmentLabel(task));
                }
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void setCompleted(@NonNull FamilyTask task, boolean completed,
                             @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean wasCompleted = FamilyTask.STATUS_COMPLETED.equals(task.status);
            long now = System.currentTimeMillis();
            long previousCompletedAt = task.completedAt;
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();
            if (task.createdAt == 0L) task.createdAt = now;
            if (task.createdByUid.isEmpty() && user != null) {
                task.createdByUid = user.getUid();
            }
            if (task.createdByName.isEmpty()) task.createdByName = displayName();
            task.updatedByName = displayName();
            task.status = completed
                    ? FamilyTask.STATUS_COMPLETED
                    : FamilyTask.STATUS_PENDING;
            task.completedAt = completed ? now : 0L;
            task.completedByName = completed ? displayName() : "";
            task.updatedAt = now;
            if (task.id == 0L) task.id = dao.insert(task); else dao.update(task);
            if (task.shared) publish(task);

            if (wasCompleted != completed) {
                activityRepository.record(task,
                        completed ? FamilyTaskActivityRepository.EVENT_COMPLETE
                                : FamilyTaskActivityRepository.EVENT_REOPEN,
                        "");
            }

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
        next.createdByName = completed.createdByName;
        next.updatedByName = completed.updatedByName;
        next.sourceType = "RECURRING_TASK";
        next.sourceRecordId = seriesId;
        next.linkedGroceryCloudId = completed.linkedGroceryCloudId;
        next.linkedGroceryItemId = completed.linkedGroceryItemId;
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
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String actorUid = user == null ? "" : user.getUid();
            String actorName = displayName();
            long deletedAt = System.currentTimeMillis();

            if (task.familyId.isEmpty()) {
                activityRepository.recordLocalAt(task,
                        FamilyTaskActivityRepository.EVENT_DELETE, "",
                        actorUid, actorName, deletedAt);
            } else {
                activityRepository.recordAt(task,
                        FamilyTaskActivityRepository.EVENT_DELETE, "",
                        actorUid, actorName, deletedAt);
                activityRepository.promoteLocalHistoryForTask(task);
                publishTombstone(task, actorUid, actorName, deletedAt);
            }
            dao.delete(task);
            mainHandler.post(callback::onComplete);
        });
    }

    private void publishTombstone(@NonNull FamilyTask task,
                                  @NonNull String actorUid,
                                  @NonNull String actorName,
                                  long deletedAt) {
        if (task.cloudId.isEmpty() || task.familyId.isEmpty()) return;
        Map<String, Object> values = new HashMap<>();
        values.put("deleted", true);
        values.put("deletedAt", deletedAt);
        values.put("deletedByUid", actorUid);
        values.put("deletedByName", actorName);
        values.put("collaborationStatus", "DELETED");
        values.put("updatedByName", actorName);
        FamilyCollaborationPublisher.publish("tasks", task.cloudId, values,
                (cloudId, familyId, uid) -> { });
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
        values.put("createdByName", task.createdByName);
        values.put("updatedByName", task.updatedByName);
        values.put("completedByName", task.completedByName);
        values.put("sourceType", task.sourceType);
        values.put("sourceRecordId", task.sourceRecordId);
        values.put("linkedGroceryCloudId", task.linkedGroceryCloudId);
        values.put("linkedGroceryItemId", task.linkedGroceryItemId);
        values.put("shared", true);
        FamilyCollaborationPublisher.publish("tasks", task.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    task.cloudId = cloudId;
                    task.familyId = familyId;
                    task.updatedByUid = uid;
                    dao.update(task);
                    // Link metadata is a separate partial write so ordinary task edits
                    // can never overwrite a newer Finance/Loan reference from another device.
                    linkRepository.promoteIfNeeded(task);
                    activityRepository.promoteLocalHistoryForTask(task);
                }));
    }

    private void mergeRemote(@NonNull String familyId, @NonNull DataSnapshot s,
                             @NonNull RealtimeCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(s, "cloudId");
            if (cloudId.isEmpty()) return;

            // Activity children are independent of task revision order. Import them
            // even when this device has a newer local task body.
            activityRepository.mergeRemoteSnapshot(s);

            if (bool(s, "deleted")) {
                FamilyTask local = dao.getByCloudId(cloudId);
                if (local == null) return;
                long id = local.id;
                activityRepository.recordRemoteAt(local,
                        FamilyTaskActivityRepository.EVENT_DELETE, "",
                        text(s, "deletedByUid"), text(s, "deletedByName"),
                        number(s, "deletedAt"));
                dao.delete(local);
                mainHandler.post(() -> callback.onRemoved(id));
                return;
            }

            long remoteUpdatedAt = number(s, "updatedAt");
            FamilyTask task = dao.getByCloudId(cloudId);
            if (task != null && task.updatedAt > remoteUpdatedAt) return;
            boolean insert = task == null;
            FamilyTask previous = insert ? null : auditCopy(task);
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
            task.createdByName = text(s, "createdByName");
            task.updatedByUid = text(s, "updatedByUid");
            task.updatedByName = text(s, "updatedByName");
            task.completedByName = text(s, "completedByName");
            task.sourceType = fallback(text(s, "sourceType"), "FAMILY_TASK");
            task.sourceRecordId = text(s, "sourceRecordId");
            task.linkedGroceryCloudId = text(s, "linkedGroceryCloudId");
            task.linkedGroceryItemId = number(s, "linkedGroceryItemId");
            task.shared = true;

            // Stage 12 link metadata lives outside Room but travels with the Task cloud snapshot.
            linkRepository.mergeRemote(task, s);

            if (insert) task.id = dao.insert(task); else dao.update(task);

            if (insert) {
                activityRepository.recordRemoteAt(task,
                        FamilyTaskActivityRepository.EVENT_CREATE, "",
                        task.createdByUid,
                        task.createdByName.isEmpty() ? task.updatedByName : task.createdByName,
                        task.createdAt > 0L ? task.createdAt : remoteUpdatedAt);
                if (!task.assignedMemberId.isEmpty() || !task.assignedMemberName.isEmpty()) {
                    activityRepository.recordRemoteAt(task,
                            FamilyTaskActivityRepository.EVENT_ASSIGN,
                            assignmentLabel(task), task.updatedByUid, task.updatedByName,
                            remoteUpdatedAt);
                }
            } else if (previous != null) {
                if (!previous.status.equals(task.status)) {
                    activityRepository.recordRemoteAt(task,
                            FamilyTask.STATUS_COMPLETED.equals(task.status)
                                    ? FamilyTaskActivityRepository.EVENT_COMPLETE
                                    : FamilyTaskActivityRepository.EVENT_REOPEN,
                            "", task.updatedByUid, task.updatedByName, remoteUpdatedAt);
                }
                if (assignmentChanged(previous, task)) {
                    activityRepository.recordRemoteAt(task,
                            FamilyTaskActivityRepository.EVENT_ASSIGN,
                            assignmentLabel(task), task.updatedByUid, task.updatedByName,
                            remoteUpdatedAt);
                }
                if (detailsChanged(previous, task)) {
                    activityRepository.recordRemoteAt(task,
                            FamilyTaskActivityRepository.EVENT_EDIT, "",
                            task.updatedByUid, task.updatedByName, remoteUpdatedAt);
                }
            }

            activityRepository.promoteLocalHistoryForTask(task);
            FamilyTask changed = task;
            mainHandler.post(() -> callback.onChanged(changed));
        });
    }

    private static boolean assignmentChanged(@NonNull FamilyTask before,
                                             @NonNull FamilyTask after) {
        return !same(before.assignedMemberId, after.assignedMemberId)
                || !same(before.assignedMemberName, after.assignedMemberName);
    }

    private static boolean detailsChanged(@NonNull FamilyTask before,
                                          @NonNull FamilyTask after) {
        return !same(before.title, after.title)
                || !same(before.notes, after.notes)
                || !same(before.priority, after.priority)
                || !same(before.repeatType, after.repeatType)
                || before.dueAt != after.dueAt
                || before.reminderEnabled != after.reminderEnabled
                || before.reminderMinutesBefore != after.reminderMinutesBefore;
    }

    @NonNull
    private static FamilyTask auditCopy(@NonNull FamilyTask source) {
        FamilyTask copy = new FamilyTask();
        copy.title = source.title;
        copy.notes = source.notes;
        copy.status = source.status;
        copy.priority = source.priority;
        copy.repeatType = source.repeatType;
        copy.assignedMemberId = source.assignedMemberId;
        copy.assignedMemberName = source.assignedMemberName;
        copy.dueAt = source.dueAt;
        copy.reminderEnabled = source.reminderEnabled;
        copy.reminderMinutesBefore = source.reminderMinutesBefore;
        return copy;
    }

    @NonNull
    private static String assignmentLabel(@NonNull FamilyTask task) {
        return task.assignedMemberName.isEmpty() ? "Whole family" : task.assignedMemberName;
    }

    private static boolean same(@Nullable String left, @Nullable String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
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
        Object value = s.child(key).getValue();
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (raw.isEmpty()) return 0L;
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                try {
                    return (long) Double.parseDouble(raw);
                } catch (NumberFormatException ignoredAgain) {
                    return 0L;
                }
            }
        }
        return 0L;
    }
    private static boolean bool(DataSnapshot s, String key) {
        Boolean value = s.child(key).getValue(Boolean.class);
        return value != null && value;
    }
    @NonNull private static String fallback(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
