package com.tridev.familyhub.location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure thresholds and cooldowns for family device-health safety alerts. */
public final class FamilyDeviceSafetyAlertPolicy {

    public static final String ALERT_NO_UPDATE = "MEMBER_NO_UPDATE";
    public static final String ALERT_LOW_BATTERY = "MEMBER_LOW_BATTERY";
    public static final String ALERT_DEVICE_OFFLINE = "MEMBER_DEVICE_OFFLINE";

    public static final long NO_UPDATE_THRESHOLD_MS = 20L * 60L * 1000L;
    public static final long OFFLINE_CONFIRMATION_MS = 5L * 60L * 1000L;
    public static final long LOW_BATTERY_DATA_MAX_AGE_MS =
            2L * 60L * 60L * 1000L;

    public static final int LOW_BATTERY_PERCENTAGE = 15;
    public static final int LOW_BATTERY_RECOVERY_PERCENTAGE = 22;

    public static final long NO_UPDATE_REMINDER_COOLDOWN_MS =
            4L * 60L * 60L * 1000L;
    public static final long OFFLINE_REMINDER_COOLDOWN_MS =
            4L * 60L * 60L * 1000L;
    public static final long LOW_BATTERY_REMINDER_COOLDOWN_MS =
            6L * 60L * 60L * 1000L;

    private FamilyDeviceSafetyAlertPolicy() {
    }

    public static boolean isSupported(@Nullable String alertType) {
        return ALERT_NO_UPDATE.equals(alertType)
                || ALERT_LOW_BATTERY.equals(alertType)
                || ALERT_DEVICE_OFFLINE.equals(alertType);
    }

    public static boolean shouldFlagNoUpdate(
            boolean sharingEnabled,
            boolean deviceOffline,
            long updatedAt,
            long now
    ) {
        return sharingEnabled
                && !deviceOffline
                && validPastTimestamp(updatedAt, now)
                && now - updatedAt >= NO_UPDATE_THRESHOLD_MS;
    }

    public static boolean shouldFlagOffline(
            boolean sharingEnabled,
            boolean online,
            long disconnectedAt,
            long heartbeatAt,
            long updatedAt,
            long now
    ) {
        if (!sharingEnabled || online || now <= 0L) {
            return false;
        }
        long evidenceAt = newestPositive(
                disconnectedAt,
                heartbeatAt,
                updatedAt
        );
        return validPastTimestamp(evidenceAt, now)
                && now - evidenceAt >= OFFLINE_CONFIRMATION_MS;
    }

    public static boolean shouldFlagLowBattery(
            boolean sharingEnabled,
            int batteryPercentage,
            boolean charging,
            long updatedAt,
            long now
    ) {
        return sharingEnabled
                && !charging
                && batteryPercentage >= 0
                && batteryPercentage <= LOW_BATTERY_PERCENTAGE
                && validPastTimestamp(updatedAt, now)
                && now - updatedAt <= LOW_BATTERY_DATA_MAX_AGE_MS;
    }

    public static boolean lowBatteryRecovered(
            int batteryPercentage,
            boolean charging
    ) {
        return charging
                || batteryPercentage < 0
                || batteryPercentage >= LOW_BATTERY_RECOVERY_PERCENTAGE;
    }

    public static long cooldownMs(@NonNull String alertType) {
        if (ALERT_LOW_BATTERY.equals(alertType)) {
            return LOW_BATTERY_REMINDER_COOLDOWN_MS;
        }
        if (ALERT_DEVICE_OFFLINE.equals(alertType)) {
            return OFFLINE_REMINDER_COOLDOWN_MS;
        }
        return NO_UPDATE_REMINDER_COOLDOWN_MS;
    }

    public static long deduplicationBucket(
            @NonNull String alertType,
            long occurredAt
    ) {
        long cooldown = Math.max(1L, cooldownMs(alertType));
        return Math.max(0L, occurredAt) / cooldown;
    }

    public static boolean cooldownElapsed(
            @NonNull String alertType,
            long lastAlertAt,
            long now
    ) {
        if (!isSupported(alertType) || now <= 0L) {
            return false;
        }
        if (lastAlertAt <= 0L || lastAlertAt > now + 2L * 60L * 1000L) {
            return true;
        }
        return now - lastAlertAt >= cooldownMs(alertType);
    }

    @NonNull
    public static String memberPlaceId(@NonNull String memberUid) {
        return "member:" + memberUid.trim();
    }

    public static boolean isMemberPlaceId(@Nullable String placeId) {
        return placeId != null && placeId.startsWith("member:");
    }

    @NonNull
    public static String memberUidFromPlaceId(@Nullable String placeId) {
        if (!isMemberPlaceId(placeId)) {
            return "";
        }
        return placeId.substring("member:".length()).trim();
    }

    private static boolean validPastTimestamp(long value, long now) {
        return value > 0L && now > 0L && value <= now + 15_000L;
    }

    private static long newestPositive(long first, long second, long third) {
        return Math.max(Math.max(Math.max(0L, first), Math.max(0L, second)),
                Math.max(0L, third));
    }
}
