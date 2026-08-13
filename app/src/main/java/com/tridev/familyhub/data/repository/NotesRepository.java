package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.NoteDao;
import com.tridev.familyhub.data.local.entity.NoteEntry;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for local text notes and checklists. */
public class NotesRepository {

    public interface NotesCallback {
        void onNotesLoaded(@NonNull List<NoteEntry> notes);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final NoteDao noteDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public NotesRepository(@NonNull Context context) {
        noteDao = FamilyHubDatabase.getInstance(context).noteDao();
    }

    /** Starts family-scoped inbound sync and refreshes the visible page on changes. */
    public void startRealtimeSync(@NonNull ActionCallback onChanged) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("notes",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemoteNote(familyId, snapshot, onChanged);
                    }

                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            NoteEntry local = noteDao.getByCloudId(cloudId);
                            if (local != null && local.isShared) {
                                noteDao.delete(local);
                            }
                            mainHandler.post(onChanged::onComplete);
                        });
                    }
                });
        subscriber.start();
    }

    public void stopRealtimeSync() {
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
        }
    }

    public void loadActive(
            @NonNull String query,
            @NonNull NotesCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<NoteEntry> notes = trimmedQuery.isEmpty()
                    ? noteDao.getActive()
                    : noteDao.searchActive(trimmedQuery);
            mainHandler.post(() -> callback.onNotesLoaded(notes));
        });
    }

    public void loadArchived(@NonNull NotesCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<NoteEntry> notes = noteDao.getArchived();
            mainHandler.post(() -> callback.onNotesLoaded(notes));
        });
    }

    public void save(
            @NonNull NoteEntry note,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            if (note.createdAt == 0L) {
                note.createdAt = now;
            }
            note.updatedAt = now;
            if (note.id == 0L) {
                note.id = noteDao.insert(note);
            } else {
                noteDao.update(note);
            }
            if (note.isShared) {
                publish(note);
            } else {
                String previousFamilyId = note.familyId;
                String previousCloudId = note.cloudId;
                note.familyId = "";
                note.cloudId = "";
                note.updatedByUid = "";
                noteDao.update(note);
                FamilyCollaborationPublisher.remove(
                        "notes", previousFamilyId, previousCloudId);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    private void publish(@NonNull NoteEntry note) {
        Map<String, Object> values = new HashMap<>();
        values.put("title", note.title);
        values.put("content", note.content);
        values.put("category", note.category);
        values.put("noteType", note.noteType);
        values.put("colorKey", note.colorKey);
        values.put("assignedMemberId", note.assignedMemberId == 0 ? "" : String.valueOf(note.assignedMemberId));
        values.put("assignedMemberName", note.assignedMemberName);
        values.put("collaborationStatus", note.collaborationStatus);
        values.put("reminderAt", note.reminderAt);
        values.put("pinned", note.isPinned);
        values.put("archived", note.isArchived);
        values.put("shared", true);
        values.put("createdAt", note.createdAt);
        FamilyCollaborationPublisher.publish("notes", note.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    note.cloudId = cloudId;
                    note.familyId = familyId;
                    note.updatedByUid = uid;
                    noteDao.update(note);
                }));
    }

    private void mergeRemoteNote(@NonNull String familyId,
                                 @NonNull DataSnapshot snapshot,
                                 @NonNull ActionCallback onChanged) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = stringValue(snapshot, "cloudId");
            if (cloudId.isEmpty()) return;
            long remoteUpdatedAt = longValue(snapshot, "updatedAt");
            NoteEntry note = noteDao.getByCloudId(cloudId);
            if (note != null && note.updatedAt > remoteUpdatedAt) return;
            boolean insert = note == null;
            if (insert) note = new NoteEntry();
            note.cloudId = cloudId;
            note.familyId = familyId;
            note.title = stringValue(snapshot, "title");
            note.content = stringValue(snapshot, "content");
            note.category = stringValue(snapshot, "category");
            note.noteType = fallback(stringValue(snapshot, "noteType"), NoteEntry.TYPE_TEXT);
            note.colorKey = fallback(stringValue(snapshot, "colorKey"), "BLUE");
            note.assignedMemberId = parseLong(stringValue(snapshot, "assignedMemberId"));
            note.assignedMemberName = stringValue(snapshot, "assignedMemberName");
            note.collaborationStatus = fallback(
                    stringValue(snapshot, "collaborationStatus"), "DRAFT");
            note.reminderAt = longValue(snapshot, "reminderAt");
            note.isPinned = booleanValue(snapshot, "pinned");
            note.isArchived = booleanValue(snapshot, "archived");
            note.isShared = true;
            note.createdAt = longValue(snapshot, "createdAt");
            if (note.createdAt == 0L) note.createdAt = remoteUpdatedAt;
            note.updatedAt = remoteUpdatedAt;
            note.updatedByUid = stringValue(snapshot, "updatedByUid");
            if (insert) note.id = noteDao.insert(note); else noteDao.update(note);
            mainHandler.post(onChanged::onComplete);
        });
    }

    @NonNull private static String stringValue(@NonNull DataSnapshot source,
                                                @NonNull String key) {
        String value = source.child(key).getValue(String.class);
        return value == null ? "" : value;
    }

    private static long longValue(@NonNull DataSnapshot source, @NonNull String key) {
        Number value = source.child(key).getValue(Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static boolean booleanValue(@NonNull DataSnapshot source,
                                        @NonNull String key) {
        Boolean value = source.child(key).getValue(Boolean.class);
        return value != null && value;
    }

    private static long parseLong(@NonNull String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
    }

    @NonNull private static String fallback(@NonNull String value,
                                            @NonNull String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    public void setPinned(
            @NonNull NoteEntry note,
            boolean pinned,
            @NonNull ActionCallback callback
    ) {
        note.isPinned = pinned;
        save(note, callback);
    }

    public void setArchived(
            @NonNull NoteEntry note,
            boolean archived,
            @NonNull ActionCallback callback
    ) {
        note.isArchived = archived;
        if (archived) {
            note.isPinned = false;
        }
        save(note, callback);
    }

    public void delete(
            @NonNull NoteEntry note,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("notes", note.familyId, note.cloudId);
            noteDao.delete(note);
            mainHandler.post(callback::onComplete);
        });
    }
}
