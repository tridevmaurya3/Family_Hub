package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.NoteDao;
import com.tridev.familyhub.data.local.entity.NoteEntry;

import java.util.List;
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

    public NotesRepository(@NonNull Context context) {
        noteDao = FamilyHubDatabase.getInstance(context).noteDao();
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
            mainHandler.post(callback::onComplete);
        });
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
            noteDao.delete(note);
            mainHandler.post(callback::onComplete);
        });
    }
}
