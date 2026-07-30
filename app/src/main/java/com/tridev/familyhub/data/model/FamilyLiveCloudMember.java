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
    @NonNull public final String placeLabel;
    public final int batteryPercentage;
    public final boolean charging;
    public final double speedMetersPerSecond;
    @NonNull public final String movementType;
    public final boolean sharingEnabled;
    public final boolean online;
    @NonNull public final String availabilityReason;
    public final long updatedAt;

    public FamilyLiveCloudMember(
            @NonNull String uid,
            @NonNull String displayName,
            @NonNull String role,
            boolean hasLocation,
            double latitude,
            double longitude,
            double accuracy,
            @NonNull String placeLabel,
            int batteryPercentage,
            boolean charging,
            double speedMetersPerSecond,
            @NonNull String movementType,
            boolean sharingEnabled,
            boolean online,
            @NonNull String availabilityReason,
            long updatedAt
    ) {
        this.uid = uid;
        this.displayName = displayName;
        this.role = role;
        this.hasLocation = hasLocation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.placeLabel = placeLabel;
        this.batteryPercentage = batteryPercentage;
        this.charging = charging;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.movementType = movementType;
        this.sharingEnabled = sharingEnabled;
        this.online = online;
        this.availabilityReason = availabilityReason;
        this.updatedAt = updatedAt;
    }
}
