package com.tridev.familyhub.data.local.dao;

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
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") List<DocumentEntry> getAll();
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY createdAt DESC") List<DocumentEntry> search(String query);
    @Query("SELECT COUNT(*) FROM documents") int count();
    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(DocumentEntry entry);
    @Update int update(DocumentEntry entry);
    @Delete int delete(DocumentEntry entry);
}
