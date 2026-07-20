package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * Stores the most recently known Family Live status of one family member.
 *
 * This is an offline-first snapshot. Real cloud synchronization and live
 * location updates can be added later without replacing the local com.tridev.familyhub.data.model.
 */
@Entity(
        tableName = "family_live_status",
        primaryKeys = {"familyMemberId"},
        foreignKeys = {
                @ForeignKey(
                        entity = FamilyMember.class,
                        parentColumns = "id",
                        childColumns = "familyMemberId",
                        onDelete = ForeignKey.CASCADE,
                        onUpdate = ForeignKey.CASCADE
                )
        },
        indices = {
                @Index(value = {"familyMemberId"}, unique = true),
                @Index(value = {"lastUpdatedAt"}),
                @Index(value = {"isLocationSharingEnabled"})
        }
)
public class FamilyLiveStatus {

    public static final String ONLINE_STATUS_ONLINE = "ONLINE";
    public static final String ONLINE_STATUS_OFFLINE = "OFFLINE";
    public static final String ONLINE_STATUS_UNKNOWN = "UNKNOWN";

    public static final String MOVEMENT_STILL = "STILL";
    public static final String MOVEMENT_WALKING = "WALKING";
    public static final String MOVEMENT_BIKE = "BIKE";
    public static final String MOVEMENT_CAR = "CAR";
    public static final String MOVEMENT_UNKNOWN = "UNKNOWN";

    public static final String VISIBILITY_GUARDIAN_ONLY = "GUARDIAN_ONLY";
    public static final String VISIBILITY_ALL_FAMILY = "ALL_FAMILY";
    public static final String VISIBILITY_SELECTED_MEMBERS = "SELECTED_MEMBERS";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    /**
     * ID of the linked row in the family_members table.
     * One member can have only one latest live-status row.
     */
    public long familyMemberId;

    @NonNull
    public String onlineStatus = ONLINE_STATUS_UNKNOWN;

    /**
     * Human-readable place such as Home, Office, School or Travelling.
     */
    @NonNull
    public String currentPlaceName = "";

    /**
     * Last known coordinates.
     * hasLocation should be checked before using these values.
     */
    public double latitude;

    public double longitude;

    public boolean hasLocation;

    /**
     * Expected range: 0 to 100.
     * -1 means battery information is unavailable.
     */
    public int batteryPercentage = -1;

    public boolean isCharging;

    public boolean hasInternet;

    @NonNull
    public String movementType = MOVEMENT_UNKNOWN;

    /**
     * Speed in metres per second.
     * Zero is used when speed is unavailable.
     */
    public float speedMetersPerSecond;

    public boolean isLocationSharingEnabled;

    @NonNull
    public String visibilityType = VISIBILITY_GUARDIAN_ONLY;

    /**
     * Epoch timestamp of the latest status update.
     */
    public long lastUpdatedAt;
}