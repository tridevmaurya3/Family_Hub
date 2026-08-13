package com.tridev.familyhub.data.local.dao;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.DocumentEntry;

import java.util.List;

@Dao
public interface DocumentDao {

    @Query("SELECT * FROM documents WHERE deletedAt = 0 ORDER BY updatedAt DESC, createdAt DESC")
    @NonNull
    List<DocumentEntry> getAll();

    @Query("SELECT * FROM documents "
            + "WHERE deletedAt = 0 AND (title LIKE '%' || :query || '%' "
            + "OR category LIKE '%' || :query || '%' "
            + "OR documentNumber LIKE '%' || :query || '%' "
            + "OR issuer LIKE '%' || :query || '%' "
            + "OR memberName LIKE '%' || :query || '%' "
            + "OR tags LIKE '%' || :query || '%' "
            + "OR notes LIKE '%' || :query || '%' "
            + "OR searchableText LIKE '%' || :query || '%') "
            + "ORDER BY updatedAt DESC, createdAt DESC")
    @NonNull
    List<DocumentEntry> search(@NonNull String query);

    @Query("SELECT * FROM documents "
            + "WHERE deletedAt = 0 AND expiryAt > 0 "
            + "ORDER BY expiryAt ASC")
    @NonNull
    List<DocumentEntry> getExpiryCandidates();

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    DocumentEntry getById(long documentId);

    @Query("SELECT COUNT(*) FROM documents WHERE deletedAt = 0")
    int count();

    /** Compatibility query used by the Dashboard's upcoming-expiry summary. */
    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND expiryAt > 0 AND expiryAt <= :deadline")
    int countExpiringBy(long deadline);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND expiryAt > 0 AND expiryAt < :startOfToday")
    int countExpired(long startOfToday);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE deletedAt = 0 AND expiryAt >= :startOfToday AND expiryAt <= :deadline")
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(@NonNull DocumentEntry entry);

    @Update
    int update(@NonNull DocumentEntry entry);

    @Delete
    int delete(@NonNull DocumentEntry entry);
}
