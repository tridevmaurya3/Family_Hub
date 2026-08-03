package com.tridev.familyhub.feature.journey;

import androidx.annotation.NonNull;

/** Firebase-compatible immutable-by-convention Journey History point. */
public class FamilyJourneyPoint {

    @NonNull public String pointId = "";
    @NonNull public String familyId = "";
    @NonNull public String uid = "";
    @NonNull public String clientUpdateId = "";
    @NonNull public String dayKey = "";
    public double latitude;
    public double longitude;
    public double accuracy;
    public long capturedAt;
    public long recordedAt;
    public double speedMetersPerSecond;
    @NonNull public String movementType = "UNKNOWN";
    @NonNull public String placeLabel = "";
    @NonNull public String safePlaceId = "";
    @NonNull public String safePlaceName = "";
    public int batteryPercentage = -1;
    public boolean charging;

    public FamilyJourneyPoint() {
        // Required by Firebase.
    }
}
