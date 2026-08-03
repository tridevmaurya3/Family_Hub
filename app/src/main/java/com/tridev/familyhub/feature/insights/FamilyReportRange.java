package com.tridev.familyhub.feature.insights;

import androidx.annotation.NonNull;

import com.tridev.familyhub.feature.journey.FamilyJourneyPolicy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Pure date-range policy for daily, weekly, monthly and custom reports. */
public final class FamilyReportRange {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";
    public static final String CUSTOM = "CUSTOM";
    public static final int MAX_CUSTOM_DAYS = 90;

    @NonNull public final String type;
    public final long startAt;
    public final long endAtExclusive;

    private FamilyReportRange(
            @NonNull String type,
            long startAt,
            long endAtExclusive
    ) {
        this.type = type;
        this.startAt = startAt;
        this.endAtExclusive = endAtExclusive;
    }

    @NonNull
    public static FamilyReportRange daily(long anchor) {
        long start = FamilyJourneyPolicy.startOfDay(anchor);
        return new FamilyReportRange(DAILY, start, addDays(start, 1));
    }

    @NonNull
    public static FamilyReportRange weekly(long anchor) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(FamilyJourneyPolicy.startOfDay(anchor));
        int firstDay = calendar.getFirstDayOfWeek();
        while (calendar.get(Calendar.DAY_OF_WEEK) != firstDay) {
            calendar.add(Calendar.DAY_OF_YEAR, -1);
        }
        long start = calendar.getTimeInMillis();
        return new FamilyReportRange(WEEKLY, start, addDays(start, 7));
    }

    @NonNull
    public static FamilyReportRange monthly(long anchor) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(FamilyJourneyPolicy.startOfDay(anchor));
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.MONTH, 1);
        return new FamilyReportRange(MONTHLY, start, calendar.getTimeInMillis());
    }

    @NonNull
    public static FamilyReportRange custom(long startInclusive, long endInclusive) {
        long start = FamilyJourneyPolicy.startOfDay(
                Math.min(startInclusive, endInclusive)
        );
        long endStart = FamilyJourneyPolicy.startOfDay(
                Math.max(startInclusive, endInclusive)
        );
        long maximumEnd = addDays(start, MAX_CUSTOM_DAYS - 1L);
        endStart = Math.min(endStart, maximumEnd);
        return new FamilyReportRange(CUSTOM, start, addDays(endStart, 1));
    }

    public int dayCount() {
        long count = Math.max(1L, (endAtExclusive - startAt) / 86_400_000L);
        return (int) Math.min(MAX_CUSTOM_DAYS, count);
    }

    public boolean contains(long timestamp) {
        return timestamp >= startAt && timestamp < endAtExclusive;
    }

    @NonNull
    public List<String> dayKeys() {
        List<String> keys = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startAt);
        while (calendar.getTimeInMillis() < endAtExclusive
                && keys.size() < MAX_CUSTOM_DAYS) {
            keys.add(FamilyJourneyPolicy.dayKey(calendar.getTimeInMillis()));
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return keys;
    }

    @NonNull
    public String displayLabel() {
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        String start = format.format(new Date(startAt));
        String end = format.format(new Date(Math.max(startAt, endAtExclusive - 1L)));
        return start.equals(end) ? start : start + " – " + end;
    }

    private static long addDays(long start, long days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(start);
        calendar.add(Calendar.DAY_OF_YEAR, (int) days);
        return calendar.getTimeInMillis();
    }
}
