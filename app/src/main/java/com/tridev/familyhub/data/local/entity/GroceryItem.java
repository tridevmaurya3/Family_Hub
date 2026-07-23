package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One local family grocery or shopping-list item. */
@Entity(
        tableName = "grocery_items",
        indices = {
                @Index("category"),
                @Index("isPurchased"),
                @Index("priority")
        }
)
public class GroceryItem {

    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    @NonNull
    public String category = "";

    /** Free-form amount such as 2 kg, 3 packets, or 1 bottle. */
    @NonNull
    public String quantity = "";

    public double estimatedCost;

    @NonNull
    public String priority = PRIORITY_NORMAL;

    public boolean isPurchased;

    @NonNull
    public String notes = "";

    public long createdAt;

    /** Zero while the item is not purchased. */
    public long purchasedAt;
}
