package com.tridev.familyhub.data.local.dao;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Transaction;

import com.tridev.familyhub.data.local.entity.DocumentEntry;

import java.util.List;

@Dao
public interface DocumentDao {

    @Query("SELECT * FROM documents WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' ORDER BY updatedAt DESC, createdAt DESC")
    @NonNull
    List<DocumentEntry> getAll();

    @Query("SELECT * FROM documents "
            + "WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' AND (title LIKE '%' || :query || '%' "
            + "OR category LIKE '%' || :query || '%' "
            + "OR documentNumber LIKE '%' || :query || '%' "
            + "OR issuer LIKE '%' || :query || '%' "
            + "OR memberName LIKE '%' || :query || '%' "
            + "OR tags LIKE '%' || :query || '%' "
            + "OR notes LIKE '%' || :query || '%' "
            + "OR linkedModule LIKE '%' || :query || '%' "
            + "OR searchableText LIKE '%' || :query || '%') "
            + "ORDER BY updatedAt DESC, createdAt DESC")
    @NonNull
    List<DocumentEntry> search(@NonNull String query);

    @Query("SELECT * FROM documents "
            + "WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' AND expiryAt > 0 "
            + "ORDER BY expiryAt ASC")
    @NonNull
    List<DocumentEntry> getExpiryCandidates();

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    DocumentEntry getById(long documentId);

    @Query("SELECT * FROM documents WHERE deletedAt = 0 "
            + "AND linkedModule != 'DOCUMENT_VERSION' "
            + "AND title = :title COLLATE NOCASE "
            + "ORDER BY updatedAt DESC, id DESC LIMIT 1")
    DocumentEntry getActiveByTitle(@NonNull String title);

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION'")
    int count();

    /** Compatibility query used by the Dashboard's upcoming-expiry summary. */
    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' AND expiryAt > 0 AND expiryAt <= :deadline")
    int countExpiringBy(long deadline);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' AND expiryAt > 0 AND expiryAt < :startOfToday")
    int countExpired(long startOfToday);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND linkedModule != 'DOCUMENT_VERSION' AND expiryAt >= :startOfToday AND expiryAt <= :deadline")
    int countExpiringBetween(long startOfToday, long deadline);

    @Query("SELECT COUNT(*) FROM documents WHERE contentUri = :contentUri")
    int countByContentUri(@NonNull String contentUri);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE contentUri = :contentUri AND id != :excludedId")
    int countOtherByContentUri(
            @NonNull String contentUri,
            long excludedId
    );

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt = 0 AND fingerprint = :fingerprint AND fingerprint != '' AND id != :excludedId")
    int countOtherByFingerprint(@NonNull String fingerprint, long excludedId);

    @Query("SELECT * FROM documents WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    @NonNull
    List<DocumentEntry> getTrash();

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt > 0")
    int countTrash();

    @Query("UPDATE documents SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :documentId")
    int moveToTrash(long documentId, long deletedAt);

    @Query("UPDATE documents SET deletedAt = 0, updatedAt = :updatedAt WHERE id = :documentId")
    int restore(long documentId, long updatedAt);

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    DocumentEntry getVersionById(long documentId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(@NonNull DocumentEntry entry);

    @Update
    int update(@NonNull DocumentEntry entry);

    @Delete
    int delete(@NonNull DocumentEntry entry);

    @Transaction
    default long renewWithVersion(
            @NonNull DocumentEntry previousVersion,
            @NonNull DocumentEntry renewed
    ) {
        long versionId = insert(previousVersion);
        renewed.previousVersionId = versionId;
        if (update(renewed) != 1) {
            throw new IllegalStateException("Renewal could not be saved");
        }
        return versionId;
    }
}
