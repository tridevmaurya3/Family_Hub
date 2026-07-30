package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.tridev.familyhub.data.local.entity.SafePlaceAlert;

import java.util.List;

@Dao
public interface SafePlaceAlertDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(SafePlaceAlert alert);

    @Query("SELECT * FROM safe_place_alerts ORDER BY occurredAt DESC")
    List<SafePlaceAlert> getAll();

    @Query("SELECT COUNT(*) FROM safe_place_alerts WHERE isRead = 0")
    int unreadCount();

    @Query("UPDATE safe_place_alerts SET isRead = 1 WHERE id = :id")
    int markRead(long id);

    @Query("UPDATE safe_place_alerts SET isRead = 1 WHERE isRead = 0")
    int markAllRead();
}
