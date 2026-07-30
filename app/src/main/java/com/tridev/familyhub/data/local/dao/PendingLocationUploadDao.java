package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.tridev.familyhub.data.local.entity.PendingLocationUpload;

@Dao
public interface PendingLocationUploadDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(PendingLocationUpload upload);

    @Query("SELECT * FROM pending_location_uploads "
            + "WHERE nextAttemptAt <= :now "
            + "ORDER BY createdAt ASC, id ASC LIMIT 1")
    PendingLocationUpload getNextReady(long now);

    @Query("DELETE FROM pending_location_uploads WHERE id = :id")
    int deleteById(long id);

    @Query("UPDATE pending_location_uploads SET "
            + "attemptCount = attemptCount + 1, "
            + "nextAttemptAt = :nextAttemptAt WHERE id = :id")
    int markRetry(long id, long nextAttemptAt);

    @Query("DELETE FROM pending_location_uploads WHERE id NOT IN "
            + "(SELECT id FROM pending_location_uploads "
            + "ORDER BY createdAt DESC, id DESC LIMIT :maximumRows)")
    int trimToLatest(int maximumRows);

    @Query("SELECT COUNT(*) FROM pending_location_uploads")
    int count();
}
