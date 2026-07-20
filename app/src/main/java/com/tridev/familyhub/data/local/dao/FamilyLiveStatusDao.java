package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.tridev.familyhub.data.local.entity.FamilyLiveStatus;

import java.util.List;

@Dao
public interface FamilyLiveStatusDao {

    @Query("SELECT * FROM family_live_status "
            + "ORDER BY isLocationSharingEnabled DESC, lastUpdatedAt DESC")
    List<FamilyLiveStatus> getAll();

    @Query("SELECT * FROM family_live_status "
            + "WHERE isLocationSharingEnabled = 1 "
            + "ORDER BY lastUpdatedAt DESC")
    List<FamilyLiveStatus> getSharingEnabled();

    @Query("SELECT * FROM family_live_status "
            + "WHERE familyMemberId = :familyMemberId LIMIT 1")
    FamilyLiveStatus getByMemberId(long familyMemberId);

    /**
     * Because familyMemberId is the primary key, REPLACE updates the latest
     * snapshot for the same family member.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void save(FamilyLiveStatus status);

    @Query("UPDATE family_live_status "
            + "SET isLocationSharingEnabled = :enabled, "
            + "lastUpdatedAt = :updatedAt "
            + "WHERE familyMemberId = :familyMemberId")
    int updateSharingStatus(
            long familyMemberId,
            boolean enabled,
            long updatedAt
    );

    @Query("DELETE FROM family_live_status WHERE familyMemberId = :familyMemberId")
    int deleteByMemberId(long familyMemberId);

    @Query("DELETE FROM family_live_status")
    int deleteAll();
}