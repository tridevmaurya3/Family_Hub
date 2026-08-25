package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import java.util.List;

@Dao
public interface GroceryPurchaseDao {
    @Insert long insert(GroceryPurchase purchase);

    @Query("SELECT * FROM grocery_purchases ORDER BY purchasedAt DESC, id DESC")
    List<GroceryPurchase> getAll();

    @Query("UPDATE grocery_purchases SET itemName = :itemName, category = :category, "
            + "quantity = :quantity, storeName = :storeName, actualCost = :actualCost, "
            + "purchasedAt = :purchasedAt WHERE id = :purchaseId")
    int updateRecovered(long purchaseId, String itemName, String category,
                        String quantity, String storeName, double actualCost,
                        long purchasedAt);

    @Query("UPDATE grocery_purchases SET purchasedAt = :newPurchasedAt "
            + "WHERE id = (SELECT id FROM grocery_purchases "
            + "WHERE itemName = :itemName COLLATE NOCASE AND purchasedAt = :oldPurchasedAt "
            + "ORDER BY id DESC LIMIT 1)")
    int updateMatchingPurchaseDate(String itemName, long oldPurchasedAt,
                                   long newPurchasedAt);

    @Query("DELETE FROM grocery_purchases WHERE id = :purchaseId")
    int deleteById(long purchaseId);

    @Query("DELETE FROM grocery_purchases WHERE id = (SELECT id FROM grocery_purchases "
            + "WHERE itemName = :itemName COLLATE NOCASE AND purchasedAt = :purchasedAt "
            + "ORDER BY id DESC LIMIT 1)")
    int deleteMatchingPurchase(String itemName, long purchasedAt);
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
