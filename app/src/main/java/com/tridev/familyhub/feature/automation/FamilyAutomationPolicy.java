package com.tridev.familyhub.feature.automation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Locale;

/** Pure time, place, battery and trip-transition rules for Phase 6. */
public final class FamilyAutomationPolicy {

    public static final int MONDAY = 1;
    public static final int TUESDAY = 1 << 1;
    public static final int WEDNESDAY = 1 << 2;
    public static final int THURSDAY = 1 << 3;
    public static final int FRIDAY = 1 << 4;
    public static final int SATURDAY = 1 << 5;
    public static final int SUNDAY = 1 << 6;
    public static final int WEEKDAYS_MASK =
            MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY;
    public static final int ALL_DAYS_MASK = WEEKDAYS_MASK | SATURDAY | SUNDAY;

    public static final int CRITICAL_BATTERY_PERCENT = 8;
    public static final int LOW_BATTERY_PERCENT = 15;
    public static final long LOCATION_FRESHNESS_MS = 20L * 60L * 1000L;
    public static final long TRIP_END_STATIONARY_MS = 10L * 60L * 1000L;
    public static final long EVENT_COOLDOWN_MS = 30L * 60L * 1000L;

    private FamilyAutomationPolicy() {
    }

    public static boolean validRule(@Nullable FamilyAutomationRule rule) {
        if (rule == null
                || rule.ruleId == null
                || rule.ruleId.trim().isEmpty()
                || rule.familyId == null
                || rule.familyId.trim().isEmpty()
                || rule.targetUid == null
                || rule.targetUid.trim().isEmpty()
                || rule.title == null
                || rule.title.trim().isEmpty()
                || !validType(rule.type)
                || rule.daysMask < 1
                || rule.daysMask > ALL_DAYS_MASK
                || !validMinute(rule.startMinute)
                || !validMinute(rule.endMinute)
                || rule.graceMinutes < 0
                || rule.graceMinutes > 180) {
            return false;
        }
        if (!rule.isPlaceRule()) {
            return true;
        }
        return rule.placeName != null
                && !rule.placeName.trim().isEmpty()
                && validCoordinate(rule.latitude, rule.longitude)
                && rule.radiusMeters >= 50D
                && rule.radiusMeters <= 2_000D;
    }

    public static boolean validType(@Nullable String type) {
        return FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL.equals(type)
                || FamilyAutomationRule.TYPE_EXPECTED_DEPARTURE.equals(type)
                || FamilyAutomationRule.TYPE_LATE_RETURN.equals(type)
                || FamilyAutomationRule.TYPE_SCHEDULED_SHARING.equals(type);
    }

    public static boolean validMinute(int minute) {
        return minute >= 0 && minute <= 1_439;
    }

    public static boolean validCoordinate(double latitude, double longitude) {
        return latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(Math.abs(latitude) < 0.0000001D
                && Math.abs(longitude) < 0.0000001D);
    }

    public static int minuteOfDay(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60
                + calendar.get(Calendar.MINUTE);
    }

    public static int dayBit(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        switch (calendar.get(Calendar.DAY_OF_WEEK)) {
            case Calendar.MONDAY:
                return MONDAY;
            case Calendar.TUESDAY:
                return TUESDAY;
            case Calendar.WEDNESDAY:
                return WEDNESDAY;
            case Calendar.THURSDAY:
                return THURSDAY;
            case Calendar.FRIDAY:
                return FRIDAY;
            case Calendar.SATURDAY:
                return SATURDAY;
            case Calendar.SUNDAY:
            default:
                return SUNDAY;
        }
    }

    public static boolean isDayEnabled(int daysMask, long timestamp) {
        return (daysMask & dayBit(timestamp)) != 0;
    }

    /** Handles normal and overnight windows such as 22:00–06:00. */
    public static boolean isMinuteInWindow(
            int startMinute,
            int endMinute,
            int currentMinute
    ) {
        if (!validMinute(startMinute)
                || !validMinute(endMinute)
                || !validMinute(currentMinute)) {
            return false;
        }
        if (startMinute == endMinute) {
            return true;
        }
        if (startMinute < endMinute) {
            return currentMinute >= startMinute && currentMinute < endMinute;
        }
        return currentMinute >= startMinute || currentMinute < endMinute;
    }

    public static boolean shouldRunSharingWindow(
            @NonNull FamilyAutomationRule rule,
            long now
    ) {
        if (!rule.enabled
                || !rule.isScheduledSharing()
                || !isDayEnabledForWindow(rule, now)) {
            return false;
        }
        return isMinuteInWindow(
                rule.startMinute,
                rule.endMinute,
                minuteOfDay(now)
        );
    }

