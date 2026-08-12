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
}
