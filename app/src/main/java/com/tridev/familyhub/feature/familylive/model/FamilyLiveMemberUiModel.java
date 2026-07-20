package com.tridev.familyhub.feature.familylive.model;

/**
 * UI com.tridev.familyhub.data.model used to display a family member's latest live status.
 *
 * This class is currently used for dummy UI data. Later it will receive
 * information from Room through the Family Live ViewModel.
 */
public class FamilyLiveMemberUiModel {

    private final long memberId;
    private final String memberName;
    private final String currentLocation;
    private final String onlineStatus;
    private final int batteryPercentage;
    private final boolean charging;
    private final boolean internetAvailable;
    private final String movementType;
    private final long lastUpdatedTime;

    public FamilyLiveMemberUiModel(
            long memberId,
            String memberName,
            String currentLocation,
            String onlineStatus,
            int batteryPercentage,
            boolean charging,
            boolean internetAvailable,
            String movementType,
            long lastUpdatedTime
    ) {
        this.memberId = memberId;
        this.memberName = memberName;
        this.currentLocation = currentLocation;
        this.onlineStatus = onlineStatus;
        this.batteryPercentage = batteryPercentage;
        this.charging = charging;
        this.internetAvailable = internetAvailable;
        this.movementType = movementType;
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public long getMemberId() {
        return memberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public String getOnlineStatus() {
        return onlineStatus;
    }

    public int getBatteryPercentage() {
        return batteryPercentage;
    }

    public boolean isCharging() {
        return charging;
    }

    public boolean isInternetAvailable() {
        return internetAvailable;
    }

    public String getMovementType() {
        return movementType;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }
}