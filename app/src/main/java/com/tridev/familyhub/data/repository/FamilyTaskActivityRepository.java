package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.tridev.familyhub.data.local.entity.FamilyTask;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Privacy-safe Family To-Do activity history.
 *
 * Stage 12 keeps the Stage 11 device cache for offline use and also mirrors each
 * compact event inside the existing authorised Task cloud record. Only task title,
 * action, actor and time are mirrored; notes, SMS text, Grocery data and financial
 * details are never copied into activity history.
 */
public final class FamilyTaskActivityRepository {
    public static final String EVENT_CREATE = "CREATE";
    public static final String EVENT_EDIT = "EDIT";
    public static final String EVENT_ASSIGN = "ASSIGN";
    public static final String EVENT_COMPLETE = "COMPLETE";
    public static final String EVENT_REOPEN = "REOPEN";
    public static final String EVENT_DELETE = "DELETE";

    public interface EventsCallback {
        void onLoaded(@NonNull List<ActivityEvent> events);
    }

    public interface ChangeCallback {
        void onChanged();
    }

    public static final class ActivityEvent {
        @NonNull public String eventId = "";
        @NonNull public String taskCloudId = "";
        @NonNull public String taskTitle = "";
        @NonNull public String eventType = "";
        @NonNull public String actorUid = "";
        @NonNull public String actorName = "";
        @NonNull public String detail = "";
        public long eventAt;
    }

