package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PlannerItemDao;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.PlannerItem;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for offline family events and tasks. */
public class PlannerRepository {

    public interface ItemsCallback {
        void onItemsLoaded(@NonNull List<PlannerItem> items);
    }

    public interface CountCallback {
        void onCountLoaded(int count);
    }

    public interface ActionCallback {
        void onComplete();
    }

    public interface RealtimeCallback {
        void onChanged(@NonNull PlannerItem item);
        void onRemoved(long localId);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final PlannerItemDao plannerItemDao;
    private final FamilyMemberDao familyMemberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public PlannerRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        plannerItemDao = database.plannerItemDao();
        familyMemberDao = database.familyMemberDao();
    }

    public void startRealtimeSync(@NonNull RealtimeCallback callback) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("planner",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, callback);
                    }
                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            PlannerItem local = plannerItemDao.getByCloudId(cloudId);
                            if (local == null) return;
                            long id = local.id;
                            plannerItemDao.delete(local);
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

    public void loadAll(
            @NonNull String query,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<PlannerItem> items = trimmedQuery.isEmpty()
                    ? plannerItemDao.getAll()
                    : plannerItemDao.search(trimmedQuery);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadInRange(
            long rangeStart,
            long rangeEnd,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<PlannerItem> items =
                    plannerItemDao.getInRange(rangeStart, rangeEnd);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadUpcoming(
            int limit,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<PlannerItem> items = plannerItemDao.getUpcoming(
                    System.currentTimeMillis(),
                    limit
            );
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadUpcomingCount(@NonNull CountCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            int count = plannerItemDao.countUpcoming(
                    System.currentTimeMillis()
            );
            mainHandler.post(() -> callback.onCountLoaded(count));
        });
    }

    public void save(
            @NonNull PlannerItem item,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            if (item.createdAt == 0L) {
                item.createdAt = now;
            }
            item.updatedAt = now;
            if (item.id == 0L) {
                item.id = plannerItemDao.insert(item);
            } else {
                plannerItemDao.update(item);
            }
            if (item.isShared) {
                publish(item);
            } else {
                String previousFamilyId = item.familyId;
                String previousCloudId = item.cloudId;
                item.familyId = "";
                item.cloudId = "";
                item.updatedByUid = "";
                plannerItemDao.update(item);
                FamilyCollaborationPublisher.remove(
                        "planner", previousFamilyId, previousCloudId);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    private void publish(@NonNull PlannerItem item) {
        Map<String, Object> values = new HashMap<>();
        values.put("title", item.title);
        values.put("notes", item.notes);
        values.put("location", item.location);
        values.put("itemType", item.itemType);
        values.put("priority", item.priority);
        values.put("startAt", item.startAt);
        values.put("endAt", item.endAt);
        values.put("assignedMemberId", item.assignedMemberId == null ? "" : String.valueOf(item.assignedMemberId));
        values.put("assignedMemberName", item.assignedMemberName);
        values.put("collaborationStatus", item.collaborationStatus);
        values.put("allDay", item.isAllDay);
        values.put("repeatType", item.repeatType);
        values.put("completed", item.isCompleted);
        values.put("reminderEnabled", item.isReminderEnabled);
        values.put("reminderMinutesBefore", item.reminderMinutesBefore);
        values.put("shared", true);
        values.put("createdAt", item.createdAt);
        FamilyCollaborationPublisher.publish("planner", item.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    item.cloudId = cloudId;
                    item.familyId = familyId;
                    item.updatedByUid = uid;
                    plannerItemDao.update(item);
                }));
    }

    private void mergeRemote(@NonNull String familyId, @NonNull DataSnapshot s,
                             @NonNull RealtimeCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(s, "cloudId");
            if (cloudId.isEmpty()) return;
            long updatedAt = number(s, "updatedAt");
            PlannerItem item = plannerItemDao.getByCloudId(cloudId);
            if (item != null && item.updatedAt > updatedAt) return;
            boolean insert = item == null;
            if (insert) item = new PlannerItem();
            item.cloudId = cloudId; item.familyId = familyId;
            item.title = text(s, "title"); item.notes = text(s, "notes");
            item.location = text(s, "location");
            item.itemType = fallback(text(s, "itemType"), PlannerItem.TYPE_EVENT);
            item.priority = fallback(text(s, "priority"), PlannerItem.PRIORITY_NORMAL);
            item.startAt = number(s, "startAt"); item.endAt = number(s, "endAt");
            item.isAllDay = bool(s, "allDay");
            item.assignedMemberName = text(s, "assignedMemberName");
            item.assignedMemberId = resolveLocalMemberId(
                    item.assignedMemberName);
            item.collaborationStatus = fallback(text(s, "collaborationStatus"), "PENDING");
            item.repeatType = fallback(text(s, "repeatType"), PlannerItem.REPEAT_NONE);
            item.isCompleted = bool(s, "completed");
            item.isReminderEnabled = bool(s, "reminderEnabled");
            item.reminderMinutesBefore = (int) number(s, "reminderMinutesBefore");
            item.isShared = true; item.createdAt = number(s, "createdAt");
            if (item.createdAt == 0L) item.createdAt = updatedAt;
            item.updatedAt = updatedAt; item.updatedByUid = text(s, "updatedByUid");
            if (insert) item.id = plannerItemDao.insert(item); else plannerItemDao.update(item);
            PlannerItem changed = item;
            mainHandler.post(() -> callback.onChanged(changed));
        });
    }

    @NonNull private static String text(DataSnapshot s, String key) { String v=s.child(key).getValue(String.class); return v==null?"":v; }
    private static long number(DataSnapshot s,String key){Number v=s.child(key).getValue(Number.class);return v==null?0L:v.longValue();}
    private static boolean bool(DataSnapshot s,String key){Boolean v=s.child(key).getValue(Boolean.class);return v!=null&&v;}
    private static long parseLong(String v){try{return Long.parseLong(v);}catch(NumberFormatException e){return 0L;}}
    @NonNull private static String fallback(String v,String fallback){return v.isEmpty()?fallback:v;}

    @Nullable
    private Long resolveLocalMemberId(@NonNull String memberName) {
        if (memberName.trim().isEmpty()) return null;
        FamilyMember local = familyMemberDao.getByName(memberName.trim());
        return local == null ? null : local.id;
    }

    public void setCompleted(
            @NonNull PlannerItem item,
            boolean completed,
            @NonNull ActionCallback callback
    ) {
        item.isCompleted = completed;
        save(item, callback);
    }

    public void delete(
            @NonNull PlannerItem item,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("planner", item.familyId, item.cloudId);
            plannerItemDao.delete(item);
            mainHandler.post(callback::onComplete);
        });
    }
}
