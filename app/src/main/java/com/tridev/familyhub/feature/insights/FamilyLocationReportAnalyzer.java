package com.tridev.familyhub.feature.insights;

import androidx.annotation.NonNull;

import com.tridev.familyhub.feature.journey.FamilyJourneyPoint;
import com.tridev.familyhub.feature.journey.FamilyJourneyPolicy;
import com.tridev.familyhub.feature.journey.FamilyJourneyRepository;
import com.tridev.familyhub.feature.journey.FamilyJourneySummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds privacy-scoped travel analytics from accessible Journey History. */
public final class FamilyLocationReportAnalyzer {

    private static final long MINUTE_MS = 60_000L;
    private static final long DELAY_THRESHOLD_MS = 60L * MINUTE_MS;
    private static final long MISSED_GRACE_MS = 120L * MINUTE_MS;
    private static final int MIN_ROUTINE_DAYS = 3;

    private FamilyLocationReportAnalyzer() {
    }

    @NonNull
    public static FamilyLocationReport analyze(
            @NonNull FamilyReportRange range,
            @NonNull List<FamilyJourneyRepository.Member> selectedMembers,
            @NonNull Map<String, List<FamilyJourneyPoint>> pointsByMember,
            long now
    ) {
        List<FamilyLocationReport.MemberReport> memberReports = new ArrayList<>();
        Map<String, Long> familyMovement = emptyMovementLongMap();
        Map<String, MutablePlace> familyPlaces = new LinkedHashMap<>();
        List<FamilyLocationReport.Insight> familyInsights = new ArrayList<>();

        double totalDistance = 0D;
        long totalMoving = 0L;
        long totalSafe = 0L;
        int activeMemberDays = 0;

        for (FamilyJourneyRepository.Member member : selectedMembers) {
            List<FamilyJourneyPoint> points = pointsByMember.get(member.uid);
            FamilyLocationReport.MemberReport report = analyzeMember(
                    range,
                    member,
                    points == null ? Collections.emptyList() : points,
                    now
            );
            memberReports.add(report);
            totalDistance += report.totalDistanceMeters;
            totalMoving += report.movingDurationMs;
            activeMemberDays += report.activeDays;
            for (Map.Entry<String, Long> entry
                    : report.movementDurationMs.entrySet()) {
                familyMovement.put(
                        entry.getKey(),
                        familyMovement.get(entry.getKey()) + entry.getValue()
                );
            }
            for (FamilyLocationReport.PlaceStat place : report.safePlaces) {
                MutablePlace aggregate = familyPlaces.get(place.name);
                if (aggregate == null) {
                    aggregate = new MutablePlace(place.name);
                    familyPlaces.put(place.name, aggregate);
                }
                aggregate.durationMs += place.durationMs;
                aggregate.visitCount += place.visitCount;
                totalSafe += place.durationMs;
            }
        }

        memberReports.sort((first, second) ->
                Double.compare(second.totalDistanceMeters,
                        first.totalDistanceMeters));
        List<FamilyLocationReport.PlaceStat> familyPlaceStats =
                toPlaceStats(familyPlaces);

        if (!memberReports.isEmpty()) {
            FamilyLocationReport.MemberReport mostTravelled = memberReports.get(0);
            if (mostTravelled.totalDistanceMeters > 0D) {
                familyInsights.add(new FamilyLocationReport.Insight(
                        FamilyLocationReport.Insight.ROUTINE,
                        "Highest travel in this period",
                        mostTravelled.displayName + " covered "
                                + formatDistance(mostTravelled.totalDistanceMeters)
                                + "."
                ));
            }
        }
        if (!familyPlaceStats.isEmpty()) {
            FamilyLocationReport.PlaceStat place = familyPlaceStats.get(0);
            familyInsights.add(new FamilyLocationReport.Insight(
                    FamilyLocationReport.Insight.ROUTINE,
                    "Most used Safe Place",
                    place.name + " recorded " + place.visitCount
                            + " visits and " + formatDuration(place.durationMs)
                            + " of detected presence."
            ));
        }
        if (activeMemberDays == 0) {
            familyInsights.add(new FamilyLocationReport.Insight(
                    FamilyLocationReport.Insight.COVERAGE,
                    "No report coverage",
                    "No Journey History samples are available for the selected members and dates."
            ));
        }

        return new FamilyLocationReport(
                range,
                memberReports,
                totalDistance,
                totalMoving,
                totalSafe,
                activeMemberDays,
                familyMovement,
                familyPlaceStats,
                familyInsights,
                now
        );
    }

