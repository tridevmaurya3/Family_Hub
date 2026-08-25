package com.tridev.familyhub.feature.grocery;

import static org.junit.Assert.assertEquals;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import org.junit.Test;

import java.util.Calendar;

public class GroceryRecurrenceEngineTest {
    @Test public void monthlyReflectsDailyAfterOneCompletedMonth() {
        GroceryItem item = recurring(GroceryItem.LIST_MONTHLY, at(2026, 1, 20));
        assertEquals(GroceryItem.LIST_MONTHLY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 19)));
        assertEquals(GroceryItem.LIST_DAILY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 20)));
    }

    @Test public void twoMonthlyProgressesThroughMonthlyToDaily() {
        GroceryItem item = recurring(GroceryItem.LIST_TWO_MONTH, at(2026, 1, 20));
        assertEquals(GroceryItem.LIST_TWO_MONTH,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 19)));
        assertEquals(GroceryItem.LIST_MONTHLY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 20)));
        assertEquals(GroceryItem.LIST_DAILY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 3, 20)));
    }

    @Test public void threeMonthlyProgressesOneActiveCategoryAtATime() {
        GroceryItem item = recurring(GroceryItem.LIST_THREE_MONTH, at(2026, 1, 20));
        assertEquals(GroceryItem.LIST_THREE_MONTH,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 19)));
        assertEquals(GroceryItem.LIST_TWO_MONTH,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 2, 20)));
        assertEquals(GroceryItem.LIST_MONTHLY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 3, 20)));
        assertEquals(GroceryItem.LIST_DAILY,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 4, 20)));
    }

    @Test public void actualPurchaseDateResetsCycleAndBadgeTracksOrigin() {
        GroceryItem item = recurring(GroceryItem.LIST_THREE_MONTH, at(2026, 1, 1));
        item.purchasedAt = at(2026, 4, 25);
        assertEquals(GroceryItem.LIST_THREE_MONTH,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 5, 24)));
        assertEquals(GroceryItem.LIST_TWO_MONTH,
                GroceryRecurrenceEngine.effectiveCycle(item, at(2026, 5, 25)));
        assertEquals("3 MONTHLY",
                GroceryRecurrenceEngine.badgeLabel(item, at(2026, 5, 25)));
    }

    @Test public void purchasedRecurringOccurrencesStayInOriginalPurchasedFilter() {
        assertPurchasedOccurrenceUsesOriginalCycle(GroceryItem.LIST_MONTHLY);
        assertPurchasedOccurrenceUsesOriginalCycle(GroceryItem.LIST_TWO_MONTH);
        assertPurchasedOccurrenceUsesOriginalCycle(GroceryItem.LIST_THREE_MONTH);
    }

    private static void assertPurchasedOccurrenceUsesOriginalCycle(String origin) {
        GroceryItem occurrence = new GroceryItem();
        occurrence.listType = GroceryItem.LIST_DAILY;
        occurrence.lastResetMonth = GroceryRecurrenceEngine.occurrenceMetadata(origin);
        occurrence.isPurchased = true;
        assertEquals(origin, GroceryRecurrenceEngine.effectiveCycle(occurrence, at(2026, 8, 25)));
    }

    @Test public void purchasedHistoryIsNotHiddenByPendingShadowing() {
        GroceryItem history = new GroceryItem();
        history.listType = GroceryItem.LIST_MONTHLY;
        history.isPurchased = true;
        history.recurrenceShadowed = true;
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                history, GroceryItem.LIST_MONTHLY, at(2026, 8, 25)));
    }

    @Test public void dailyPurchaseStaysDailyAndDoesNotBecomeRecurring() {
        GroceryItem daily = new GroceryItem();
        daily.listType = GroceryItem.LIST_DAILY;
        daily.isPurchased = true;
        daily.purchasedAt = at(2026, 8, 25);
        assertEquals(GroceryItem.LIST_DAILY,
                GroceryRecurrenceEngine.effectiveCycle(daily, at(2026, 11, 25)));
        assertEquals(false, GroceryRecurrenceEngine.isRecurringType(
                GroceryRecurrenceEngine.originalCycle(daily)));
    }

    private static GroceryItem recurring(String type, long anchor) {
        GroceryItem item = new GroceryItem();
        item.listType = type; item.createdAt = anchor; item.isPurchased = false;
        return item;
    }

    private static long at(int year, int month, int day) {
        Calendar value = Calendar.getInstance();
        value.clear(); value.set(year, month - 1, day, 12, 0, 0);
        return value.getTimeInMillis();
    }
}
