package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.tridev.familyhub.data.local.entity.PendingLocationUpload;

/**
 * Stores only the newest encrypted Family Live update waiting for sync.
 *
 * Firebase stores the member's current state, not a route-history stream.
 * Keeping older failed points could replay stale coordinates over a newer
 * position. Every insert therefore replaces the previous pending point.
 */
@Dao
public interface PendingLocationUploadDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insertInternal(PendingLocationUpload upload);

    /**
     * Existing callers use insert(); this transaction transparently compacts
     * the queue to the newest point without exposing encrypted coordinates.
     */
    @Transaction
    default long insert(PendingLocationUpload upload) {
        deleteAll();
        return insertInternal(upload);
    }

    /**
     * Foreground-service replay is deliberately disabled. Durable replay is
     * handled by PendingLocationSyncWorker, which checks the remote timestamp
     * before writing and therefore cannot overwrite a newer location.
     */
    default PendingLocationUpload getNextReady(long now) {
        return null;
    }

    @Query("SELECT * FROM pending_location_uploads "
            + "ORDER BY createdAt DESC, id DESC LIMIT 1")
    PendingLocationUpload getLatest();

    @Query("SELECT COUNT(*) FROM pending_location_uploads WHERE id = :id")
    int existsById(long id);

    @Query("DELETE FROM pending_location_uploads WHERE id = :id")
    int deleteById(long id);

    @Query("DELETE FROM pending_location_uploads")
    int deleteAll();

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

    @Transaction
    default long replaceWithLatest(PendingLocationUpload upload) {
        return insert(upload);
    }
}
