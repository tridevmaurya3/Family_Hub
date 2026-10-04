package com.tridev.familyhub.core.tasks;

import java.util.Calendar;

/** Pure timing rules: a missed advance notice must not skip the due notice. */
public final class FamilyTaskReminderTiming {
    private FamilyTaskReminderTiming() { }
    public static long occurrence(long due, String repeat, long now, boolean followUp) {
        if (due <= 0) return -1;
        long end = due + (followUp ? 30L * 60000L : 0L);
        if (end > now) return due;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(due);
        while (c.getTimeInMillis() + (followUp ? 30L * 60000L : 0L) <= now) {
            if ("DAILY".equals(repeat)) c.add(Calendar.DAY_OF_YEAR, 1);
            else if ("WEEKLY".equals(repeat)) c.add(Calendar.WEEK_OF_YEAR, 1);
            else if ("MONTHLY".equals(repeat)) c.add(Calendar.MONTH, 1);
            else return -1;
        }
        return c.getTimeInMillis();
    }
    public static long trigger(long occurrence, int minutesBefore, int stage) {
        if (stage == 0) return occurrence - Math.max(0, minutesBefore) * 60000L;
        return occurrence + (stage == 2 ? 30L * 60000L : 0L);
    }
}
