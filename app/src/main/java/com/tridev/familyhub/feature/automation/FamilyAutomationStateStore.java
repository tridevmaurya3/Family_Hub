package com.tridev.familyhub.feature.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Local execution state; it stores no coordinates or full route history. */
public final class FamilyAutomationStateStore {

    private static final String PREFS = "family_automation_state";
    private static final String KEY_AUTOMATION_SHARING =
            "sharing_started_by_automation";
    private static final String KEY_TRIP_ACTIVE = "trip_active";
    private static final String KEY_TRIP_STARTED_AT = "trip_started_at";
    private static final String KEY_TRIP_LAST_MOVING_AT =
            "trip_last_moving_at";
    private static final String KEY_TRIP_START_PLACE = "trip_start_place";
    private static final String KEY_PREVIOUS_MOVEMENT = "previous_movement";

    private final SharedPreferences preferences;

    public FamilyAutomationStateStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public boolean shouldDispatch(
            @NonNull String deduplicationKey,
            long now
    ) {
        long previous = preferences.getLong(
                eventKey(deduplicationKey),
                0L
        );
        return previous <= 0L
                || now - previous >= FamilyAutomationPolicy.EVENT_COOLDOWN_MS;
    }

    public void recordDispatched(
            @NonNull String deduplicationKey,
            long occurredAt
    ) {
        preferences.edit()
                .putLong(eventKey(deduplicationKey), occurredAt)
                .apply();
    }

    public boolean wasInside(
            @NonNull String ruleId,
            boolean fallback
    ) {
        String key = "inside_" + safeKey(ruleId);
        return preferences.contains(key)
                ? preferences.getBoolean(key, fallback)
                : fallback;
    }

    public void setInside(@NonNull String ruleId, boolean inside) {
        preferences.edit()
                .putBoolean("inside_" + safeKey(ruleId), inside)
                .apply();
    }

    public boolean hasRuleEventForDay(
            @NonNull String ruleId,
            @NonNull String eventType,
            @NonNull String dayKey
    ) {
        return preferences.getBoolean(
                ruleDayKey(ruleId, eventType, dayKey),
                false
        );
    }

    public void markRuleEventForDay(
            @NonNull String ruleId,
            @NonNull String eventType,
            @NonNull String dayKey
    ) {
        preferences.edit()
                .putBoolean(ruleDayKey(ruleId, eventType, dayKey), true)
                .apply();
    }

    public boolean sharingStartedByAutomation() {
        return preferences.getBoolean(KEY_AUTOMATION_SHARING, false);
    }

    public void setSharingStartedByAutomation(boolean value) {
        preferences.edit()
                .putBoolean(KEY_AUTOMATION_SHARING, value)
                .apply();
    }

    public boolean tripActive() {
        return preferences.getBoolean(KEY_TRIP_ACTIVE, false);
    }

    public long tripStartedAt() {
        return preferences.getLong(KEY_TRIP_STARTED_AT, 0L);
    }

    public long tripLastMovingAt() {
        return preferences.getLong(KEY_TRIP_LAST_MOVING_AT, 0L);
    }

    @NonNull
    public String tripStartPlace() {
        String value = preferences.getString(KEY_TRIP_START_PLACE, "");
        return value == null ? "" : value.trim();
    }

    @NonNull
    public String previousMovement() {
        String value = preferences.getString(KEY_PREVIOUS_MOVEMENT, "UNKNOWN");
        return value == null ? "UNKNOWN" : value.trim();
    }

    public void updateMovement(
            @Nullable String movementType,
            long capturedAt
    ) {
        String movement = movementType == null
                ? "UNKNOWN"
                : movementType.trim();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_PREVIOUS_MOVEMENT, movement);
        if (FamilyAutomationPolicy.isMoving(movement)) {
            editor.putLong(KEY_TRIP_LAST_MOVING_AT, capturedAt);
        }
        editor.apply();
    }

    public void startTrip(long startedAt, @Nullable String placeLabel) {
        preferences.edit()
                .putBoolean(KEY_TRIP_ACTIVE, true)
                .putLong(KEY_TRIP_STARTED_AT, startedAt)
                .putLong(KEY_TRIP_LAST_MOVING_AT, startedAt)
                .putString(
                        KEY_TRIP_START_PLACE,
                        placeLabel == null ? "" : placeLabel.trim()
                )
                .apply();
    }

    public void endTrip() {
        preferences.edit()
                .remove(KEY_TRIP_ACTIVE)
                .remove(KEY_TRIP_STARTED_AT)
                .remove(KEY_TRIP_LAST_MOVING_AT)
                .remove(KEY_TRIP_START_PLACE)
                .apply();
    }

    private String eventKey(@NonNull String value) {
        return "event_" + safeKey(value);
    }

    private String ruleDayKey(
            @NonNull String ruleId,
            @NonNull String eventType,
            @NonNull String dayKey
    ) {
        return "day_" + safeKey(ruleId)
                + "_" + safeKey(eventType)
                + "_" + safeKey(dayKey);
    }

    @NonNull
    private static String safeKey(@NonNull String value) {
        return Integer.toHexString(value.hashCode());
    }
}
