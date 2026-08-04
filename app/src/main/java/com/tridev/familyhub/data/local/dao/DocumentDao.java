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

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    @NonNull
    List<DocumentEntry> getAll();

    @Query("SELECT * FROM documents "
            + "WHERE title LIKE '%' || :query || '%' "
            + "OR category LIKE '%' || :query || '%' "
            + "ORDER BY createdAt DESC")
    @NonNull
    List<DocumentEntry> search(@NonNull String query);

    @Query("SELECT * FROM documents "
            + "WHERE expiryAt > 0 "
            + "ORDER BY expiryAt ASC")
    @NonNull
    List<DocumentEntry> getExpiryCandidates();

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    DocumentEntry getById(long documentId);

    @Query("SELECT COUNT(*) FROM documents")
    int count();

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE expiryAt > 0 AND expiryAt < :startOfToday")
    int countExpired(long startOfToday);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE expiryAt >= :startOfToday AND expiryAt <= :deadline")
    int countExpiringBetween(long startOfToday, long deadline);

    @Query("SELECT COUNT(*) FROM documents WHERE contentUri = :contentUri")
    int countByContentUri(@NonNull String contentUri);

    @Query("SELECT COUNT(*) FROM documents "
            + "WHERE contentUri = :contentUri AND id != :excludedId")
    int countOtherByContentUri(
            @NonNull String contentUri,
            long excludedId
    );

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(@NonNull DocumentEntry entry);

    @Update
    int update(@NonNull DocumentEntry entry);

    @Delete
    int delete(@NonNull DocumentEntry entry);
}
