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
            + "OR phone LIKE '%' || :query || '%' "
            + "OR bloodGroup COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR address COLLATE NOCASE LIKE '%' || :query || '%' "
            + "ORDER BY name COLLATE NOCASE ASC")
    List<FamilyMember> search(String query);

    @Query("SELECT * FROM family_members "
            + "WHERE ownerFamilyId = :familyId "
            + "ORDER BY name COLLATE NOCASE ASC")
    List<FamilyMember> getForFamily(String familyId);

    @Query("SELECT * FROM family_members "
            + "WHERE ownerFamilyId = :familyId AND ("
            + "name COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR relation COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR phone LIKE '%' || :query || '%' "
            + "OR email COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR bloodGroup COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR address COLLATE NOCASE LIKE '%' || :query || '%') "
            + "ORDER BY name COLLATE NOCASE ASC")
    List<FamilyMember> searchForFamily(String familyId, String query);

    @Query("SELECT * FROM family_members WHERE ownerFamilyId = ''")
    List<FamilyMember> getUnscoped();

    @Query("SELECT * FROM family_members "
            + "WHERE cloudProfileId = :cloudProfileId LIMIT 1")
    FamilyMember getByCloudProfileId(String cloudProfileId);

    @Query("SELECT * FROM family_members WHERE name = :name COLLATE NOCASE LIMIT 1")
    FamilyMember getByName(String name);

    @Query("SELECT COUNT(*) FROM family_members")
    int count();

    @Query("SELECT COUNT(*) FROM family_members WHERE isGuardian = 1")
    int countGuardians();

    @Query("SELECT COUNT(*) FROM family_members "
            + "WHERE gender = :gender COLLATE NOCASE")
    int countByGender(String gender);

    @Query("SELECT COUNT(*) FROM family_members WHERE familyRole = :role")
    int countByRole(String role);

    @Query("SELECT COUNT(*) FROM family_members "
            + "WHERE phone != '' AND phone = :phone AND id != :memberId")
    int countOtherMembersWithPhone(String phone, long memberId);

    @Query("SELECT COUNT(*) FROM family_members "
            + "WHERE email != '' AND email = :email COLLATE NOCASE "
            + "AND id != :memberId")
    int countOtherMembersWithEmail(String email, long memberId);

    @Query("UPDATE family_members SET syncPending = 0 "
            + "WHERE cloudProfileId = :cloudProfileId")
    int markSynced(String cloudProfileId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(FamilyMember member);

    @Update
    int update(FamilyMember member);

    @Delete
    int delete(FamilyMember member);
}
