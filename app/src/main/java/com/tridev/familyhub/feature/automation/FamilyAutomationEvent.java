package com.tridev.familyhub.feature.automation;

import androidx.annotation.NonNull;

/** Privacy-minimised automation event. Exact coordinates are never stored. */
public final class FamilyAutomationEvent {

    public static final String EVENT_ARRIVED = "ARRIVED";
    public static final String EVENT_DEPARTED = "DEPARTED";
    public static final String EVENT_LATE = "LATE";
    public static final String EVENT_MISSED = "MISSED";
    public static final String EVENT_TRIP_STARTED = "TRIP_STARTED";
    public static final String EVENT_TRIP_ENDED = "TRIP_ENDED";
    public static final String EVENT_SHARING_STARTED = "SHARING_STARTED";
    public static final String EVENT_SHARING_STOPPED = "SHARING_STOPPED";
    public static final String EVENT_LOW_BATTERY_PAUSED =
            "LOW_BATTERY_PAUSED";

    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARNING = "WARNING";

    @NonNull public String eventId = "";
    @NonNull public String familyId = "";
    @NonNull public String targetUid = "";
    @NonNull public String targetName = "";
    @NonNull public String ruleId = "";
    @NonNull public String ruleTitle = "";
    @NonNull public String type = EVENT_ARRIVED;
    @NonNull public String severity = SEVERITY_INFO;
    @NonNull public String placeName = "";
    @NonNull public String detail = "";
    @NonNull public String deduplicationKey = "";
    public long occurredAt;
    public long createdAt;

    public FamilyAutomationEvent() {
        // Required by Firebase.
    }
}
