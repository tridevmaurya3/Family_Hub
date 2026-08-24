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
            + "OR assignedMemberName LIKE '%' || :query || '%' "
            + "ORDER BY isPurchased ASC, "
            + "CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, "
            + "createdAt DESC")
    List<GroceryItem> search(String query);

    @Query("SELECT * FROM grocery_items WHERE isPurchased = 0 "
            + "ORDER BY category COLLATE NOCASE, CASE priority WHEN 'URGENT' THEN 0 "
            + "WHEN 'HIGH' THEN 1 ELSE 2 END, createdAt DESC")
    List<GroceryItem> getPendingForWidget();

    @Query("SELECT * FROM grocery_items WHERE id = :itemId LIMIT 1")
    GroceryItem getById(long itemId);

    @Query("SELECT * FROM grocery_items WHERE cloudId = :cloudId LIMIT 1")
    GroceryItem getByCloudId(String cloudId);

    @Query("SELECT * FROM grocery_items WHERE financeEntryId = :financeEntryId LIMIT 1")
    GroceryItem getByFinanceEntryId(long financeEntryId);

    @Query("SELECT * FROM grocery_items WHERE name = :name COLLATE NOCASE LIMIT 1")
    GroceryItem findDuplicate(String name);

    @Query("SELECT * FROM grocery_items WHERE name = :name COLLATE NOCASE "
            + "AND listType IN ('MONTHLY','TWO_MONTH','THREE_MONTH') "
            + "ORDER BY isMonthlyMaster DESC, createdAt ASC LIMIT 1")
    GroceryItem findRecurringMaster(String name);

    @Query("SELECT * FROM grocery_items WHERE name = :name COLLATE NOCASE "
            + "AND actualCost > 0 AND priceLocationKey = :locationKey "
            + "ORDER BY purchasedAt DESC LIMIT 1")
    GroceryItem findLocalPrice(String name, String locationKey);

    @Query("SELECT * FROM grocery_items WHERE name = :name COLLATE NOCASE "
            + "AND actualCost > 0 ORDER BY purchasedAt DESC LIMIT 1")
    GroceryItem findAnyPrice(String name);

    @Query("SELECT * FROM grocery_items WHERE purchaseCount >= 2 "
            + "ORDER BY purchaseCount DESC, purchasedAt DESC LIMIT :limit")
    List<GroceryItem> getRecurringSuggestions(int limit);

    @Query("SELECT * FROM grocery_items WHERE cloudId != ''")
    List<GroceryItem> getAllSynced();

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

    @Query("DELETE FROM grocery_items WHERE cloudId = :cloudId")
    int deleteByCloudId(String cloudId);

    @Query("DELETE FROM grocery_items WHERE isPurchased = 1")
    int deletePurchased();

    @Query("UPDATE grocery_items SET actualCost = :amount, updatedAt = :updatedAt "
            + "WHERE financeEntryId = :financeEntryId")
    int updateLinkedFinanceAmount(long financeEntryId, double amount, long updatedAt);

    @Query("UPDATE grocery_items SET financeEntryId = 0, isPurchased = 0, "
            + "purchasedAt = 0, buyingStatus = 'PENDING', purchasedByName = '', "
            + "updatedAt = :updatedAt WHERE financeEntryId = :financeEntryId")
    int resetLinkedPurchase(long financeEntryId, long updatedAt);
}
