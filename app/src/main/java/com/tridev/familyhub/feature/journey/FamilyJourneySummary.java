package com.tridev.familyhub.feature.journey;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Calculates route distance, movement segments and Safe Place visits. */
public final class FamilyJourneySummary {

    public static final class MovementSegment {
        @NonNull public final String movementType;
        public final long startedAt;
        public final long endedAt;
        public final double distanceMeters;

        MovementSegment(
                @NonNull String movementType,
                long startedAt,
                long endedAt,
                double distanceMeters
        ) {
            this.movementType = movementType;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.distanceMeters = distanceMeters;
        }

        public long durationMs() {
            return Math.max(0L, endedAt - startedAt);
        }
    }

    public static final class SafePlaceVisit {
        @NonNull public final String safePlaceName;
        public final long arrivedAt;
        public final long leftAt;

        SafePlaceVisit(
                @NonNull String safePlaceName,
                long arrivedAt,
                long leftAt
        ) {
            this.safePlaceName = safePlaceName;
            this.arrivedAt = arrivedAt;
            this.leftAt = leftAt;
        }

        public long durationMs() {
            return Math.max(0L, leftAt - arrivedAt);
        }
    }

    @NonNull public final List<FamilyJourneyPoint> points;
    @NonNull public final List<MovementSegment> segments;
    @NonNull public final List<SafePlaceVisit> safePlaceVisits;
    public final double totalDistanceMeters;
    public final long startedAt;
    public final long endedAt;
    @NonNull public final String startPlace;
    @NonNull public final String endPlace;

    private FamilyJourneySummary(
            @NonNull List<FamilyJourneyPoint> points,
            @NonNull List<MovementSegment> segments,
            @NonNull List<SafePlaceVisit> safePlaceVisits,
            double totalDistanceMeters,
            long startedAt,
            long endedAt,
            @NonNull String startPlace,
            @NonNull String endPlace
    ) {
        this.points = points;
        this.segments = segments;
        this.safePlaceVisits = safePlaceVisits;
        this.totalDistanceMeters = totalDistanceMeters;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.startPlace = startPlace;
        this.endPlace = endPlace;
    }

    @NonNull
    public static FamilyJourneySummary from(
            @NonNull List<FamilyJourneyPoint> source
    ) {
        List<FamilyJourneyPoint> points = new ArrayList<>(source);
        points.sort(Comparator.comparingLong(point -> point.capturedAt));
        if (points.isEmpty()) {
            return new FamilyJourneySummary(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0D,
                    0L,
                    0L,
                    "",
                    ""
            );
        }

        double total = 0D;
        for (int index = 1; index < points.size(); index++) {
            FamilyJourneyPoint previous = points.get(index - 1);
            FamilyJourneyPoint current = points.get(index);
            double distance = FamilyJourneyPolicy.distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    current.latitude,
                    current.longitude
            );
            long gap = current.capturedAt - previous.capturedAt;
            if (FamilyJourneyPolicy.plausibleTransition(distance, gap)) {
                total += distance;
            }
        }

        return new FamilyJourneySummary(
                Collections.unmodifiableList(points),
                Collections.unmodifiableList(buildSegments(points)),
                Collections.unmodifiableList(buildSafePlaceVisits(points)),
                total,
                points.get(0).capturedAt,
                points.get(points.size() - 1).capturedAt,
                displayPlace(points.get(0)),
                displayPlace(points.get(points.size() - 1))
        );
    }

    @NonNull
    private static List<MovementSegment> buildSegments(
            @NonNull List<FamilyJourneyPoint> points
    ) {
        List<MovementSegment> segments = new ArrayList<>();
        if (points.isEmpty()) {
            return segments;
        }

        String currentType = FamilyJourneyPolicy.normalizeMovement(
                points.get(0).movementType
        );
        long startedAt = points.get(0).capturedAt;
        double distance = 0D;

        for (int index = 1; index < points.size(); index++) {
            FamilyJourneyPoint previous = points.get(index - 1);
            FamilyJourneyPoint current = points.get(index);
            String nextType = FamilyJourneyPolicy.normalizeMovement(
                    current.movementType
            );
            double leg = FamilyJourneyPolicy.distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    current.latitude,
                    current.longitude
            );
            long gap = current.capturedAt - previous.capturedAt;
            if (FamilyJourneyPolicy.plausibleTransition(leg, gap)) {
                distance += leg;
            }

            boolean longGap = gap > 20L * 60L * 1000L;
            if (!currentType.equals(nextType) || longGap) {
                segments.add(new MovementSegment(
                        currentType,
                        startedAt,
                        previous.capturedAt,
                        distance
                ));
                currentType = nextType;
                startedAt = current.capturedAt;
                distance = 0D;
            }
        }

        segments.add(new MovementSegment(
                currentType,
                startedAt,
                points.get(points.size() - 1).capturedAt,
                distance
        ));
        return segments;
    }

    @NonNull
    private static List<SafePlaceVisit> buildSafePlaceVisits(
            @NonNull List<FamilyJourneyPoint> points
    ) {
        List<SafePlaceVisit> visits = new ArrayList<>();
        String activeName = "";
        long arrivedAt = 0L;
        long lastSeenAt = 0L;

        for (FamilyJourneyPoint point : points) {
            String name = point.safePlaceName == null
                    ? ""
                    : point.safePlaceName.trim();
            if (name.equals(activeName)) {
                if (!name.isEmpty()) {
                    lastSeenAt = point.capturedAt;
                }
                continue;
            }

            if (!activeName.isEmpty()) {
                visits.add(new SafePlaceVisit(
                        activeName,
                        arrivedAt,
                        Math.max(arrivedAt, lastSeenAt)
                ));
            }

            activeName = name;
            arrivedAt = name.isEmpty() ? 0L : point.capturedAt;
            lastSeenAt = arrivedAt;
        }

        if (!activeName.isEmpty()) {
            visits.add(new SafePlaceVisit(
                    activeName,
                    arrivedAt,
                    Math.max(arrivedAt, lastSeenAt)
            ));
        }
        return visits;
    }

    @NonNull
    private static String displayPlace(@NonNull FamilyJourneyPoint point) {
        if (point.safePlaceName != null && !point.safePlaceName.trim().isEmpty()) {
            return point.safePlaceName.trim();
        }
        if (point.placeLabel != null && !point.placeLabel.trim().isEmpty()) {
            return point.placeLabel.trim();
        }
        return String.format(
                java.util.Locale.US,
                "%.5f, %.5f",
                point.latitude,
                point.longitude
        );
    }
}