    @NonNull
    private static FamilyLocationReport.MemberReport analyzeMember(
            @NonNull FamilyReportRange range,
            @NonNull FamilyJourneyRepository.Member member,
            @NonNull List<FamilyJourneyPoint> source,
            long now
    ) {
        Map<String, List<FamilyJourneyPoint>> byDay = new LinkedHashMap<>();
        for (String dayKey : range.dayKeys()) {
            byDay.put(dayKey, new ArrayList<>());
        }
        for (FamilyJourneyPoint point : source) {
            if (!range.contains(point.capturedAt)) {
                continue;
            }
            String dayKey = FamilyJourneyPolicy.dayKey(point.capturedAt);
            List<FamilyJourneyPoint> day = byDay.get(dayKey);
            if (day != null) {
                day.add(point);
            }
        }

        double distance = 0D;
        int activeDays = 0;
        int pointCount = 0;
        Map<String, Long> movementDuration = emptyMovementLongMap();
        Map<String, Double> movementDistance = emptyMovementDoubleMap();
        Map<String, MutablePlace> places = new LinkedHashMap<>();
        Map<String, List<Long>> arrivalMinutesByPlace = new LinkedHashMap<>();
        Map<String, Map<String, Long>> arrivalByDay = new LinkedHashMap<>();
        List<String> activeDayKeys = new ArrayList<>();

        for (Map.Entry<String, List<FamilyJourneyPoint>> entry : byDay.entrySet()) {
            List<FamilyJourneyPoint> dayPoints = entry.getValue();
            if (dayPoints.isEmpty()) {
                continue;
            }
            dayPoints.sort(Comparator.comparingLong(point -> point.capturedAt));
            activeDays++;
            activeDayKeys.add(entry.getKey());
            pointCount += dayPoints.size();
            FamilyJourneySummary summary = FamilyJourneySummary.from(dayPoints);
            distance += summary.totalDistanceMeters;

            for (FamilyJourneySummary.MovementSegment segment
                    : summary.segments) {
                String type = FamilyJourneyPolicy.normalizeMovement(
                        segment.movementType
                );
                movementDuration.put(
                        type,
                        movementDuration.get(type) + segment.durationMs()
                );
                movementDistance.put(
                        type,
                        movementDistance.get(type) + segment.distanceMeters
                );
            }

            Map<String, Long> firstArrivalForDay = new LinkedHashMap<>();
            for (FamilyJourneySummary.SafePlaceVisit visit
                    : summary.safePlaceVisits) {
                String placeName = visit.safePlaceName.trim();
                if (placeName.isEmpty()) {
                    continue;
                }
                MutablePlace place = places.get(placeName);
                if (place == null) {
                    place = new MutablePlace(placeName);
                    places.put(placeName, place);
                }
                place.durationMs += visit.durationMs();
                place.visitCount++;
                long minute = minuteOfDay(visit.arrivedAt);
                if (!firstArrivalForDay.containsKey(placeName)) {
                    firstArrivalForDay.put(placeName, minute);
                    List<Long> arrivals = arrivalMinutesByPlace.get(placeName);
                    if (arrivals == null) {
                        arrivals = new ArrayList<>();
                        arrivalMinutesByPlace.put(placeName, arrivals);
                    }
                    arrivals.add(minute);
                }
            }
            arrivalByDay.put(entry.getKey(), firstArrivalForDay);
        }

        long moving = movementDuration.get("WALKING")
                + movementDuration.get("CYCLING")
                + movementDuration.get("TRAVELLING");
        long stationary = movementDuration.get("STATIONARY");
        List<FamilyLocationReport.PlaceStat> placeStats = toPlaceStats(places);
        String mostVisited = placeStats.isEmpty() ? "" : placeStats.get(0).name;
        List<FamilyLocationReport.Insight> insights = buildMemberInsights(
                range,
                member,
                activeDayKeys,
                arrivalMinutesByPlace,
                arrivalByDay,
                placeStats,
                distance,
                now
        );

        if (activeDays > 0) {
            insights.add(0, new FamilyLocationReport.Insight(
                    FamilyLocationReport.Insight.COVERAGE,
                    "Report coverage",
                    activeDays + " of " + range.dayCount()
                            + " selected days contain Journey History samples."
            ));
        }

        return new FamilyLocationReport.MemberReport(
                member.uid,
                member.displayName,
                member.role,
                distance,
                moving,
                stationary,
                activeDays,
                pointCount,
                movementDuration,
                movementDistance,
                placeStats,
                mostVisited,
                insights
        );
    }

