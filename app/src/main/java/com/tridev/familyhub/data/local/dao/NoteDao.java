package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.NoteEntry;

import java.util.List;

@Dao
public interface NoteDao {

    @Query("SELECT * FROM notes WHERE isArchived = 0 "
            + "ORDER BY isPinned DESC, updatedAt DESC")
    List<NoteEntry> getActive();

    @Query("SELECT * FROM notes WHERE isArchived = 1 "
            + "ORDER BY updatedAt DESC")
    List<NoteEntry> getArchived();

    @Query("SELECT * FROM notes WHERE isArchived = 0 "
            + "AND (title LIKE '%' || :query || '%' "
            + "OR content LIKE '%' || :query || '%' "
            + "OR category LIKE '%' || :query || '%') "
            + "ORDER BY isPinned DESC, updatedAt DESC")
    List<NoteEntry> searchActive(String query);

    @Query("SELECT COUNT(*) FROM notes WHERE isArchived = 0")
    int countActive();

    @Query("SELECT COUNT(*) FROM notes WHERE isArchived = 0 AND isPinned = 1")
    int countPinned();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(NoteEntry note);

    @Update
    int update(NoteEntry note);

    @Delete
    int delete(NoteEntry note);
}
