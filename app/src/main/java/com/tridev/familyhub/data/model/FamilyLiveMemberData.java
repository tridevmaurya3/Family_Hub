package com.tridev.familyhub.data.model;

import androidx.annotation.NonNull;

/**
 * Read-only result that combines a family profile with its latest optional
 * Family Live status. A member remains visible even before status sharing
 * has been configured.
 */
public class FamilyLiveMemberData {

    public long familyMemberId;

    @NonNull
    public String memberName = "";

    @NonNull
    public String relation = "";

    @NonNull
    public String onlineStatus = "UNKNOWN";

    @NonNull
    public String currentPlaceName = "";

    public int batteryPercentage = -1;

    public boolean isCharging;

    public boolean hasInternet;

    @NonNull
    public String movementType = "UNKNOWN";

    public long lastUpdatedAt;

    public boolean isLocationSharingEnabled;

    public boolean hasLocation;
}