    private static final String PREFS = "family_task_activity_history_v1";
    private static final String KEY_PREFIX = "event:";
    private static final String SYNC_PREFIX = "cloudSynced:";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @NonNull private final SharedPreferences preferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    public FamilyTaskActivityRepository(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void loadAll(@NonNull EventsCallback callback) {
        EXECUTOR.execute(() -> {
            List<ActivityEvent> events = loadAllNow();
            mainHandler.post(() -> callback.onLoaded(events));
        });
    }

    /** Keeps an open timeline current when local or remote sync stores an event. */
    public void startObserving(@NonNull ChangeCallback callback) {
        stopObserving();
        preferenceListener = (prefs, key) -> {
            if (key != null && key.startsWith(KEY_PREFIX)) {
                mainHandler.post(callback::onChanged);
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    public void stopObserving() {
        if (preferenceListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        preferenceListener = null;
    }

    /** Records an action performed by the currently signed-in Family Hub user. */
    public void record(@NonNull FamilyTask task, @NonNull String eventType,
                       @Nullable String detail) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user == null ? "" : safe(user.getUid());
        String actor = displayName(user);
        long when = task.updatedAt > 0L ? task.updatedAt : System.currentTimeMillis();
        recordAt(task, eventType, detail, uid, actor, when);
    }

    /** Records locally and publishes a compact, idempotent event for family devices. */
    public void recordAt(@NonNull FamilyTask task, @NonNull String eventType,
                         @Nullable String detail, @Nullable String actorUid,
                         @Nullable String actorName, long eventAt) {
        ActivityEvent event = buildEvent(task, eventType, detail,
                actorUid, actorName, eventAt);
        if (event == null) return;
        saveLocal(event, false);
        publish(task, event);
    }

    /** Stores a reconstructed remote action without re-publishing it as this user. */
    public void recordRemoteAt(@NonNull FamilyTask task, @NonNull String eventType,
                               @Nullable String detail, @Nullable String actorUid,
                               @Nullable String actorName, long eventAt) {
        ActivityEvent event = buildEvent(task, eventType, detail,
                actorUid, actorName, eventAt);
        if (event == null) return;
        saveLocal(event, true);
    }

    /** Local-only path for a task deleted before it ever acquired a family identity. */
    public void recordLocalAt(@NonNull FamilyTask task, @NonNull String eventType,
                              @Nullable String detail, @Nullable String actorUid,
                              @Nullable String actorName, long eventAt) {
        ActivityEvent event = buildEvent(task, eventType, detail,
                actorUid, actorName, eventAt);
        if (event == null) return;
        saveLocal(event, false);
    }

    /** Imports durable activity children from an authorised Task realtime snapshot. */
    public int mergeRemoteSnapshot(@NonNull DataSnapshot taskSnapshot) {
        DataSnapshot activity = taskSnapshot.child("activity");
        if (!activity.exists()) return 0;
        int count = 0;
        for (DataSnapshot child : activity.getChildren()) {
            ActivityEvent event = fromSnapshot(child);
            if (event == null) continue;
            saveLocal(event, true);
            count++;
        }
        return count;
    }

    /**
     * Promotes Stage 11/offline local events once the task has a confirmed cloud identity.
     * Event IDs are deterministic, so repeated retries overwrite the same child only.
     */
    public void promoteLocalHistoryForTask(@NonNull FamilyTask task) {
        if (task.cloudId.isEmpty() || task.familyId.isEmpty()) return;
        EXECUTOR.execute(() -> {
            List<ActivityEvent> events = loadAllNow();
            for (ActivityEvent event : events) {
                if (!task.cloudId.equals(event.taskCloudId) || isSynced(event.eventId)) continue;
                mainHandler.post(() -> publish(task, event));
            }
        });
    }

    @Nullable
    private static ActivityEvent buildEvent(@NonNull FamilyTask task,
                                            @NonNull String eventType,
                                            @Nullable String detail,
                                            @Nullable String actorUid,
                                            @Nullable String actorName,
                                            long eventAt) {
        String taskCloudId = safe(task.cloudId);
        if (taskCloudId.isEmpty()) return null;
        ActivityEvent event = new ActivityEvent();
        event.taskCloudId = taskCloudId;
        event.taskTitle = limit(safe(task.title), 120);
        event.eventType = normalizeType(eventType);
        if (event.eventType.isEmpty()) return null;
        event.actorUid = limit(safe(actorUid), 128);
        event.actorName = limit(safe(actorName), 100);
        if (event.actorName.isEmpty()) event.actorName = "Family member";
        event.detail = limit(safe(detail), 100);
        event.eventAt = eventAt > 0L ? eventAt : System.currentTimeMillis();
        event.eventId = stableEventId(event);
        return event;
    }

    @NonNull
    private List<ActivityEvent> loadAllNow() {
        List<ActivityEvent> out = new ArrayList<>();
        Map<String, ?> all = preferences.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX)
                    || !(entry.getValue() instanceof String)) continue;
            ActivityEvent event = fromJson((String) entry.getValue());
            if (event != null) out.add(event);
        }
        out.sort(Comparator.comparingLong((ActivityEvent e) -> e.eventAt).reversed());
        return out;
    }

    private void saveLocal(@NonNull ActivityEvent event, boolean cloudSynced) {
        try {
            JSONObject json = new JSONObject();
            json.put("eventId", event.eventId);
            json.put("taskCloudId", event.taskCloudId);
            json.put("taskTitle", event.taskTitle);
            json.put("eventType", event.eventType);
            json.put("actorUid", event.actorUid);
            json.put("actorName", event.actorName);
            json.put("detail", event.detail);
            json.put("eventAt", event.eventAt);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_PREFIX + event.eventId, json.toString());
            if (cloudSynced) editor.putBoolean(SYNC_PREFIX + event.eventId, true);
            editor.apply();
        } catch (Exception ignored) {
            // Audit storage must never block the underlying task action.
        }
    }

