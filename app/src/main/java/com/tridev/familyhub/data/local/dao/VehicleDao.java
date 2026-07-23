package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.VehicleWithOwner;

import java.util.List;

@Dao
public interface VehicleDao {

    @Query("SELECT vehicles.*, family_members.name AS ownerName "
            + "FROM vehicles INNER JOIN family_members "
            + "ON family_members.id = vehicles.ownerMemberId "
            + "ORDER BY vehicles.displayName COLLATE NOCASE")
    List<VehicleWithOwner> getAllWithOwner();

    @Query("SELECT vehicles.*, family_members.name AS ownerName "
            + "FROM vehicles INNER JOIN family_members "
            + "ON family_members.id = vehicles.ownerMemberId "
            + "WHERE vehicles.displayName LIKE '%' || :query || '%' "
            + "OR vehicles.registrationNumber LIKE '%' || :query || '%' "
            + "OR vehicles.manufacturer LIKE '%' || :query || '%' "
            + "OR vehicles.model LIKE '%' || :query || '%' "
            + "OR family_members.name LIKE '%' || :query || '%' "
            + "ORDER BY vehicles.displayName COLLATE NOCASE")
    List<VehicleWithOwner> searchWithOwner(String query);

    @Query("SELECT COUNT(*) FROM vehicles")
    int count();

    @Query("SELECT COUNT(*) FROM vehicles WHERE "
            + "(insuranceExpiryAt > 0 AND insuranceExpiryAt <= :deadline) OR "
            + "(pollutionExpiryAt > 0 AND pollutionExpiryAt <= :deadline) OR "
            + "(serviceDueAt > 0 AND serviceDueAt <= :deadline)")
    int countDueBy(long deadline);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(Vehicle vehicle);

    @Update
    int update(Vehicle vehicle);

    @Delete
    int delete(Vehicle vehicle);
}
