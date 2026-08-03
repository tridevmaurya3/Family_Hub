package com.tridev.familyhub.geofence;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Persists privacy-safe geofence state without storing coordinates. */
public final class SafePlaceAlertStateStore {

    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_INSIDE = "INSIDE";
    public static final String STATE_OUTSIDE = "OUTSIDE";

    private static final String PREFS =
            "family_hub_safe_place_alert_state";
    private static final String KEY_STATE = "state_";
    private static final String KEY_LAST_ARRIVED = "last_arrived_";
    private static final String KEY_LAST_LEFT = "last_left_";
    private static final String KEY_LAST_DWELL = "last_dwell_";
    private static final String KEY_PENDING_ENTER = "pending_enter_";

    private final SharedPreferences preferences;

    public SafePlaceAlertStateStore(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public String state(long placeId) {
        return preferences.getString(
                KEY_STATE + placeId,
                STATE_UNKNOWN
        );
    }

    public void markPendingEnter(long placeId, long occurredAt) {
        preferences.edit()
                .putLong(KEY_PENDING_ENTER + placeId, occurredAt)
                .apply();
    }

    public long pendingEnterAt(long placeId) {
        return preferences.getLong(KEY_PENDING_ENTER + placeId, 0L);
    }

    public void clearPendingEnter(long placeId) {
        preferences.edit()
                .remove(KEY_PENDING_ENTER + placeId)
                .apply();
    }

    public long lastAlertAt(long placeId, @NonNull String alertType) {
        if (SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)) {
            return preferences.getLong(KEY_LAST_ARRIVED + placeId, 0L);
        }
        if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)) {
            return preferences.getLong(KEY_LAST_LEFT + placeId, 0L);
        }
        if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(alertType)) {
            return preferences.getLong(KEY_LAST_DWELL + placeId, 0L);
        }
        return 0L;
    }

    public void recordAlert(
            long placeId,
            @NonNull String alertType,
            long occurredAt
    ) {
        SharedPreferences.Editor editor = preferences.edit();
        if (SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)) {
            editor.putString(KEY_STATE + placeId, STATE_INSIDE)
                    .putLong(KEY_LAST_ARRIVED + placeId, occurredAt)
                    .remove(KEY_PENDING_ENTER + placeId);
        } else if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)) {
            editor.putString(KEY_STATE + placeId, STATE_OUTSIDE)
                    .putLong(KEY_LAST_LEFT + placeId, occurredAt)
                    .remove(KEY_PENDING_ENTER + placeId);
        } else if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(alertType)) {
            editor.putString(KEY_STATE + placeId, STATE_INSIDE)
                    .putLong(KEY_LAST_DWELL + placeId, occurredAt)
                    .remove(KEY_PENDING_ENTER + placeId);
        }
        editor.apply();
    }

    public void markConfirmedState(long placeId, boolean inside) {
        preferences.edit()
                .putString(
                        KEY_STATE + placeId,
                        inside ? STATE_INSIDE : STATE_OUTSIDE
                )
                .apply();
    }

    public void remove(long placeId) {
        preferences.edit()
                .remove(KEY_STATE + placeId)
                .remove(KEY_LAST_ARRIVED + placeId)
                .remove(KEY_LAST_LEFT + placeId)
                .remove(KEY_LAST_DWELL + placeId)
                .remove(KEY_PENDING_ENTER + placeId)
                .apply();
    }
}
