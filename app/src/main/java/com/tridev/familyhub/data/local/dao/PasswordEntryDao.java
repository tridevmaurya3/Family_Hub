package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.tridev.familyhub.data.local.entity.PasswordEntry;
import java.util.List;

@Dao
public interface PasswordEntryDao {
    @Query("SELECT * FROM password_entries ORDER BY title COLLATE NOCASE") List<PasswordEntry> getAll();
    @Query("SELECT * FROM password_entries WHERE title LIKE '%' || :query || '%' OR website LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE") List<PasswordEntry> search(String query);
    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(PasswordEntry entry);
    @Update int update(PasswordEntry entry);
    @Delete int delete(PasswordEntry entry);
}
