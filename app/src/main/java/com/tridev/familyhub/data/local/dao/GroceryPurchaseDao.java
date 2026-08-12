package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import java.util.List;

@Dao
public interface GroceryPurchaseDao {
    @Insert long insert(GroceryPurchase purchase);
    @Query("SELECT * FROM grocery_purchases WHERE purchasedAt >= :from AND purchasedAt < :to "
            + "ORDER BY category COLLATE NOCASE, itemName COLLATE NOCASE, purchasedAt")
    List<GroceryPurchase> getForPeriod(long from, long to);

    @Query("SELECT * FROM grocery_purchases WHERE itemName = :itemName COLLATE NOCASE "
            + "ORDER BY purchasedAt DESC LIMIT 1")
    GroceryPurchase getLatestForItem(String itemName);

    @Query("SELECT * FROM grocery_purchases WHERE itemName = :itemName COLLATE NOCASE "
            + "AND actualCost > 0 AND storeName <> '' "
            + "AND (:quantity = '' OR quantity = :quantity COLLATE NOCASE) "
            + "ORDER BY actualCost ASC, purchasedAt DESC LIMIT 1")
    GroceryPurchase getCheapestStoreForItem(String itemName, String quantity);

    @Query("DELETE FROM grocery_purchases WHERE sourceItemId = :sourceItemId "
            + "AND purchasedAt = :purchasedAt")
    int deletePurchase(long sourceItemId, long purchasedAt);
}
