package com.tridev.familyhub.data.model;

import androidx.annotation.NonNull;

/** Immutable authorised-family member snapshot assembled from Firebase. */
public final class FamilyLiveCloudMember {

    @NonNull public final String uid;
    @NonNull public final String displayName;
    @NonNull public final String role;
    public final boolean hasLocation;
    public final double latitude;
    public final double longitude;
    public final double accuracy;
    public final int batteryPercentage;
    public final boolean charging;
    public final double speedMetersPerSecond;
    @NonNull public final String movementType;
    public final boolean sharingEnabled;
    public final boolean online;
    public final long updatedAt;

    public FamilyLiveCloudMember(
            @NonNull String uid,
            @NonNull String displayName,
            @NonNull String role,
            boolean hasLocation,
            double latitude,
            double longitude,
            double accuracy,
            int batteryPercentage,
            boolean charging,
            double speedMetersPerSecond,
            @NonNull String movementType,
            boolean sharingEnabled,
            boolean online,
            long updatedAt
    ) {
        this.uid = uid;
        this.displayName = displayName;
        this.role = role;
        this.hasLocation = hasLocation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.batteryPercentage = batteryPercentage;
        this.charging = charging;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.movementType = movementType;
        this.sharingEnabled = sharingEnabled;
        this.online = online;
        this.updatedAt = updatedAt;
    }
}
