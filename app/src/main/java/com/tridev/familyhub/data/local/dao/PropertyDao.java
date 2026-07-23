package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;

import java.util.List;

@Dao
public interface PropertyDao {

    @Query("SELECT properties.*, family_members.name AS ownerName "
            + "FROM properties INNER JOIN family_members "
            + "ON family_members.id = properties.ownerMemberId "
            + "ORDER BY properties.title COLLATE NOCASE")
    List<PropertyWithOwner> getAllWithOwner();

    @Query("SELECT properties.*, family_members.name AS ownerName "
            + "FROM properties INNER JOIN family_members "
            + "ON family_members.id = properties.ownerMemberId "
            + "WHERE properties.title LIKE '%' || :query || '%' "
            + "OR properties.propertyType LIKE '%' || :query || '%' "
            + "OR properties.address LIKE '%' || :query || '%' "
            + "OR properties.city LIKE '%' || :query || '%' "
            + "OR family_members.name LIKE '%' || :query || '%' "
            + "ORDER BY properties.title COLLATE NOCASE")
    List<PropertyWithOwner> searchWithOwner(String query);

    @Query("SELECT COUNT(*) FROM properties")
    int count();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(PropertyEntry property);

    @Update
    int update(PropertyEntry property);

    @Delete
    int delete(PropertyEntry property);
}
