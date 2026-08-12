package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Immutable purchase event used for cumulative monthly reports. */
@Entity(tableName = "grocery_purchases", indices = {
        @Index("purchasedAt"), @Index("category"), @Index("itemName")})
public class GroceryPurchase {
    @PrimaryKey(autoGenerate = true) public long id;
    public long sourceItemId;
    @NonNull public String itemName = "";
    @NonNull public String category = "";
    @NonNull public String quantity = "";
    public double actualCost;
    public long purchasedAt;
}
