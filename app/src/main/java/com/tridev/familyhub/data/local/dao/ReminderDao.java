package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.Reminder;

import java.util.List;

@Dao
public interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY isEnabled DESC, reminderAt ASC, id DESC")
    List<Reminder> getAll();

    @Query("SELECT * FROM reminders "
            + "WHERE title COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR note COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR repeatType COLLATE NOCASE LIKE '%' || :query || '%' "
            + "ORDER BY isEnabled DESC, reminderAt ASC, id DESC")
    List<Reminder> search(String query);

    @Query("SELECT * FROM reminders WHERE isEnabled = 1")
    List<Reminder> getEnabled();

    @Query("SELECT * FROM reminders WHERE id = :reminderId LIMIT 1")
    Reminder getById(long reminderId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(Reminder reminder);

    @Update
    int update(Reminder reminder);

    @Delete
    int delete(Reminder reminder);
}
