package com.tridev.familyhub.location;

import androidx.annotation.NonNull;

import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;

/**
 * Central priority rules for the device conditions required by Family Live.
 *
 * Permission and GPS failures block location collection. Internet loss does
 * not stop collection; the service keeps only the newest encrypted point and
 * synchronises it when a validated network returns.
 */
public final class LocationDeviceHealth {

    public static final String READY = "READY";
    public static final String PERMISSION_OFF = "PERMISSION_OFF";
    public static final String GPS_OFF = "GPS_OFF";
    public static final String INTERNET_OFF = "INTERNET_OFF";

    private LocationDeviceHealth() {
    }

    @NonNull
    public static String resolve(
            boolean permissionGranted,
            boolean locationEnabled,
            boolean internetAvailable
    ) {
        if (!permissionGranted) {
            return PERMISSION_OFF;
        }
        if (!locationEnabled) {
            return GPS_OFF;
        }
        if (!internetAvailable) {
            return INTERNET_OFF;
        }
        return READY;
    }

    public static boolean isReady(@NonNull String state) {
        return READY.equals(state);
    }

    public static boolean blocksLocationUpdates(@NonNull String state) {
        return PERMISSION_OFF.equals(state) || GPS_OFF.equals(state);
    }

    public static boolean shouldQueueOffline(@NonNull String state) {
        return INTERNET_OFF.equals(state);
    }

    @NonNull
    public static String availabilityReason(@NonNull String state) {
        switch (state) {
            case PERMISSION_OFF:
                return FamilyLiveAvailability.PERMISSION_OFF;
            case GPS_OFF:
                return FamilyLiveAvailability.GPS_OFF;
            case INTERNET_OFF:
                return FamilyLiveAvailability.INTERNET_UNAVAILABLE;
            default:
                return FamilyLiveAvailability.AVAILABLE;
        }
    }
}
