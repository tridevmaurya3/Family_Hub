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
 * Family Live writes a current-state node in Firebase rather than a route
 * history. Keeping old failed points would therefore waste storage and could
 * replay stale coordinates over a newer position. replaceWithLatest() makes
 * the queue compact and deterministic.
 */
@Dao
public interface PendingLocationUploadDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(PendingLocationUpload upload);

    @Query("SELECT * FROM pending_location_uploads "
            + "WHERE nextAttemptAt <= :now "
            + "ORDER BY createdAt DESC, id DESC LIMIT 1")
    PendingLocationUpload getLatestReady(long now);

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

    /**
     * Atomically replaces every older failed point with the newest point.
     * The encrypted payload remains private while duplicate/stale uploads are
     * prevented without needing coordinate columns in Room.
     */
    @Transaction
    default long replaceWithLatest(PendingLocationUpload upload) {
        deleteAll();
        return insert(upload);
    }
}
