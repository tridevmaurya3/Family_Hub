package com.tridev.familyhub.feature.automation;

import androidx.annotation.NonNull;

/** Firebase model for a member-scoped Family Live automation rule. */
public final class FamilyAutomationRule {

    public static final String TYPE_EXPECTED_ARRIVAL = "EXPECTED_ARRIVAL";
    public static final String TYPE_EXPECTED_DEPARTURE = "EXPECTED_DEPARTURE";
    public static final String TYPE_LATE_RETURN = "LATE_RETURN";
    public static final String TYPE_SCHEDULED_SHARING = "SCHEDULED_SHARING";

    @NonNull public String ruleId = "";
    @NonNull public String familyId = "";
    @NonNull public String targetUid = "";
    @NonNull public String targetName = "";
    @NonNull public String createdByUid = "";
    @NonNull public String title = "";
    @NonNull public String type = TYPE_EXPECTED_ARRIVAL;
    @NonNull public String placeName = "";
    public double latitude;
    public double longitude;
    public double radiusMeters = 150D;
    public int daysMask = FamilyAutomationPolicy.ALL_DAYS_MASK;
    public int startMinute = 8 * 60;
    public int endMinute = 18 * 60;
    public int graceMinutes = 30;
    public boolean enabled = true;
    public boolean notifyTrustedViewers = true;
    public long createdAt;
    public long updatedAt;

    public FamilyAutomationRule() {
        // Required by Firebase.
    }

    public boolean isScheduledSharing() {
        return TYPE_SCHEDULED_SHARING.equals(type);
    }

    public boolean isPlaceRule() {
        return TYPE_EXPECTED_ARRIVAL.equals(type)
                || TYPE_EXPECTED_DEPARTURE.equals(type)
                || TYPE_LATE_RETURN.equals(type);
    }

    @NonNull
    public String safeTitle() {
        String value = title == null ? "" : title.trim();
        return value.isEmpty() ? type : value;
    }
}
