package com.tridev.familyhub.feature.grocery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.Calendar;

/** Purchase-date anchored recurrence policy for Grocery items. */
public final class GroceryRecurrenceEngine {
    public static final String OCCURRENCE_META_PREFIX = "recurrence-origin:";

    private GroceryRecurrenceEngine() { }

    /** Compatibility hooks: recurrence is now calculated, not copied. */
    public static void register(@NonNull android.app.Application application) { }
    public static void schedule(@NonNull android.content.Context context) { }

    public static boolean matchesCycle(@NonNull GroceryItem item,
                                       @NonNull String selectedCycle, long now) {
        return !item.recurrenceShadowed
                && normalizeCycle(selectedCycle).equals(effectiveCycle(item, now));
    }

    /** The only category in which this active item is currently visible. */
    @NonNull
    public static String effectiveCycle(@NonNull GroceryItem item, long now) {
        String origin = originalCycle(item);
        int interval = intervalMonths(origin);
        if (interval <= 0 || item.isPurchased) return normalizeCycle(item.listType);
        long anchor = item.purchasedAt > 0L ? item.purchasedAt : item.createdAt;
        int remaining = Math.max(0, interval - completedCalendarMonths(anchor, now));
        if (remaining == 0) return GroceryItem.LIST_DAILY;
        if (remaining == 1) return GroceryItem.LIST_MONTHLY;
        if (remaining == 2) return GroceryItem.LIST_TWO_MONTH;
        return GroceryItem.LIST_THREE_MONTH;
    }

    @NonNull
    public static String originalCycle(@NonNull GroceryItem item) {
        if (isRecurringType(item.originalRecurringType)) {
            return normalizeCycle(item.originalRecurringType);
        }
        String metadataOrigin = originFromMetadata(item.lastResetMonth);
        return isRecurringType(metadataOrigin)
                ? metadataOrigin : normalizeCycle(item.listType);
    }

    /** Badge is needed only when an item is reflected outside its original list. */
    @NonNull
    public static String badgeLabel(@NonNull GroceryItem item, long now) {
        String origin = originalCycle(item);
        if (!isRecurringType(origin) || origin.equals(effectiveCycle(item, now))) return "";
        if (GroceryItem.LIST_THREE_MONTH.equals(origin)) return "3 MONTHLY";
        if (GroceryItem.LIST_TWO_MONTH.equals(origin)) return "2 MONTHLY";
        return "MONTHLY";
    }

    public static int monthsUntilNextDue(@NonNull GroceryItem item, long now) {
        int interval = intervalMonths(originalCycle(item));
        if (interval <= 0) return Integer.MAX_VALUE;
        long anchor = item.purchasedAt > 0L ? item.purchasedAt : item.createdAt;
        return Math.max(0, interval - completedCalendarMonths(anchor, now));
    }

    public static boolean isRecurringType(@Nullable String value) {
        String clean = normalizeCycle(value);
        return GroceryItem.LIST_MONTHLY.equals(clean)
                || GroceryItem.LIST_TWO_MONTH.equals(clean)
                || GroceryItem.LIST_THREE_MONTH.equals(clean);
    }

    public static int intervalMonths(@Nullable String value) {
        String clean = normalizeCycle(value);
        if (GroceryItem.LIST_MONTHLY.equals(clean)) return 1;
        if (GroceryItem.LIST_TWO_MONTH.equals(clean)) return 2;
        if (GroceryItem.LIST_THREE_MONTH.equals(clean)) return 3;
        return 0;
    }

    @NonNull
    public static String normalizeCycle(@Nullable String value) {
        if (GroceryItem.LIST_MONTHLY.equals(value)) return GroceryItem.LIST_MONTHLY;
        if (GroceryItem.LIST_TWO_MONTH.equals(value)) return GroceryItem.LIST_TWO_MONTH;
        if (GroceryItem.LIST_THREE_MONTH.equals(value)) return GroceryItem.LIST_THREE_MONTH;
        return GroceryItem.LIST_DAILY;
    }

    @NonNull
    public static String occurrenceMetadata(@NonNull String origin) {
        return OCCURRENCE_META_PREFIX + normalizeCycle(origin);
    }

    @NonNull
    public static String originFromMetadata(@Nullable String value) {
        if (value == null || !value.startsWith(OCCURRENCE_META_PREFIX)) return "";
        return normalizeCycle(value.substring(OCCURRENCE_META_PREFIX.length()));
    }

    static int completedCalendarMonths(long anchor, long now) {
        if (anchor <= 0L || now <= anchor) return 0;
        Calendar start = Calendar.getInstance(); start.setTimeInMillis(anchor);
        Calendar end = Calendar.getInstance(); end.setTimeInMillis(now);
        int months = (end.get(Calendar.YEAR) - start.get(Calendar.YEAR)) * 12
                + end.get(Calendar.MONTH) - start.get(Calendar.MONTH);
        Calendar anniversary = (Calendar) start.clone();
        anniversary.add(Calendar.MONTH, Math.max(0, months));
        if (anniversary.after(end)) months--;
        return Math.max(0, months);
    }
}
