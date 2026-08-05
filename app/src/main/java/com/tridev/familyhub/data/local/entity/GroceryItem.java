package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.room.Ignore;

/** One local family grocery or shopping-list item. */
@Entity(
        tableName = "grocery_items",
        indices = {
                @Index("category"),
                @Index("isPurchased"),
                @Index("priority"),
                @Index("cloudId"),
                @Index("familyId"),
                @Index("listType"),
                @Index("assignedMemberId"),
                @Index("buyingStatus"),
                @Index("isMonthlyMaster"),
                @Index("priceLocationKey")
        }
)
public class GroceryItem {

    public static final String LIST_DAILY = "DAILY";
    public static final String LIST_MONTHLY = "MONTHLY";

    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BUYING = "BUYING";
    public static final String STATUS_PURCHASED = "PURCHASED";

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

    @ColumnInfo(defaultValue = "0") public double actualCost;

    @ColumnInfo(defaultValue = "1") public boolean autoPriceEnabled = true;

    @NonNull
    @ColumnInfo(defaultValue = "''") public String priceLocationKey = "";

    @ColumnInfo(defaultValue = "0") public int priceConfidence;

    @NonNull
    public String priority = PRIORITY_NORMAL;

    public boolean isPurchased;

    @NonNull
    @ColumnInfo(defaultValue = "'PENDING'") public String buyingStatus = STATUS_PENDING;

    @ColumnInfo(defaultValue = "0") public boolean isMonthlyMaster;

    @NonNull
    @ColumnInfo(defaultValue = "''") public String lastResetMonth = "";

    @ColumnInfo(defaultValue = "0") public int purchaseCount;

    @ColumnInfo(defaultValue = "0") public long financeEntryId;

    @NonNull
    public String notes = "";

    /** Daily essentials or the recurring monthly household list. */
    @NonNull
    public String listType = LIST_DAILY;

    /** Stable family-profile id and display label of the person buying it. */
    @NonNull
    public String assignedMemberId = "";

    @NonNull
    public String assignedMemberName = "";

    /** Kept separately so later edits do not hide who completed the item. */
    @NonNull
    public String purchasedByName = "";

    public long createdAt;

    /** Zero while the item is not purchased. */
    public long purchasedAt;

    /** Stable Firebase key used by every device in the family. */
    @NonNull
    public String cloudId = "";

    /** Family that owns the shared item; empty means local-only until linked. */
    @NonNull
    public String familyId = "";

    public long updatedAt;

    @NonNull
    public String updatedByUid = "";

    @NonNull
    public String updatedByName = "";

    /** Runtime-only signal used to explain duplicate reuse in the UI. */
    @Ignore public boolean duplicateMerged;
}
