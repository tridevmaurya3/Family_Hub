package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.HealthRecordWithMember;

import java.util.List;

@Dao
public interface HealthRecordDao {

    @Query("SELECT health_records.*, family_members.name AS memberName "
            + "FROM health_records "
            + "INNER JOIN family_members "
            + "ON family_members.id = health_records.familyMemberId "
            + "ORDER BY health_records.recordedAt DESC, health_records.id DESC")
    List<HealthRecordWithMember> getAllWithMember();

    @Query("SELECT health_records.*, family_members.name AS memberName "
            + "FROM health_records "
            + "INNER JOIN family_members "
            + "ON family_members.id = health_records.familyMemberId "
            + "WHERE health_records.title LIKE '%' || :query || '%' "
            + "OR health_records.recordType LIKE '%' || :query || '%' "
            + "OR health_records.value LIKE '%' || :query || '%' "
            + "OR family_members.name LIKE '%' || :query || '%' "
            + "ORDER BY health_records.recordedAt DESC, health_records.id DESC")
    List<HealthRecordWithMember> searchWithMember(String query);

    @Query("SELECT COUNT(*) FROM health_records")
    int count();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(HealthRecord record);

    @Update
    int update(HealthRecord record);

    @Delete
    int delete(HealthRecord record);
}
