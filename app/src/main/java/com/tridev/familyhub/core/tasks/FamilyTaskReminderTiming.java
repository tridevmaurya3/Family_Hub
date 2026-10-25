package com.tridev.familyhub.core.tasks;

import java.util.Calendar;

/** Pure timing rules: a missed advance notice must not skip the due notice. */
public final class FamilyTaskReminderTiming {
    private FamilyTaskReminderTiming() { }
    /** Keep the displayed quick-add timestamp fixed until the user changes a control. */
    public static long quickDue(long now, int dateMode, long customDate, int timeMode,
                                int hour, int minute) {
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(now);
        if (dateMode == 2 && customDate > 0) {
            Calendar selected = Calendar.getInstance();
            selected.setTimeInMillis(customDate);
            due.set(Calendar.YEAR, selected.get(Calendar.YEAR));
            due.set(Calendar.MONTH, selected.get(Calendar.MONTH));
            due.set(Calendar.DAY_OF_MONTH, selected.get(Calendar.DAY_OF_MONTH));
        } else if (dateMode == 1) due.add(Calendar.DAY_OF_YEAR, 1);
        due.set(Calendar.SECOND, 0); due.set(Calendar.MILLISECOND, 0);
        if (timeMode == 3 && hour >= 0 && minute >= 0) {
            due.set(Calendar.HOUR_OF_DAY, hour); due.set(Calendar.MINUTE, minute);
        } else if (timeMode == 1) due.add(Calendar.MINUTE, 30);
        else if (timeMode == 2) due.add(Calendar.HOUR_OF_DAY, 1);
        else due.add(Calendar.MINUTE, 1); // "Now": the next minute, never a past timestamp.
        return due.getTimeInMillis();
    }
    public static boolean validReminderDue(long selected, long previous, boolean existing, long now) {
        return selected > now || (existing && selected > 0 && selected == previous);
    }
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
    /** One bounded catch-up for a missed due/snooze notice; never replay delivered notices. */
    public static long deliveryTrigger(long intended, int stage, long now, boolean delivered) {
        if (intended <= 0 || delivered) return -1;
        if (intended > now) return intended;
        if ((stage == 1 || stage == 3) && now - intended <= 24L * 60L * 60000L) return now + 1000L;
        return -1;
    }

}

