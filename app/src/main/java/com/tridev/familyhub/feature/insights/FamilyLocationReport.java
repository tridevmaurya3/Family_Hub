package com.tridev.familyhub.feature.insights;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable analytics result used by charts, insights and exports. */
public final class FamilyLocationReport {

    public static final class PlaceStat {
        @NonNull public final String name;
        public final long durationMs;
        public final int visitCount;

        public PlaceStat(@NonNull String name, long durationMs, int visitCount) {
            this.name = name;
            this.durationMs = Math.max(0L, durationMs);
            this.visitCount = Math.max(0, visitCount);
        }
    }

    public static final class Insight {
        public static final String ROUTINE = "ROUTINE";
        public static final String DELAY = "DELAY";
        public static final String MISSED_VISIT = "MISSED_VISIT";
        public static final String COVERAGE = "COVERAGE";

        @NonNull public final String type;
        @NonNull public final String title;
        @NonNull public final String detail;

        public Insight(
                @NonNull String type,
                @NonNull String title,
                @NonNull String detail
        ) {
            this.type = type;
            this.title = title;
            this.detail = detail;
        }
    }

    public static final class MemberReport {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String role;
        public final double totalDistanceMeters;
        public final long movingDurationMs;
        public final long stationaryDurationMs;
        public final int activeDays;
        public final int routePointCount;
        @NonNull public final Map<String, Long> movementDurationMs;
        @NonNull public final Map<String, Double> movementDistanceMeters;
        @NonNull public final List<PlaceStat> safePlaces;
        @NonNull public final String mostVisitedPlace;
        @NonNull public final List<Insight> insights;

        public MemberReport(
                @NonNull String uid,
                @NonNull String displayName,
                @NonNull String role,
                double totalDistanceMeters,
                long movingDurationMs,
                long stationaryDurationMs,
                int activeDays,
                int routePointCount,
                @NonNull Map<String, Long> movementDurationMs,
                @NonNull Map<String, Double> movementDistanceMeters,
                @NonNull List<PlaceStat> safePlaces,
                @NonNull String mostVisitedPlace,
                @NonNull List<Insight> insights
        ) {
            this.uid = uid;
            this.displayName = displayName;
            this.role = role;
            this.totalDistanceMeters = Math.max(0D, totalDistanceMeters);
            this.movingDurationMs = Math.max(0L, movingDurationMs);
            this.stationaryDurationMs = Math.max(0L, stationaryDurationMs);
            this.activeDays = Math.max(0, activeDays);
            this.routePointCount = Math.max(0, routePointCount);
            this.movementDurationMs = Collections.unmodifiableMap(movementDurationMs);
            this.movementDistanceMeters = Collections.unmodifiableMap(
                    movementDistanceMeters
            );
            this.safePlaces = Collections.unmodifiableList(safePlaces);
            this.mostVisitedPlace = mostVisitedPlace;
            this.insights = Collections.unmodifiableList(insights);
        }
    }

    @NonNull public final FamilyReportRange range;
    @NonNull public final List<MemberReport> members;
    public final double totalDistanceMeters;
    public final long totalMovingDurationMs;
    public final long totalSafePlaceDurationMs;
    public final int activeMemberDays;
    @NonNull public final Map<String, Long> movementDurationMs;
    @NonNull public final List<PlaceStat> familySafePlaces;
    @NonNull public final List<Insight> familyInsights;
    public final long generatedAt;

    public FamilyLocationReport(
            @NonNull FamilyReportRange range,
            @NonNull List<MemberReport> members,
            double totalDistanceMeters,
            long totalMovingDurationMs,
            long totalSafePlaceDurationMs,
            int activeMemberDays,
            @NonNull Map<String, Long> movementDurationMs,
            @NonNull List<PlaceStat> familySafePlaces,
            @NonNull List<Insight> familyInsights,
            long generatedAt
    ) {
        this.range = range;
        this.members = Collections.unmodifiableList(members);
        this.totalDistanceMeters = Math.max(0D, totalDistanceMeters);
        this.totalMovingDurationMs = Math.max(0L, totalMovingDurationMs);
        this.totalSafePlaceDurationMs = Math.max(0L, totalSafePlaceDurationMs);
        this.activeMemberDays = Math.max(0, activeMemberDays);
        this.movementDurationMs = Collections.unmodifiableMap(movementDurationMs);
        this.familySafePlaces = Collections.unmodifiableList(familySafePlaces);
        this.familyInsights = Collections.unmodifiableList(familyInsights);
        this.generatedAt = generatedAt;
    }
}
