package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A private vehicle profile linked to its family owner. */
@Entity(
        tableName = "vehicles",
        foreignKeys = @ForeignKey(
                entity = FamilyMember.class,
                parentColumns = "id",
                childColumns = "ownerMemberId",
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("ownerMemberId"),
                @Index(value = "registrationNumber", unique = true),
                @Index("insuranceExpiryAt"),
                @Index("serviceDueAt")
        }
)
public class Vehicle {

    public static final String TYPE_CAR = "CAR";
    public static final String TYPE_MOTORCYCLE = "MOTORCYCLE";
    public static final String TYPE_SCOOTER = "SCOOTER";
    public static final String TYPE_BICYCLE = "BICYCLE";
    public static final String TYPE_COMMERCIAL = "COMMERCIAL";
    public static final String TYPE_OTHER = "OTHER";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long ownerMemberId;

    @NonNull
    public String vehicleType = TYPE_CAR;

    /** Friendly name such as Family Car or Papa's Bike. */
    @NonNull
    public String displayName = "";

    @NonNull
    public String registrationNumber = "";

    @NonNull
    public String manufacturer = "";

    @NonNull
    public String model = "";

    @NonNull
    public String fuelType = "";

    public int manufactureYear;

    /** Zero means that an expiry or due date has not been recorded. */
    public long insuranceExpiryAt;
    public long pollutionExpiryAt;
    public long serviceDueAt;

    @NonNull
    public String notes = "";

    public long createdAt;
}
