package com.tridev.familyhub.feature.grocery;

import static org.junit.Assert.assertEquals;

import com.tridev.familyhub.data.local.entity.GroceryItem;

import org.junit.Test;

import java.util.Calendar;

public class GroceryRecurrenceEngineTest {
    @Test public void freshRecurringItemIsPendingImmediately() {
        GroceryItem item = recurring(GroceryItem.LIST_WEEKLY, at(2026, 9, 1));
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_WEEKLY, at(2026, 9, 1)));
    }

    @Test public void weeklyReturnsSevenDaysAfterPurchase() {
        GroceryItem item = purchasedMaster(
                GroceryItem.LIST_WEEKLY, at(2026, 9, 1));
        assertEquals(false, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_WEEKLY, at(2026, 9, 7)));
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_WEEKLY, at(2026, 9, 8)));
    }

    @Test public void fortnightlyReturnsFifteenDaysAfterPurchase() {
        GroceryItem item = purchasedMaster(
                GroceryItem.LIST_FORTNIGHTLY, at(2026, 9, 1));
        assertEquals(false, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_FORTNIGHTLY, at(2026, 9, 15)));
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_FORTNIGHTLY, at(2026, 9, 16)));
    }

    @Test public void monthlyReturnsOneCalendarMonthAfterPurchase() {
        GroceryItem item = purchasedMaster(
                GroceryItem.LIST_MONTHLY, at(2026, 1, 31));
        assertEquals(false, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_MONTHLY, at(2026, 2, 27)));
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                item, GroceryItem.LIST_MONTHLY, at(2026, 2, 28)));
    }

    @Test public void purchasedHistoryRemainsInOriginalFilter() {
        GroceryItem occurrence = new GroceryItem();
        occurrence.listType = GroceryItem.LIST_DAILY;
        occurrence.lastResetMonth = GroceryRecurrenceEngine.occurrenceMetadata(
                GroceryItem.LIST_WEEKLY);
        occurrence.isPurchased = true;
        assertEquals(true, GroceryRecurrenceEngine.matchesCycle(
                occurrence, GroceryItem.LIST_WEEKLY, at(2026, 9, 20)));
    }

    @Test public void dailyPurchaseStaysDaily() {
        GroceryItem daily = new GroceryItem();
        daily.listType = GroceryItem.LIST_DAILY;
        daily.isPurchased = true;
        assertEquals(GroceryItem.LIST_DAILY,
                GroceryRecurrenceEngine.effectiveCycle(daily, at(2026, 9, 20)));
    }

    private static GroceryItem recurring(String type, long createdAt) {
        GroceryItem item = new GroceryItem();
        item.listType = type;
        item.createdAt = createdAt;
        item.isPurchased = false;
        return item;
    }

    private static GroceryItem purchasedMaster(String type, long purchasedAt) {
        GroceryItem item = recurring(type, purchasedAt);
        item.purchasedAt = purchasedAt;
        return item;
    }

    private static long at(int year, int month, int day) {
        Calendar value = Calendar.getInstance();
        value.clear(); value.set(year, month - 1, day, 12, 0, 0);
        return value.getTimeInMillis();
    }
}