    /**
     * Appends only to an already-existing Task cloud record. This intentionally
     * avoids re-sending the Task body, so activity retry can never resurrect or
     * overwrite a stale/deleted task.
     */
    private void publish(@NonNull FamilyTask task, @NonNull ActivityEvent event) {
        if (task.familyId.isEmpty() || isSynced(event.eventId)) return;

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("eventId", event.eventId);
        eventPayload.put("taskCloudId", event.taskCloudId);
        eventPayload.put("taskTitle", event.taskTitle);
        eventPayload.put("eventType", event.eventType);
        eventPayload.put("actorUid", event.actorUid);
        eventPayload.put("actorName", event.actorName);
        eventPayload.put("detail", event.detail);
        eventPayload.put("eventAt", event.eventAt);

        Map<String, Object> values = new HashMap<>();
        values.put("activity/" + event.eventId, eventPayload);
        values.put("updatedByName", displayName(FirebaseAuth.getInstance().getCurrentUser()));
        FamilyCollaborationPublisher.publish("tasks", task.cloudId, values,
                (cloudId, familyId, uid) -> preferences.edit()
                        .putBoolean(SYNC_PREFIX + event.eventId, true).apply());
    }

    private boolean isSynced(@NonNull String eventId) {
        return preferences.getBoolean(SYNC_PREFIX + eventId, false);
    }

    @Nullable
    private static ActivityEvent fromSnapshot(@NonNull DataSnapshot snapshot) {
        ActivityEvent event = new ActivityEvent();
        event.eventId = safe(text(snapshot, "eventId"));
        if (event.eventId.isEmpty() && snapshot.getKey() != null) {
            event.eventId = safe(snapshot.getKey());
        }
        event.taskCloudId = safe(text(snapshot, "taskCloudId"));
        event.taskTitle = limit(safe(text(snapshot, "taskTitle")), 120);
        event.eventType = normalizeType(text(snapshot, "eventType"));
        event.actorUid = limit(safe(text(snapshot, "actorUid")), 128);
        event.actorName = limit(safe(text(snapshot, "actorName")), 100);
        event.detail = limit(safe(text(snapshot, "detail")), 100);
        Number at = snapshot.child("eventAt").getValue(Number.class);
        event.eventAt = at == null ? 0L : at.longValue();
        if (event.eventId.isEmpty() || event.taskCloudId.isEmpty()
                || event.eventType.isEmpty() || event.eventAt <= 0L) return null;
        if (event.actorName.isEmpty()) event.actorName = "Family member";
        return event;
    }

    @Nullable
    private static ActivityEvent fromJson(@NonNull String encoded) {
        try {
            JSONObject json = new JSONObject(encoded);
            ActivityEvent event = new ActivityEvent();
            event.eventId = safe(json.optString("eventId"));
            event.taskCloudId = safe(json.optString("taskCloudId"));
            event.taskTitle = safe(json.optString("taskTitle"));
            event.eventType = normalizeType(json.optString("eventType"));
            event.actorUid = safe(json.optString("actorUid"));
            event.actorName = safe(json.optString("actorName"));
            event.detail = safe(json.optString("detail"));
            event.eventAt = json.optLong("eventAt", 0L);
            if (event.eventId.isEmpty() || event.taskCloudId.isEmpty()
                    || event.eventType.isEmpty() || event.eventAt <= 0L) return null;
            return event;
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private static String stableEventId(@NonNull ActivityEvent event) {
        String source = event.taskCloudId + "|" + event.eventType + "|"
                + event.eventAt + "|" + event.actorUid + "|" + event.detail;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @NonNull
    private static String normalizeType(@Nullable String value) {
        String safe = FamilyTaskActivityRepository.safe(value).toUpperCase();
        if (EVENT_CREATE.equals(safe) || EVENT_EDIT.equals(safe)
                || EVENT_ASSIGN.equals(safe) || EVENT_COMPLETE.equals(safe)
                || EVENT_REOPEN.equals(safe) || EVENT_DELETE.equals(safe)) return safe;
        return "";
    }

    @NonNull
    private static String displayName(@Nullable FirebaseUser user) {
        if (user == null) return "Family member";
        String name = safe(user.getDisplayName());
        return name.isEmpty() ? "Family member" : limit(name, 100);
    }

    @NonNull
    private static String text(@NonNull DataSnapshot snapshot, @NonNull String key) {
        String value = snapshot.child(key).getValue(String.class);
        return value == null ? "" : value;
    }

    @NonNull
    private static String limit(@NonNull String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).trim();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
