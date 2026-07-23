package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.List;

@Dao
public interface GroceryItemDao {

    @Query("SELECT * FROM grocery_items "
            + "ORDER BY isPurchased ASC, "
            + "CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, "
            + "createdAt DESC")
    List<GroceryItem> getAll();

    @Query("SELECT * FROM grocery_items "
            + "WHERE name LIKE '%' || :query || '%' "
            + "OR category LIKE '%' || :query || '%' "
            + "OR notes LIKE '%' || :query || '%' "
            + "ORDER BY isPurchased ASC, "
            + "CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, "
            + "createdAt DESC")
    List<GroceryItem> search(String query);

    @Query("SELECT COUNT(*) FROM grocery_items WHERE isPurchased = 0")
    int countPending();

    @Query("SELECT COUNT(*) FROM grocery_items WHERE isPurchased = 1")
    int countPurchased();

    @Query("SELECT COALESCE(SUM(estimatedCost), 0) "
            + "FROM grocery_items WHERE isPurchased = 0")
    double pendingEstimatedCost();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(GroceryItem item);

    @Update
    int update(GroceryItem item);

    @Delete
    int delete(GroceryItem item);

    @Query("DELETE FROM grocery_items WHERE isPurchased = 1")
    int deletePurchased();
}
