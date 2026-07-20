package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.FamilyMember;

import java.util.List;

@Dao
public interface FamilyMemberDao {

    @Query("SELECT * FROM family_members ORDER BY name COLLATE NOCASE ASC")
    List<FamilyMember> getAll();

    @Query("SELECT * FROM family_members "
            + "WHERE name COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR relation COLLATE NOCASE LIKE '%' || :query || '%' "
            + "ORDER BY name COLLATE NOCASE ASC")
    List<FamilyMember> search(String query);

    @Query("SELECT COUNT(*) FROM family_members")
    int count();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(FamilyMember member);

    @Update
    int update(FamilyMember member);

    @Delete
    int delete(FamilyMember member);
}
