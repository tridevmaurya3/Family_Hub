package com.tridev.familyhub.feature.tasks.overlay;

import java.util.Calendar;

/** Past pending ranges end at today's local midnight, excluding today's and future tasks. */
final class FamilyTaskOverlayPastRange {
    static final int ALL_PAST = 6;
    static final int YESTERDAY = 7;
    static final int SEVEN_DAYS = 8;
    static final int FIFTEEN_DAYS = 9;
    static final int THIRTY_DAYS = 10;

    private FamilyTaskOverlayPastRange() { }

    static long[] calculate(int mode, Calendar now) {
        if (mode < ALL_PAST || mode > THIRTY_DAYS) return null;
        Calendar start = (Calendar) now.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long end = start.getTimeInMillis();
        // A missing due date (zero) is not an overdue task.
        if (mode == ALL_PAST) return new long[]{1L, end};
        int days = mode == YESTERDAY ? 1 : mode == SEVEN_DAYS ? 7
                : mode == FIFTEEN_DAYS ? 15 : 30;
        start.add(Calendar.DAY_OF_YEAR, -days);
        return new long[]{start.getTimeInMillis(), end};
    }
}
