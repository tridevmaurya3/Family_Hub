package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.feature.documents.DocumentExpiryPolicy;
import com.tridev.familyhub.feature.documents.DocumentExpiryScheduler;
import com.tridev.familyhub.feature.documents.DocumentCaptureStorage;

import java.util.List;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

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
        void onLoaded(int total, int expiring, int expired, int trash);
    }

    public interface ChecklistCallback {
        void onLoaded(@NonNull List<String> missingItems, boolean hasMembers);
    }

    public interface IntegrityCallback {
        void onLoaded(int verified, int changed, int missing, int notIndexed,
                      @NonNull List<String> issues);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final String scopeUid = com.tridev.familyhub.core.security.AccountDataScope.uid();
    private final FamilyHubDatabase accountDatabase;
    private boolean currentAccount() { return scopeUid.equals(com.tridev.familyhub.core.security.AccountDataScope.uid()); }
    private void postToAccount(Runnable callback) { mainHandler.post(() -> { if (currentAccount()) callback.run(); }); }
    private final DocumentDao documentDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DocumentRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        accountDatabase = FamilyHubDatabase.getForAccount(appContext,scopeUid);
        documentDao = accountDatabase.documentDao();
    }

    public void loadDocuments(
            @NonNull String query,
            @NonNull DocumentsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            String trimmedQuery = query.trim();
            List<DocumentEntry> documents = trimmedQuery.isEmpty()
                    ? documentDao.getAll()
                    : documentDao.search(trimmedQuery);
            postToAccount(() -> callback.onDocumentsLoaded(documents));
        });
    }

    public void loadTrash(@NonNull DocumentsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            List<DocumentEntry> documents = documentDao.getTrash();
            postToAccount(() -> callback.onDocumentsLoaded(documents));
        });
    }

    public void loadStats(
            int reminderDays,
            @NonNull StatsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
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
            int trash = documentDao.countTrash();
            postToAccount(() -> callback.onLoaded(
                    total,
                    expiring,
                    expired,
                    trash
            ));
        });
    }

    public void checkDuplicate(
            @NonNull String contentUri,
            long excludedDocumentId,
            @NonNull DuplicateCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            int count = excludedDocumentId <= 0L
                    ? documentDao.countByContentUri(contentUri)
                    : documentDao.countOtherByContentUri(
                            contentUri,
                            excludedDocumentId
                    );
            postToAccount(() -> callback.onChecked(count > 0));
        });
    }

    public void save(
            @NonNull DocumentEntry document,
            @NonNull SaveCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
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

                if (!currentAccount()) return;
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
                postToAccount(() -> callback.onSaved(savedId));
            } catch (Exception error) {
                postToAccount(() -> callback.onError(error));
            }
        });
    }

    public void renew(
            @NonNull DocumentEntry renewed,
            @NonNull DocumentEntry previous,
            @NonNull SaveCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            try {
                DocumentEntry version = copy(previous);
                version.id = 0L;
                version.linkedModule = "DOCUMENT_VERSION";
                if (version.fingerprint.isEmpty()) {
                    version.fingerprint = fingerprint(version.contentUri);
                }
                version.updatedAt = System.currentTimeMillis();
                renewed.fingerprint = fingerprint(renewed.contentUri);
                if (renewed.fingerprint.isEmpty()) {
                    throw new IllegalStateException("Renewed document file is unavailable");
                }
                if (documentDao.countOtherByFingerprint(
                        renewed.fingerprint, renewed.id) > 0) {
                    throw new IllegalStateException("Duplicate document content");
                }
                renewed.updatedAt = System.currentTimeMillis();
                if (!currentAccount()) return;
                documentDao.renewWithVersion(version, renewed);
                DocumentExpiryScheduler.sync(appContext);
                postToAccount(() -> callback.onSaved(renewed.id));
            } catch (Exception error) {
                postToAccount(() -> callback.onError(error));
            }
        });
    }

    public void loadVersionHistory(
            @NonNull DocumentEntry document,
            @NonNull DocumentsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            List<DocumentEntry> history = new ArrayList<>();
            DocumentEntry current = document;
            int guard = 0;
            while (current != null && guard++ < 50) {
                history.add(current);
                if (current.previousVersionId <= 0L) break;
                current = documentDao.getVersionById(current.previousVersionId);
            }
            postToAccount(() -> callback.onDocumentsLoaded(history));
        });
    }

    public void loadMissingChecklist(@NonNull ChecklistCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            List<FamilyMember> members = accountDatabase.familyMemberDao().getAll();
            List<DocumentEntry> documents = documentDao.getAll();
            List<String> missing = new ArrayList<>();
            for (FamilyMember member : members) {
                List<String> required = new ArrayList<>();
                required.add("Identity");
                required.add("Health");
                if (FamilyMember.ROLE_CHILD.equals(member.familyRole)) {
                    required.add("Education");
                } else {
                    required.add("Bank & Finance");
                }
                if (member.isGuardian) required.add("Property");
                for (String category : required) {
                    if (!hasDocument(documents, member.name, category)) {
                        missing.add(member.name + " — " + category);
                    }
                }
            }
            postToAccount(() -> callback.onLoaded(missing, !members.isEmpty()));
        });
    }

    public void verifyIntegrity(@NonNull IntegrityCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            List<DocumentEntry> documents = documentDao.getAll();
            List<String> issues = new ArrayList<>();
            int verified = 0, changed = 0, missing = 0, notIndexed = 0;
            for (DocumentEntry document : documents) {
                String actual = fingerprint(document.contentUri);
                if (actual.isEmpty()) {
                    missing++;
                    issues.add(document.title + " — file unavailable");
                } else if (document.fingerprint.isEmpty()) {
                    notIndexed++;
                    issues.add(document.title + " — fingerprint not indexed");
                } else if (!actual.equalsIgnoreCase(document.fingerprint)) {
                    changed++;
                    issues.add(document.title + " — content changed");
                } else {
                    verified++;
                }
            }
            int safeVerified = verified, safeChanged = changed;
            int safeMissing = missing, safeNotIndexed = notIndexed;
            postToAccount(() -> callback.onLoaded(safeVerified, safeChanged,
                    safeMissing, safeNotIndexed, issues));
        });
    }

    private static boolean hasDocument(
            @NonNull List<DocumentEntry> documents,
            @NonNull String memberName,
            @NonNull String category
    ) {
        for (DocumentEntry document : documents) {
            if (document.memberName.trim().equalsIgnoreCase(memberName.trim())
                    && document.category.trim().equalsIgnoreCase(category)) return true;
        }
        return false;
    }

    @NonNull
    private static DocumentEntry copy(@NonNull DocumentEntry source) {
        DocumentEntry copy = new DocumentEntry();
        copy.title = source.title; copy.category = source.category;
        copy.contentUri = source.contentUri; copy.mimeType = source.mimeType;
        copy.documentNumber = source.documentNumber; copy.issuer = source.issuer;
        copy.memberName = source.memberName; copy.tags = source.tags;
        copy.notes = source.notes; copy.searchableText = source.searchableText;
        copy.fingerprint = source.fingerprint; copy.linkedModule = source.linkedModule;
        copy.emergency = source.emergency; copy.issuedAt = source.issuedAt;
        copy.expiryAt = source.expiryAt; copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt; copy.deletedAt = source.deletedAt;
        copy.previousVersionId = source.previousVersionId;
        return copy;
    }

    public void delete(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            try {
                int deleted = documentDao.moveToTrash(
                        document.id, System.currentTimeMillis());
                if (deleted != 1) {
                    throw new IllegalStateException(
                            "Document could not be removed"
                    );
                }
                DocumentExpiryScheduler.sync(appContext);
                postToAccount(callback::onComplete);
            } catch (Exception error) {
                postToAccount(() -> callback.onError(error));
            }
        });
    }

    public void restore(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            try {
                int restored = documentDao.restore(
                        document.id, System.currentTimeMillis());
                if (restored != 1) {
                    throw new IllegalStateException("Document could not be restored");
                }
                DocumentExpiryScheduler.sync(appContext);
                postToAccount(callback::onComplete);
            } catch (Exception error) {
                postToAccount(() -> callback.onError(error));
            }
        });
    }

    public void permanentlyDelete(
            @NonNull DocumentEntry document,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (!currentAccount()) return;
            try {
                int deleted = documentDao.delete(document);
                if (deleted != 1) {
                    throw new IllegalStateException("Document could not be permanently deleted");
                }
                DocumentCaptureStorage.deleteIfOwned(appContext, document.contentUri);
                postToAccount(callback::onComplete);
            } catch (Exception error) {
                postToAccount(() -> callback.onError(error));
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
