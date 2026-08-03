package com.tridev.familyhub.geofence;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.location.FamilyDeviceSafetyAlertPolicy;

import java.util.Calendar;

/**
 * Stores alert choices separately for each signed-in family member.
 * Exact locations and alert history are never written to preferences.
 */
public final class FamilySafetyAlertPreferences {

    private static final String PREFS = "family_hub_safety_alert_preferences";
    private static final String KEY_NOTIFICATIONS = "notifications";
    private static final String KEY_ARRIVED = "arrived";
    private static final String KEY_LEFT = "left";
    private static final String KEY_DWELL = "dwell";
    private static final String KEY_NO_UPDATE = "no_update";
    private static final String KEY_LOW_BATTERY = "low_battery";
    private static final String KEY_DEVICE_OFFLINE = "device_offline";
    private static final String KEY_QUIET_HOURS = "quiet_hours";
    private static final String KEY_QUIET_START = "quiet_start";
    private static final String KEY_QUIET_END = "quiet_end";

    private final SharedPreferences preferences;
    private final String scopePrefix;

    public FamilySafetyAlertPreferences(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        scopePrefix = scopePrefix();
    }

    public boolean notificationsEnabled() {
        return preferences.getBoolean(key(KEY_NOTIFICATIONS), true);
    }

    public void setNotificationsEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_NOTIFICATIONS), enabled).apply();
    }

    public boolean arrivedEnabled() {
        return preferences.getBoolean(key(KEY_ARRIVED), true);
    }

    public void setArrivedEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_ARRIVED), enabled).apply();
    }

    public boolean leftEnabled() {
        return preferences.getBoolean(key(KEY_LEFT), true);
    }

    public void setLeftEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_LEFT), enabled).apply();
    }

    public boolean dwellEnabled() {
        return preferences.getBoolean(key(KEY_DWELL), true);
    }

    public void setDwellEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_DWELL), enabled).apply();
    }

    public boolean noUpdateEnabled() {
        return preferences.getBoolean(key(KEY_NO_UPDATE), true);
    }

    public void setNoUpdateEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_NO_UPDATE), enabled).apply();
    }

    public boolean lowBatteryEnabled() {
        return preferences.getBoolean(key(KEY_LOW_BATTERY), true);
    }

    public void setLowBatteryEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_LOW_BATTERY), enabled).apply();
    }

    public boolean deviceOfflineEnabled() {
        return preferences.getBoolean(key(KEY_DEVICE_OFFLINE), true);
    }

    public void setDeviceOfflineEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_DEVICE_OFFLINE), enabled).apply();
    }

    public boolean quietHoursEnabled() {
        return preferences.getBoolean(key(KEY_QUIET_HOURS), false);
    }

    public void setQuietHoursEnabled(boolean enabled) {
        preferences.edit().putBoolean(key(KEY_QUIET_HOURS), enabled).apply();
    }

    public int quietStartMinute() {
        return preferences.getInt(
                key(KEY_QUIET_START),
                FamilySafetyAlertPolicy.DEFAULT_QUIET_START_MINUTE
        );
    }

    public int quietEndMinute() {
        return preferences.getInt(
                key(KEY_QUIET_END),
                FamilySafetyAlertPolicy.DEFAULT_QUIET_END_MINUTE
        );
    }

    public boolean isAlertTypeEnabled(@NonNull String alertType) {
        if (FamilySafetyAlertPolicy.isArrived(alertType)) {
            return arrivedEnabled();
        }
        if (FamilySafetyAlertPolicy.isLeft(alertType)) {
            return leftEnabled();
        }
        if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(alertType)) {
            return dwellEnabled();
        }
        if (FamilyDeviceSafetyAlertPolicy.ALERT_NO_UPDATE.equals(alertType)) {
            return noUpdateEnabled();
        }
        if (FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY.equals(alertType)) {
            return lowBatteryEnabled();
        }
        if (FamilyDeviceSafetyAlertPolicy.ALERT_DEVICE_OFFLINE.equals(alertType)) {
            return deviceOfflineEnabled();
        }
        return false;
    }

    public boolean shouldShowNotification(
            @NonNull String alertType,
            long occurredAt
    ) {
        Calendar calendar = Calendar.getInstance();
        if (occurredAt > 0L) {
            calendar.setTimeInMillis(occurredAt);
        }
        int minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60
                + calendar.get(Calendar.MINUTE);
        return FamilySafetyAlertPolicy.shouldShowNotification(
                notificationsEnabled(),
                isAlertTypeEnabled(alertType),
                quietHoursEnabled(),
                minuteOfDay,
                quietStartMinute(),
                quietEndMinute()
        );
    }

    @NonNull
    private String key(@NonNull String key) {
        return scopePrefix + key;
    }

    @NonNull
    private static String scopePrefix() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user == null ? null : user.getUid();
        if (uid == null || uid.trim().isEmpty()) {
            uid = "local_member";
        }
        return uid.trim() + "_";
    }
}
