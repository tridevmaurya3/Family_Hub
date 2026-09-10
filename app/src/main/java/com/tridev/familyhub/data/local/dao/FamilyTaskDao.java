package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.FamilyTask;
import java.util.List;

@Dao
public interface FamilyTaskDao {
    @Query("SELECT * FROM family_tasks WHERE id = :id LIMIT 1")
    FamilyTask getById(long id);

    @Query("SELECT * FROM family_tasks WHERE cloudId = :cloudId LIMIT 1")
    FamilyTask getByCloudId(String cloudId);

    @Query("SELECT * FROM family_tasks ORDER BY status = 'COMPLETED', dueAt ASC, updatedAt DESC")
    List<FamilyTask> getAll();

    @Query("SELECT * FROM family_tasks WHERE (title LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' OR assignedMemberName LIKE '%' || :query || '%') ORDER BY status = 'COMPLETED', dueAt ASC, updatedAt DESC")
    List<FamilyTask> search(String query);

    @Query("SELECT * FROM family_tasks WHERE shared = 1 AND familyId = ''")
    List<FamilyTask> getPendingShared();

    @Query("SELECT * FROM family_tasks WHERE reminderEnabled = 1 AND status = 'PENDING'")
    List<FamilyTask> getReminderEnabled();

    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(FamilyTask task);
    @Update int update(FamilyTask task);
    @Delete int delete(FamilyTask task);
}