    @NonNull
    private static List<FamilyLocationReport.Insight> buildMemberInsights(
            @NonNull FamilyReportRange range,
            @NonNull FamilyJourneyRepository.Member member,
            @NonNull List<String> activeDayKeys,
            @NonNull Map<String, List<Long>> arrivalMinutesByPlace,
            @NonNull Map<String, Map<String, Long>> arrivalByDay,
            @NonNull List<FamilyLocationReport.PlaceStat> places,
            double distanceMeters,
            long now
    ) {
        List<FamilyLocationReport.Insight> insights = new ArrayList<>();
        if (!places.isEmpty()) {
            FamilyLocationReport.PlaceStat most = places.get(0);
            insights.add(new FamilyLocationReport.Insight(
                    FamilyLocationReport.Insight.ROUTINE,
                    "Most visited place",
                    member.displayName + " was detected at " + most.name
                            + " for " + formatDuration(most.durationMs)
                            + " across " + most.visitCount + " visits."
            ));
        }
        if (!activeDayKeys.isEmpty()) {
            double averageDistance = distanceMeters / activeDayKeys.size();
            insights.add(new FamilyLocationReport.Insight(
                    FamilyLocationReport.Insight.ROUTINE,
                    "Average active-day travel",
                    formatDistance(averageDistance)
                            + " per day with available Journey History."
            ));
        }

        if (activeDayKeys.size() < MIN_ROUTINE_DAYS) {
            return insights;
        }
        Collections.sort(activeDayKeys);
        String latestDay = activeDayKeys.get(activeDayKeys.size() - 1);
        Map<String, Long> latestArrivals = arrivalByDay.get(latestDay);
        if (latestArrivals == null) {
            latestArrivals = Collections.emptyMap();
        }

        for (Map.Entry<String, List<Long>> entry
                : arrivalMinutesByPlace.entrySet()) {
            String place = entry.getKey();
            List<Long> arrivals = entry.getValue();
            List<Long> historical = new ArrayList<>();
            for (String dayKey : activeDayKeys) {
                if (dayKey.equals(latestDay)) {
                    continue;
                }
                Map<String, Long> dayArrivals = arrivalByDay.get(dayKey);
                if (dayArrivals != null && dayArrivals.containsKey(place)) {
                    historical.add(dayArrivals.get(place));
                }
            }
            int priorDayCount = Math.max(0, activeDayKeys.size() - 1);
            int required = Math.max(2, (int) Math.ceil(priorDayCount * 0.60D));
            if (historical.size() < required) {
                continue;
            }
            long usualMinute = median(historical);
            Long latestMinute = latestArrivals.get(place);
            if (latestMinute != null
                    && latestMinute - usualMinute >= DELAY_THRESHOLD_MS / MINUTE_MS) {
                insights.add(new FamilyLocationReport.Insight(
                        FamilyLocationReport.Insight.DELAY,
                        "Later than the usual pattern",
                        place + " was reached at " + formatMinute(latestMinute)
                                + ", about "
                                + formatDuration((latestMinute - usualMinute) * MINUTE_MS)
                                + " later than the recent routine."
                ));
                continue;
            }
            if (latestMinute == null && shouldEvaluateMissedVisit(
                    range,
                    latestDay,
                    usualMinute,
                    now
            )) {
                insights.add(new FamilyLocationReport.Insight(
                        FamilyLocationReport.Insight.MISSED_VISIT,
                        "Regular Safe Place not detected",
                        place + " appeared on " + historical.size()
                                + " recent active days but was not detected on "
                                + displayDay(latestDay) + "."
                ));
            }
        }
        return insights;
    }

