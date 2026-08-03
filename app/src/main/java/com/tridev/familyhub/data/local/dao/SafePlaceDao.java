package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.List;

@Dao
public interface SafePlaceDao {
    @Query("SELECT * FROM safe_places ORDER BY name COLLATE NOCASE")
    List<SafePlace> getAll();

    @Query("SELECT * FROM safe_places WHERE id = :id LIMIT 1")
    SafePlace getById(long id);

    @Query("SELECT * FROM safe_places WHERE alertsEnabled = 1 LIMIT 100")
    List<SafePlace> getEnabled();

    @Query("SELECT COUNT(*) FROM safe_places "
            + "WHERE alertsEnabled = 1 AND id != :excludedId")
    int enabledCountExcluding(long excludedId);

    @Query("UPDATE safe_places SET alertsEnabled = :enabled, "
            + "updatedAt = :updatedAt WHERE id = :id")
    int updateAlertsEnabled(long id, boolean enabled, long updatedAt);

    @Query("SELECT COUNT(*) FROM safe_places WHERE name = :name AND "
            + "ABS(latitude - :latitude) < 0.0001 AND "
            + "ABS(longitude - :longitude) < 0.0001 AND id != :excludedId")
    int duplicateCount(String name, double latitude, double longitude,
                       long excludedId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(SafePlace place);

    @Update int update(SafePlace place);

    @Delete int delete(SafePlace place);
}
