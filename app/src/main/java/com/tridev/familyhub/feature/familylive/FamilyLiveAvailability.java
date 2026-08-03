package com.tridev.familyhub.feature.familylive;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.location.LocationHeartbeatPolicy;

import java.util.Locale;

/**
 * Single source of truth for Family Live availability, severity,
 * battery attention, network confidence and tracking heartbeat health.
 */
public final class FamilyLiveAvailability {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String SHARING_PAUSED = "SHARING_PAUSED";
    public static final String PERMISSION_OFF = "PERMISSION_OFF";
    public static final String GPS_OFF = "GPS_OFF";
    public static final String INTERNET_UNAVAILABLE = "INTERNET_UNAVAILABLE";
    public static final String BATTERY_SAVER = "BATTERY_SAVER";
    public static final String DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String TRACKING_STALLED = "TRACKING_STALLED";
    public static final String NO_RECENT_UPDATE = "NO_RECENT_UPDATE";
    public static final String LOCATION_UNAVAILABLE = "LOCATION_UNAVAILABLE";

    public static final int CONNECTION_UNKNOWN = -1;
    public static final int CONNECTION_OFFLINE = 0;
    public static final int CONNECTION_CONNECTED = 1;

    public static final int LOW_BATTERY_PERCENT = 20;

    private FamilyLiveAvailability() {
    }

    @NonNull
    public static String resolve(
            @NonNull FamilyLiveCloudMember member,
            long now,
            long freshnessMs
    ) {
        if (!member.sharingEnabled) {
            return SHARING_PAUSED;
        }

        String reported = normalize(member.availabilityReason);
        if (PERMISSION_OFF.equals(reported)
                || GPS_OFF.equals(reported)
                || INTERNET_UNAVAILABLE.equals(reported)
                || BATTERY_SAVER.equals(reported)) {
            return reported;
        }

        String serviceState = LocationHeartbeatPolicy.normalizeState(
                member.serviceState
        );
        if (LocationHeartbeatPolicy.STATE_RECOVERY_PENDING.equals(serviceState)
                || LocationHeartbeatPolicy.STATE_STALLED.equals(serviceState)
                || LocationHeartbeatPolicy.STATE_STOPPED.equals(serviceState)) {
            return TRACKING_STALLED;
        }

        if (!member.online) {
            return DEVICE_OFFLINE;
        }

        if (LocationHeartbeatPolicy.isCloudHeartbeatStale(
                member.serviceState,
                member.serviceHeartbeatAt,
                now
        )) {
            return TRACKING_STALLED;
        }

        if (member.updatedAt <= 0L
                || now - member.updatedAt > freshnessMs) {
            return NO_RECENT_UPDATE;
        }

        if (!member.hasLocation) {
            return LOCATION_UNAVAILABLE;
        }

        return AVAILABLE;
    }

    @NonNull
    public static String normalize(String value) {
        if (value == null) {
            return LOCATION_UNAVAILABLE;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case AVAILABLE:
            case SHARING_PAUSED:
            case PERMISSION_OFF:
            case GPS_OFF:
            case INTERNET_UNAVAILABLE:
            case BATTERY_SAVER:
            case DEVICE_OFFLINE:
            case TRACKING_STALLED:
            case NO_RECENT_UPDATE:
            case LOCATION_UNAVAILABLE:
                return normalized;
            default:
                return LOCATION_UNAVAILABLE;
        }
    }

    @StringRes
    public static int labelRes(@NonNull String reason) {
        switch (normalize(reason)) {
            case AVAILABLE:
                return R.string.family_live_state_live_now;
            case SHARING_PAUSED:
                return R.string.family_live_state_sharing_paused;
            case PERMISSION_OFF:
                return R.string.family_live_state_permission_off;
            case GPS_OFF:
                return R.string.family_live_state_gps_off;
            case INTERNET_UNAVAILABLE:
                return R.string.family_live_state_internet_off;
            case BATTERY_SAVER:
                return R.string.family_live_state_battery_saver;
            case DEVICE_OFFLINE:
                return R.string.family_live_state_device_offline;
            case TRACKING_STALLED:
                return R.string.family_live_state_tracking_stalled;
            case NO_RECENT_UPDATE:
                return R.string.family_live_state_update_stale;
            default:
                return R.string.family_live_state_location_unavailable;
        }
    }

    @StringRes
    public static int detailRes(@NonNull String reason) {
        switch (normalize(reason)) {
            case AVAILABLE:
                return R.string.family_live_state_detail_live;
            case SHARING_PAUSED:
                return R.string.family_live_state_detail_sharing_paused;
            case PERMISSION_OFF:
                return R.string.family_live_state_detail_permission_off;
            case GPS_OFF:
                return R.string.family_live_state_detail_gps_off;
            case INTERNET_UNAVAILABLE:
                return R.string.family_live_state_detail_internet_off;
            case BATTERY_SAVER:
                return R.string.family_live_state_detail_battery_saver;
            case DEVICE_OFFLINE:
                return R.string.family_live_state_detail_device_offline;
            case TRACKING_STALLED:
                return R.string.family_live_state_detail_tracking_stalled;
            case NO_RECENT_UPDATE:
                return R.string.family_live_state_detail_update_stale;
            default:
                return R.string.family_live_state_detail_location_unavailable;
        }
    }

    public static boolean isAvailable(@NonNull String reason) {
        return AVAILABLE.equals(normalize(reason));
    }

    public static boolean isPaused(@NonNull String reason) {
        return SHARING_PAUSED.equals(normalize(reason));
    }

    public static boolean isWarning(@NonNull String reason) {
        String normalized = normalize(reason);
        return BATTERY_SAVER.equals(normalized)
                || NO_RECENT_UPDATE.equals(normalized);
    }

    public static boolean isCritical(@NonNull String reason) {
        String normalized = normalize(reason);
        return PERMISSION_OFF.equals(normalized)
                || GPS_OFF.equals(normalized)
                || INTERNET_UNAVAILABLE.equals(normalized)
                || DEVICE_OFFLINE.equals(normalized)
                || TRACKING_STALLED.equals(normalized)
                || LOCATION_UNAVAILABLE.equals(normalized);
    }

    public static boolean isLowBattery(
            int batteryPercentage,
            boolean charging
    ) {
        return !charging
                && batteryPercentage >= 0
                && batteryPercentage <= LOW_BATTERY_PERCENT;
    }

    public static boolean needsAttention(
            @NonNull String reason,
            int batteryPercentage,
            boolean charging
    ) {
        return !isAvailable(reason)
                || isLowBattery(batteryPercentage, charging);
    }

    public static int connectionState(
            @NonNull String reason,
            boolean reportedConnected
    ) {
        String normalized = normalize(reason);

        if (AVAILABLE.equals(normalized)) {
            return reportedConnected
                    ? CONNECTION_CONNECTED
                    : CONNECTION_UNKNOWN;
        }

        if (INTERNET_UNAVAILABLE.equals(normalized)
                || DEVICE_OFFLINE.equals(normalized)) {
            return CONNECTION_OFFLINE;
        }

        return CONNECTION_UNKNOWN;
    }
}
