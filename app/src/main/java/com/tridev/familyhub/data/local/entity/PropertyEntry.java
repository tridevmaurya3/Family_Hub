package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A private property profile linked to a family owner. */
@Entity(
        tableName = "properties",
        foreignKeys = @ForeignKey(
                entity = FamilyMember.class,
                parentColumns = "id",
                childColumns = "ownerMemberId",
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("ownerMemberId"),
                @Index("propertyType"),
                @Index("title")
        }
)
public class PropertyEntry {

    public static final String TYPE_HOUSE = "HOUSE";
    public static final String TYPE_FLAT = "FLAT";
    public static final String TYPE_LAND = "LAND";
    public static final String TYPE_SHOP = "SHOP";
    public static final String TYPE_OFFICE = "OFFICE";
    public static final String TYPE_AGRICULTURAL = "AGRICULTURAL";
    public static final String TYPE_OTHER = "OTHER";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long ownerMemberId;

    @NonNull
    public String propertyType = TYPE_HOUSE;

    /** Friendly name such as Home, Village Land, or Main Market Shop. */
    @NonNull
    public String title = "";

    @NonNull
    public String address = "";

    @NonNull
    public String city = "";

    @NonNull
    public String state = "";

    @NonNull
    public String postalCode = "";

    /** Free-form area with its unit, for example 1200 sq ft or 2.5 acre. */
    @NonNull
    public String area = "";

    public double purchaseValue;
    public double estimatedValue;

    /** Zero means the purchase date has not been recorded. */
    public long purchaseDate;

    @NonNull
    public String registrationReference = "";

    @NonNull
    public String notes = "";

    public long createdAt;
}
