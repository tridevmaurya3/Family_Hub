package com.tridev.familyhub.feature.grocery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** Purchase-date anchored recurrence policy for Grocery items. */
public final class GroceryRecurrenceEngine {
    public static final String OCCURRENCE_META_PREFIX = "recurrence-origin:";

    private GroceryRecurrenceEngine() { }

    /** Compatibility hooks: recurrence is calculated from the purchase anchor. */
    public static void register(@NonNull android.app.Application application) { }
    public static void schedule(@NonNull android.content.Context context) { }

    public static boolean matchesCycle(@NonNull GroceryItem item,
                                       @NonNull String selectedCycle, long now) {
        if (item.recurrenceShadowed && !item.isPurchased) return false;
        String selected = normalizeCycle(selectedCycle);
        String origin = originalCycle(item);
        if (item.isPurchased) {
            return selected.equals(isRecurringType(origin)
                    ? origin : normalizeCycle(item.listType));
        }
        if (!isRecurringType(origin)) {
            return selected.equals(normalizeCycle(item.listType));
        }
        // A fresh recurring item is immediately actionable. After purchase the
        // pending master stays persisted/synced but is hidden until its interval.
        if (item.purchasedAt > 0L && now < nextDueAt(item)) return false;
        return selected.equals(origin);
    }

    /** Category in which the item belongs when it is visible. */
    @NonNull
    public static String effectiveCycle(@NonNull GroceryItem item, long now) {
        String origin = originalCycle(item);
        return isRecurringType(origin) ? origin : normalizeCycle(item.listType);
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

    /** Badge is retained for immutable purchase history compatibility. */
    @NonNull
    public static String badgeLabel(@NonNull GroceryItem item, long now) {
        String origin = originalCycle(item);
        if (!isRecurringType(origin)
                || origin.equals(normalizeCycle(item.listType))) return "";
        if (GroceryItem.LIST_FORTNIGHTLY.equals(origin)) return "FORTNIGHTLY";
        if (GroceryItem.LIST_WEEKLY.equals(origin)) return "WEEKLY";
        return "MONTHLY";
    }

    public static long nextDueAt(@NonNull GroceryItem item) {
        String origin = originalCycle(item);
        long anchor = item.purchasedAt > 0L ? item.purchasedAt : item.createdAt;
        if (anchor <= 0L || !isRecurringType(origin)) return Long.MAX_VALUE;
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(anchor);
        if (GroceryItem.LIST_WEEKLY.equals(origin)) {
            due.add(Calendar.DAY_OF_YEAR, 7);
        } else if (GroceryItem.LIST_FORTNIGHTLY.equals(origin)) {
            due.add(Calendar.DAY_OF_YEAR, 15);
        } else {
            due.add(Calendar.MONTH, 1);
        }
        return due.getTimeInMillis();
    }

    public static int daysUntilNextDue(@NonNull GroceryItem item, long now) {
        long due = nextDueAt(item);
        if (due == Long.MAX_VALUE) return Integer.MAX_VALUE;
        if (due <= now) return 0;
        return (int) Math.max(1L,
                TimeUnit.MILLISECONDS.toDays(due - now + TimeUnit.DAYS.toMillis(1) - 1));
    }

    /** Kept for callers compiled against the earlier monthly-only engine. */
    public static int monthsUntilNextDue(@NonNull GroceryItem item, long now) {
        String origin = originalCycle(item);
        if (GroceryItem.LIST_MONTHLY.equals(origin)) {
            return now >= nextDueAt(item) ? 0 : 1;
        }
        return daysUntilNextDue(item, now) == 0 ? 0 : 1;
    }

    public static boolean isRecurringType(@Nullable String value) {
        String clean = normalizeCycle(value);
        return GroceryItem.LIST_MONTHLY.equals(clean)
                || GroceryItem.LIST_WEEKLY.equals(clean)
                || GroceryItem.LIST_FORTNIGHTLY.equals(clean);
    }

    public static int intervalMonths(@Nullable String value) {
        return GroceryItem.LIST_MONTHLY.equals(normalizeCycle(value)) ? 1 : 0;
    }

    @NonNull
    public static String normalizeCycle(@Nullable String value) {
        if (GroceryItem.LIST_MONTHLY.equals(value)) return GroceryItem.LIST_MONTHLY;
        if (GroceryItem.LIST_WEEKLY.equals(value)) return GroceryItem.LIST_WEEKLY;
        if (GroceryItem.LIST_FORTNIGHTLY.equals(value)) {
            return GroceryItem.LIST_FORTNIGHTLY;
        }
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
