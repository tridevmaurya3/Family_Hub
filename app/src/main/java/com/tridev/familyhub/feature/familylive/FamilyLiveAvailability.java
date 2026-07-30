package com.tridev.familyhub.feature.familylive;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;

import java.util.Locale;

/** Single source of truth for truthful Family Live availability states. */
public final class FamilyLiveAvailability {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String SHARING_PAUSED = "SHARING_PAUSED";
    public static final String PERMISSION_OFF = "PERMISSION_OFF";
    public static final String GPS_OFF = "GPS_OFF";
    public static final String INTERNET_UNAVAILABLE = "INTERNET_UNAVAILABLE";
    public static final String BATTERY_SAVER = "BATTERY_SAVER";
    public static final String DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String NO_RECENT_UPDATE = "NO_RECENT_UPDATE";
    public static final String LOCATION_UNAVAILABLE = "LOCATION_UNAVAILABLE";

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
        if (!member.online) {
            return DEVICE_OFFLINE;
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
                return R.string.family_live_reason_available;
            case SHARING_PAUSED:
                return R.string.family_live_reason_sharing_paused;
            case PERMISSION_OFF:
                return R.string.family_live_reason_permission_off;
            case GPS_OFF:
                return R.string.family_live_reason_gps_off;
            case INTERNET_UNAVAILABLE:
                return R.string.family_live_reason_internet_unavailable;
            case BATTERY_SAVER:
                return R.string.family_live_reason_battery_saver;
            case DEVICE_OFFLINE:
                return R.string.family_live_reason_device_offline;
            case NO_RECENT_UPDATE:
                return R.string.family_live_reason_no_recent_update;
            default:
                return R.string.family_live_reason_location_unavailable;
        }
    }

    public static boolean isAvailable(@NonNull String reason) {
        return AVAILABLE.equals(normalize(reason));
    }

    public static boolean isWarning(@NonNull String reason) {
        String normalized = normalize(reason);
        return BATTERY_SAVER.equals(normalized)
                || NO_RECENT_UPDATE.equals(normalized);
    }
}