    private static boolean isDayEnabledForWindow(
            @NonNull FamilyAutomationRule rule,
            long now
    ) {
        int minute = minuteOfDay(now);
        if (rule.startMinute <= rule.endMinute || minute >= rule.startMinute) {
            return isDayEnabled(rule.daysMask, now);
        }
        Calendar previousDay = Calendar.getInstance();
        previousDay.setTimeInMillis(now);
        previousDay.add(Calendar.DAY_OF_YEAR, -1);
        return isDayEnabled(rule.daysMask, previousDay.getTimeInMillis());
    }

    public static boolean isLateWindow(
            @NonNull FamilyAutomationRule rule,
            long now
    ) {
        if (!rule.enabled
                || !rule.isPlaceRule()
                || !isDayEnabled(rule.daysMask, now)) {
            return false;
        }
        int lateMinute = Math.min(1_439,
                rule.startMinute + Math.max(0, rule.graceMinutes));
        return minuteOfDay(now) >= lateMinute;
    }

    public static boolean isFreshLocation(long locationAt, long now) {
        return locationAt > 0L
                && locationAt <= now + 15_000L
                && now - locationAt <= LOCATION_FRESHNESS_MS;
    }

    public static boolean insidePlace(
            double latitude,
            double longitude,
            @NonNull FamilyAutomationRule rule
    ) {
        if (!rule.isPlaceRule()
                || !validCoordinate(latitude, longitude)) {
            return false;
        }
        return distanceMeters(
                latitude,
                longitude,
                rule.latitude,
                rule.longitude
        ) <= Math.max(50D, rule.radiusMeters);
    }

    public static boolean shouldPauseAutomaticStart(
            int batteryPercentage,
            boolean charging
    ) {
        return !charging
                && batteryPercentage >= 0
                && batteryPercentage <= CRITICAL_BATTERY_PERCENT;
    }

    public static boolean isLowBattery(
            int batteryPercentage,
            boolean charging
    ) {
        return !charging
                && batteryPercentage >= 0
                && batteryPercentage <= LOW_BATTERY_PERCENT;
    }

    public static boolean isMoving(@Nullable String movementType) {
        String type = safeUpper(movementType);
        return "WALKING".equals(type)
                || "CYCLING".equals(type)
                || "TRAVELLING".equals(type);
    }

    public static boolean isStationary(@Nullable String movementType) {
        return "STATIONARY".equals(safeUpper(movementType));
    }

    public static boolean shouldStartTrip(
            boolean tripActive,
            @Nullable String previousMovement,
            @Nullable String currentMovement
    ) {
        return !tripActive
                && isMoving(currentMovement)
                && (isMoving(previousMovement)
                || isStationary(previousMovement)
                || "UNKNOWN".equals(safeUpper(previousMovement)));
    }

    public static boolean shouldEndTrip(
            boolean tripActive,
            long lastMovingAt,
            @Nullable String currentMovement,
            long now
    ) {
        return tripActive
                && isStationary(currentMovement)
                && lastMovingAt > 0L
                && now - lastMovingAt >= TRIP_END_STATIONARY_MS;
    }

    public static long nextBoundaryAfter(
            @NonNull FamilyAutomationRule rule,
            long now
    ) {
        if (!rule.enabled || !rule.isScheduledSharing()) {
            return Long.MAX_VALUE;
        }
        long best = Long.MAX_VALUE;
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(now);
        cursor.set(Calendar.SECOND, 0);
        cursor.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 8; offset++) {
            Calendar day = (Calendar) cursor.clone();
            day.add(Calendar.DAY_OF_YEAR, offset);
            long dayStart = startOfDay(day.getTimeInMillis());
            if (!isDayEnabled(rule.daysMask, dayStart)) {
                continue;
            }
            best = minimumFuture(best,
                    minuteTimestamp(dayStart, rule.startMinute), now);
            long endDayStart = dayStart;
            if (rule.startMinute >= rule.endMinute
                    && rule.startMinute != rule.endMinute) {
                Calendar next = Calendar.getInstance();
                next.setTimeInMillis(dayStart);
                next.add(Calendar.DAY_OF_YEAR, 1);
                endDayStart = startOfDay(next.getTimeInMillis());
            }
            best = minimumFuture(best,
                    minuteTimestamp(endDayStart, rule.endMinute), now);
        }
        return best;
    }

    public static long startOfDay(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @NonNull
    public static String dayKey(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return String.format(
                Locale.US,
                "%04d%02d%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
        );
    }

    public static double distanceMeters(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2
    ) {
        double radius = 6_371_000D;
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double dLat = Math.toRadians(latitude2 - latitude1);
        double dLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2D) * Math.sin(dLon / 2D);
        return radius * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    private static long minuteTimestamp(long dayStart, int minute) {
        return dayStart + minute * 60_000L;
    }

    private static long minimumFuture(long best, long candidate, long now) {
        return candidate > now ? Math.min(best, candidate) : best;
    }

    @NonNull
    private static String safeUpper(@Nullable String value) {
        return value == null
                ? "UNKNOWN"
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
