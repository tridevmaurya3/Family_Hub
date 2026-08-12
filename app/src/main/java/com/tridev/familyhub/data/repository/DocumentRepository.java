package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.feature.documents.DocumentExpiryPolicy;
import com.tridev.familyhub.feature.documents.DocumentExpiryScheduler;

import java.util.List;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Keeps Documents Vault database work away from the UI thread. */
public class DocumentRepository {

    public interface DocumentsCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntry> documents);
    }

    public interface SaveCallback {
        void onSaved(long documentId);

        void onError(@NonNull Exception error);
    }

    public interface ActionCallback {
        void onComplete();

        void onError(@NonNull Exception error);
    }

    public interface DuplicateCallback {
        void onChecked(boolean duplicate);
    }

    public interface StatsCallback {
        void onLoaded(int total, int expiring, int expired);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final DocumentDao documentDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DocumentRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        documentDao = FamilyHubDatabase
                .getInstance(appContext)
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
            mainHandler.post(() -> callback.onDocumentsLoaded(documents));
        });
    }

    public void loadTrash(@NonNull DocumentsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<DocumentEntry> documents = documentDao.getTrash();
            mainHandler.post(() -> callback.onDocumentsLoaded(documents));
        });
    }

    public void loadStats(
            int reminderDays,
            @NonNull StatsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            long startOfToday = DocumentExpiryPolicy.startOfDay(now);
            long deadline = startOfToday
                    + TimeUnit.DAYS.toMillis(Math.max(1, reminderDays) + 1L)
                    - 1L;
            int total = documentDao.count();
            int expiring = documentDao.countExpiringBetween(
                    startOfToday,
                    deadline
            );
            int expired = documentDao.countExpired(startOfToday);
            mainHandler.post(() -> callback.onLoaded(
                    total,
                    expiring,
                    expired
            ));
        });
    }

    public void checkDuplicate(
            @NonNull String contentUri,
            long excludedDocumentId,
            @NonNull DuplicateCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            int count = excludedDocumentId <= 0L
                    ? documentDao.countByContentUri(contentUri)
                    : documentDao.countOtherByContentUri(
                            contentUri,
                            excludedDocumentId
                    );
            mainHandler.post(() -> callback.onChecked(count > 0));
        });
    }

    public void save(
            @NonNull DocumentEntry document,
            @NonNull SaveCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            try {
                if (document.createdAt == 0L) {
                    document.createdAt = System.currentTimeMillis();
                }
                document.updatedAt = System.currentTimeMillis();
                if (document.fingerprint.isEmpty() && !document.contentUri.isEmpty()) {
                    document.fingerprint = fingerprint(document.contentUri);
                }
                if (!document.fingerprint.isEmpty()
                        && documentDao.countOtherByFingerprint(
                        document.fingerprint, document.id) > 0) {
                    throw new IllegalStateException("Duplicate document content");
                }

                long documentId;
                if (document.id == 0L) {
                    documentId = documentDao.insert(document);
                    document.id = documentId;
                } else {
                    int updated = documentDao.update(document);
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "Document could not be updated"
                        );
                    }
                    documentId = document.id;
                }

                DocumentExpiryScheduler.sync(appContext);
                long savedId = documentId;
                mainHandler.post(() -> callback.onSaved(savedId));
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    public void delete(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            try {
                int deleted = documentDao.moveToTrash(
                        document.id, System.currentTimeMillis());
                if (deleted != 1) {
                    throw new IllegalStateException(
                            "Document could not be removed"
                    );
                }
                DocumentExpiryScheduler.sync(appContext);
                mainHandler.post(callback::onComplete);
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    public void restore(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            try {
                int restored = documentDao.restore(
                        document.id, System.currentTimeMillis());
                if (restored != 1) {
                    throw new IllegalStateException("Document could not be restored");
                }
                DocumentExpiryScheduler.sync(appContext);
                mainHandler.post(callback::onComplete);
            } catch (Exception error) {
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    @NonNull
    private String fingerprint(@NonNull String contentUri) {
        try (InputStream input = appContext.getContentResolver()
                .openInputStream(android.net.Uri.parse(contentUri))) {
            if (input == null) return "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) {
                value.append(String.format(java.util.Locale.ENGLISH, "%02x", part));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
