package com.tridev.familyhub.feature.tasks.overlay;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class FamilyTaskOverlayPastRangeTest {
    private Calendar at(String zone, int year, int month, int day, int hour) {
        Calendar date = Calendar.getInstance(TimeZone.getTimeZone(zone));
        date.clear();
        date.set(year, month - 1, day, hour, 25);
        return date;
    }

    @Test public void allPastExcludesUndatedTodayAndFutureTasks() {
        Calendar now = at("Asia/Kolkata", 2026, 10, 5, 6);
        long[] range = FamilyTaskOverlayPastRange.calculate(FamilyTaskOverlayPastRange.ALL_PAST, now);
        long midnight = at("Asia/Kolkata", 2026, 10, 5, 0).getTimeInMillis() - 25 * 60_000L;
        assertEquals(1L, range[0]);
        assertEquals(midnight, range[1]);
        assertTrue(at("Asia/Kolkata", 2026, 10, 4, 18).getTimeInMillis() < range[1]);
        assertTrue(now.getTimeInMillis() >= range[1]);
    }

    @Test public void boundedPastRangesCrossMonthAndYearBoundaries() {
        Calendar now = at("Asia/Kolkata", 2026, 1, 5, 6);
        int[] modes = {FamilyTaskOverlayPastRange.YESTERDAY, FamilyTaskOverlayPastRange.SEVEN_DAYS,
                FamilyTaskOverlayPastRange.FIFTEEN_DAYS, FamilyTaskOverlayPastRange.THIRTY_DAYS};
        int[] days = {1, 7, 15, 30};
        for (int i = 0; i < modes.length; i++) {
            long[] range = FamilyTaskOverlayPastRange.calculate(modes[i], now);
            Calendar expected = (Calendar) now.clone();
            expected.set(Calendar.HOUR_OF_DAY, 0);
            expected.set(Calendar.MINUTE, 0);
            expected.add(Calendar.DAY_OF_YEAR, -days[i]);
            assertEquals(expected.getTimeInMillis(), range[0]);
            assertEquals(days[i] * 86_400_000L, range[1] - range[0]);
        }
        assertEquals(6, now.get(Calendar.HOUR_OF_DAY));
    }

    @Test public void yesterdayUsesLocalCalendarAcrossDaylightSavingChange() {
        long[] range = FamilyTaskOverlayPastRange.calculate(FamilyTaskOverlayPastRange.YESTERDAY,
                at("America/New_York", 2026, 3, 9, 6));
        assertEquals(23 * 3_600_000L, range[1] - range[0]);
    }

    @Test public void existingTodayAndFutureFiltersKeepTheirOriginalRangeLogic() {
        for (int mode = 0; mode <= 5; mode++) {
            assertNull(FamilyTaskOverlayPastRange.calculate(mode,
                    at("Asia/Kolkata", 2026, 10, 5, 6)));
        }
    }
}
