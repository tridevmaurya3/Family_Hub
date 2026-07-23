package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps document metadata database work away from the UI thread. */
public class DocumentRepository {

    public interface DocumentsCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntry> documents);
    }

    public interface SaveCallback {
        void onSaved(long documentId);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final DocumentDao documentDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DocumentRepository(@NonNull Context context) {
        documentDao = FamilyHubDatabase
                .getInstance(context)
                .documentDao();
    }

    public void loadDocuments(
            @NonNull String query,
            @NonNull DocumentsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<DocumentEntry> documents = trimmedQuery.isEmpty()
                    ? documentDao.getAll()
                    : documentDao.search(trimmedQuery);

            mainHandler.post(
                    () -> callback.onDocumentsLoaded(documents)
            );
        });
    }

    public void save(
            @NonNull DocumentEntry document,
            @NonNull SaveCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (document.createdAt == 0L) {
                document.createdAt = System.currentTimeMillis();
            }

            long documentId;
            if (document.id == 0L) {
                documentId = documentDao.insert(document);
                document.id = documentId;
            } else {
                documentDao.update(document);
                documentId = document.id;
            }

            long savedId = documentId;
            mainHandler.post(() -> callback.onSaved(savedId));
        });
    }

    public void delete(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            documentDao.delete(document);
            mainHandler.post(callback::onComplete);
        });
    }
}
