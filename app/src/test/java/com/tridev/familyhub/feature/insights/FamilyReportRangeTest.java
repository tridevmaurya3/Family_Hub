package com.tridev.familyhub.feature.insights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class FamilyReportRangeTest {

    @Test
    public void dailyWeeklyAndMonthly_haveExpectedCoverage() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2026, Calendar.AUGUST, 3, 12, 0, 0);
        long anchor = calendar.getTimeInMillis();

        assertEquals(1, FamilyReportRange.daily(anchor).dayCount());
        assertEquals(7, FamilyReportRange.weekly(anchor).dayCount());
        int monthDays = FamilyReportRange.monthly(anchor).dayCount();
        assertTrue(monthDays >= 28 && monthDays <= 31);
    }

    @Test
    public void customRange_isOrderedAndLimitedToNinetyDays() {
        Calendar start = Calendar.getInstance();
        start.set(2026, Calendar.JANUARY, 1, 0, 0, 0);
        Calendar farEnd = Calendar.getInstance();
        farEnd.set(2026, Calendar.DECEMBER, 31, 0, 0, 0);

        FamilyReportRange range = FamilyReportRange.custom(
                farEnd.getTimeInMillis(),
                start.getTimeInMillis()
        );

        assertEquals(90, range.dayCount());
        assertEquals(90, range.dayKeys().size());
        assertTrue(range.startAt < range.endAtExclusive);
    }
}