    private static boolean shouldEvaluateMissedVisit(
            @NonNull FamilyReportRange range,
            @NonNull String latestDay,
            long usualMinute,
            long now
    ) {
        String todayKey = FamilyJourneyPolicy.dayKey(now);
        if (!latestDay.equals(todayKey)) {
            return true;
        }
        long elapsedToday = now - FamilyJourneyPolicy.startOfDay(now);
        return elapsedToday >= usualMinute * MINUTE_MS + MISSED_GRACE_MS
                && range.contains(now);
    }

    @NonNull
    private static Map<String, Long> emptyMovementLongMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("STATIONARY", 0L);
        map.put("WALKING", 0L);
        map.put("CYCLING", 0L);
        map.put("TRAVELLING", 0L);
        map.put("UNKNOWN", 0L);
        return map;
    }

    @NonNull
    private static Map<String, Double> emptyMovementDoubleMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("STATIONARY", 0D);
        map.put("WALKING", 0D);
        map.put("CYCLING", 0D);
        map.put("TRAVELLING", 0D);
        map.put("UNKNOWN", 0D);
        return map;
    }

    @NonNull
    private static List<FamilyLocationReport.PlaceStat> toPlaceStats(
            @NonNull Map<String, MutablePlace> source
    ) {
        List<FamilyLocationReport.PlaceStat> result = new ArrayList<>();
        for (MutablePlace value : source.values()) {
            result.add(new FamilyLocationReport.PlaceStat(
                    value.name,
                    value.durationMs,
                    value.visitCount
            ));
        }
        result.sort((first, second) -> {
            int duration = Long.compare(second.durationMs, first.durationMs);
            return duration != 0
                    ? duration
                    : Integer.compare(second.visitCount, first.visitCount);
        });
        return result;
    }

    private static long minuteOfDay(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60L
                + calendar.get(Calendar.MINUTE);
    }

    private static long median(@NonNull List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    @NonNull
    private static String formatMinute(long minute) {
        long safe = Math.max(0L, Math.min(1_439L, minute));
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, (int) (safe / 60L));
        calendar.set(Calendar.MINUTE, (int) (safe % 60L));
        return new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(calendar.getTime());
    }

    @NonNull
    private static String displayDay(@NonNull String dayKey) {
        try {
            Date date = new SimpleDateFormat("yyyyMMdd", Locale.US).parse(dayKey);
            return date == null
                    ? dayKey
                    : new SimpleDateFormat("dd MMM", Locale.getDefault())
                    .format(date);
        } catch (Exception ignored) {
            return dayKey;
        }
    }

    @NonNull
    public static String formatDistance(double meters) {
        if (meters < 1_000D) {
            return Math.round(Math.max(0D, meters)) + " m";
        }
        return String.format(Locale.getDefault(), "%.1f km", meters / 1_000D);
    }

    @NonNull
    public static String formatDuration(long durationMs) {
        long minutes = Math.max(0L, durationMs / MINUTE_MS);
        long hours = minutes / 60L;
        long remaining = minutes % 60L;
        if (hours <= 0L) {
            return minutes + " min";
        }
        return hours + " h " + remaining + " min";
    }

    private static final class MutablePlace {
        @NonNull final String name;
        long durationMs;
        int visitCount;

        MutablePlace(@NonNull String name) {
            this.name = name;
        }
    }
}
