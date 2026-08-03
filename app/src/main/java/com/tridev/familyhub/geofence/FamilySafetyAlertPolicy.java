package com.tridev.familyhub.geofence;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.location.FamilyDeviceSafetyAlertPolicy;

/** Pure filter and quiet-hours rules for the Family Safety Alert Centre. */
public final class FamilySafetyAlertPolicy {

    public static final String FILTER_ALL = "ALL";
    public static final String FILTER_UNREAD = "UNREAD";
    public static final String FILTER_ARRIVED = "ARRIVED";
    public static final String FILTER_LEFT = "LEFT";
    public static final String FILTER_DWELL = "DWELL";
    public static final String FILTER_NO_UPDATE = "NO_UPDATE";
    public static final String FILTER_LOW_BATTERY = "LOW_BATTERY";
    public static final String FILTER_OFFLINE = "OFFLINE";

    public static final int DEFAULT_QUIET_START_MINUTE = 22 * 60;
    public static final int DEFAULT_QUIET_END_MINUTE = 7 * 60;

    private FamilySafetyAlertPolicy() {
    }

    public static boolean matchesFilter(
            @Nullable String transitionType,
            boolean isRead,
            @Nullable String filter
    ) {
        String safeFilter = filter == null ? FILTER_ALL : filter;
        if (FILTER_ALL.equals(safeFilter)) {
            return true;
        }
        if (FILTER_UNREAD.equals(safeFilter)) {
            return !isRead;
        }
        if (FILTER_ARRIVED.equals(safeFilter)) {
            return isArrived(transitionType);
        }
        if (FILTER_LEFT.equals(safeFilter)) {
            return isLeft(transitionType);
        }
        if (FILTER_DWELL.equals(safeFilter)) {
            return SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(
                    transitionType
            );
        }
        if (FILTER_NO_UPDATE.equals(safeFilter)) {
            return FamilyDeviceSafetyAlertPolicy.ALERT_NO_UPDATE.equals(
                    transitionType
            );
        }
        if (FILTER_LOW_BATTERY.equals(safeFilter)) {
            return FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY.equals(
                    transitionType
            );
        }
        if (FILTER_OFFLINE.equals(safeFilter)) {
            return FamilyDeviceSafetyAlertPolicy.ALERT_DEVICE_OFFLINE.equals(
                    transitionType
            );
        }
        return true;
    }

    public static boolean isQuietMinute(
            int minuteOfDay,
            int quietStartMinute,
            int quietEndMinute
    ) {
        if (!validMinute(minuteOfDay)
                || !validMinute(quietStartMinute)
                || !validMinute(quietEndMinute)
                || quietStartMinute == quietEndMinute) {
            return false;
        }
        if (quietStartMinute < quietEndMinute) {
            return minuteOfDay >= quietStartMinute
                    && minuteOfDay < quietEndMinute;
        }
        return minuteOfDay >= quietStartMinute
                || minuteOfDay < quietEndMinute;
    }

    public static boolean shouldShowNotification(
            boolean notificationsEnabled,
            boolean alertTypeEnabled,
            boolean quietHoursEnabled,
            int minuteOfDay,
            int quietStartMinute,
            int quietEndMinute
    ) {
        if (!notificationsEnabled || !alertTypeEnabled) {
            return false;
        }
        return !quietHoursEnabled || !isQuietMinute(
                minuteOfDay,
                quietStartMinute,
                quietEndMinute
        );
    }

    public static boolean isArrived(@Nullable String transitionType) {
        return SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(transitionType)
                || "ENTER".equals(transitionType);
    }

    public static boolean isLeft(@Nullable String transitionType) {
        return SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(transitionType)
                || "EXIT".equals(transitionType);
    }

    public static boolean isDeviceHealthAlert(
            @Nullable String transitionType
    ) {
        return FamilyDeviceSafetyAlertPolicy.isSupported(transitionType);
    }

    @NonNull
    public static String normalizeFilter(@Nullable String filter) {
        if (FILTER_UNREAD.equals(filter)
                || FILTER_ARRIVED.equals(filter)
                || FILTER_LEFT.equals(filter)
                || FILTER_DWELL.equals(filter)
                || FILTER_NO_UPDATE.equals(filter)
                || FILTER_LOW_BATTERY.equals(filter)
                || FILTER_OFFLINE.equals(filter)) {
            return filter;
        }
        return FILTER_ALL;
    }

    private static boolean validMinute(int minute) {
        return minute >= 0 && minute < 24 * 60;
    }
}
