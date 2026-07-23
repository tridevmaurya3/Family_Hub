package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.PlannerItem;

import java.util.List;

@Dao
public interface PlannerItemDao {

    @Query("SELECT * FROM planner_items "
            + "ORDER BY isCompleted ASC, startAt ASC")
    List<PlannerItem> getAll();

    @Query("SELECT * FROM planner_items "
            + "WHERE startAt >= :rangeStart AND startAt < :rangeEnd "
            + "ORDER BY isCompleted ASC, startAt ASC")
    List<PlannerItem> getInRange(long rangeStart, long rangeEnd);

    @Query("SELECT * FROM planner_items "
            + "WHERE isCompleted = 0 AND startAt >= :now "
            + "ORDER BY startAt ASC LIMIT :limit")
    List<PlannerItem> getUpcoming(long now, int limit);

    @Query("SELECT * FROM planner_items "
            + "WHERE title LIKE '%' || :query || '%' "
            + "OR notes LIKE '%' || :query || '%' "
            + "OR location LIKE '%' || :query || '%' "
            + "ORDER BY isCompleted ASC, startAt ASC")
    List<PlannerItem> search(String query);

    @Query("SELECT COUNT(*) FROM planner_items "
            + "WHERE isCompleted = 0 AND startAt >= :now")
    int countUpcoming(long now);

    @Query("SELECT COUNT(*) FROM planner_items WHERE isCompleted = 0")
    int countOpen();

    @Query("SELECT COUNT(*) FROM planner_items WHERE isCompleted = 1")
    int countCompleted();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(PlannerItem item);

    @Update
    int update(PlannerItem item);

    @Delete
    int delete(PlannerItem item);
}
