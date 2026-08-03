package com.tridev.familyhub.feature.automation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates the signed-in member's own rules from reliable Family Live points.
 * It never starts location tracking; scheduled sharing is handled separately.
 */
public final class FamilyAutomationRuntime {

    private static final FamilyAutomationRuntime INSTANCE =
            new FamilyAutomationRuntime();

    @Nullable private Context appContext;
    @Nullable private FirebaseAuth.AuthStateListener authListener;
    @Nullable private DatabaseReference rulesReference;
    @Nullable private ValueEventListener rulesListener;
    @Nullable private DatabaseReference locationReference;
    @Nullable private ValueEventListener locationListener;
    @Nullable private String uid;
    @Nullable private String familyId;
    @NonNull private String targetName = "Family member";
    @NonNull private List<FamilyAutomationRule> rules = new ArrayList<>();
    private int generation;

    private FamilyAutomationRuntime() {
    }

    public static void start(@NonNull Context context) {
        INSTANCE.startInternal(context.getApplicationContext());
    }

    private synchronized void startInternal(@NonNull Context context) {
        appContext = context;
        if (authListener != null) {
            return;
        }
        authListener = auth -> attachForUser(auth.getCurrentUser());
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
    }

    private synchronized void attachForUser(@Nullable FirebaseUser user) {
        detachFirebaseListeners();
        uid = null;
        familyId = null;
        rules = new ArrayList<>();
        if (user == null || !user.isEmailVerified() || appContext == null) {
            return;
        }
        uid = user.getUid();
        int requestGeneration = ++generation;
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("users").child(user.getUid()).get()
                .addOnSuccessListener(userSnapshot -> {
                    if (requestGeneration != generation) {
                        return;
                    }
                    String resolvedFamilyId = stringValue(
                            userSnapshot.child("familyId")
                    );
                    String status = stringValue(
                            userSnapshot.child("status")
                    );
                    if (resolvedFamilyId.isEmpty()
                            || !"ACTIVE".equals(status)) {
                        return;
                    }
                    root.child("memberships")
                            .child(resolvedFamilyId)
                            .child(user.getUid())
                            .get()
                            .addOnSuccessListener(membership -> {
                                if (requestGeneration != generation
                                        || !"ACTIVE".equals(stringValue(
                                        membership.child("status")
                                ))) {
                                    return;
                                }
                                familyId = resolvedFamilyId;
                                String name = stringValue(
                                        membership.child("displayName")
                                );
                                targetName = name.isEmpty()
                                        ? "Family member"
                                        : name;
                                attachRules(requestGeneration);
                                attachLocation(requestGeneration);
                            });
                });
    }

    private synchronized void attachRules(int requestGeneration) {
        String activeFamilyId = familyId;
        String activeUid = uid;
        Context context = appContext;
        if (activeFamilyId == null
                || activeUid == null
                || context == null) {
            return;
        }
        rulesReference = FirebaseDatabase.getInstance()
                .getReference()
                .child("familyAutomationRules")
                .child(activeFamilyId)
                .child(activeUid);
        rulesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (requestGeneration != generation) {
                    return;
                }
                List<FamilyAutomationRule> loaded = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    FamilyAutomationRule rule = child.getValue(
                            FamilyAutomationRule.class
                    );
                    if (rule == null) {
                        continue;
                    }
                    if ((rule.ruleId == null
                            || rule.ruleId.trim().isEmpty())
                            && child.getKey() != null) {
                        rule.ruleId = child.getKey();
                    }
                    if (activeUid.equals(rule.targetUid)
                            && FamilyAutomationPolicy.validRule(rule)) {
                        loaded.add(rule);
                    }
                }
                rules = loaded;
                FamilyAutomationRuleCache.save(
                        context,
                        activeUid,
                        loaded
                );
                FamilyAutomationScheduler.enable(context);
                FamilyAutomationScheduler.scheduleNow(context);
                FamilyAutomationScheduler.scheduleNextBoundary(
                        context,
                        loaded,
                        System.currentTimeMillis()
                );
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                rules = FamilyAutomationRuleCache.load(context, activeUid);
            }
        };
        rulesReference.addValueEventListener(rulesListener);
    }

    private synchronized void attachLocation(int requestGeneration) {
        String activeFamilyId = familyId;
        String activeUid = uid;
        if (activeFamilyId == null || activeUid == null) {
            return;
        }
        locationReference = FirebaseDatabase.getInstance()
                .getReference()
                .child("locations")
                .child(activeFamilyId)
                .child(activeUid);
        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (requestGeneration != generation) {
                    return;
                }
                evaluateLocation(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Existing Family Live service continues independently.
            }
        };
        locationReference.addValueEventListener(locationListener);
    }

    private void evaluateLocation(@NonNull DataSnapshot snapshot) {
        Context context = appContext;
        String activeUid = uid;
        String activeFamilyId = familyId;
        if (context == null
                || activeUid == null
                || activeFamilyId == null
                || !booleanValue(snapshot.child("sharingEnabled"), false)
                || !"RELIABLE".equals(stringValue(
                snapshot.child("locationQuality")
        ))) {
            return;
        }

        Double latitude = numberValue(snapshot.child("latitude"));
        Double longitude = numberValue(snapshot.child("longitude"));
        long capturedAt = longValue(snapshot.child("clientTimestamp"));
        if (capturedAt <= 0L) {
            capturedAt = longValue(snapshot.child("updatedAt"));
        }
        long now = System.currentTimeMillis();
        if (latitude == null
                || longitude == null
                || !FamilyAutomationPolicy.validCoordinate(
                latitude,
                longitude
        )
                || !FamilyAutomationPolicy.isFreshLocation(
                capturedAt,
                now
        )) {
            return;
        }

        String movement = stringValue(snapshot.child("movementType"));
        String placeLabel = stringValue(snapshot.child("placeLabel"));
        int battery = intValue(snapshot.child("batteryPercentage"), -1);
        boolean charging = booleanValue(snapshot.child("charging"), false);
        List<FamilyAutomationRule> currentRules = new ArrayList<>(rules);
        FamilyAutomationStateStore state =
                new FamilyAutomationStateStore(context);

        evaluatePlaceRules(
                context,
                state,
                currentRules,
                activeFamilyId,
                activeUid,
                latitude,
                longitude,
                capturedAt
        );
        evaluateTrip(
                context,
                state,
                activeFamilyId,
                activeUid,
                movement,
                placeLabel,
                battery,
                charging,
                capturedAt
        );
    }

    private void evaluatePlaceRules(
            @NonNull Context context,
            @NonNull FamilyAutomationStateStore state,
            @NonNull List<FamilyAutomationRule> currentRules,
            @NonNull String activeFamilyId,
            @NonNull String activeUid,
            double latitude,
            double longitude,
            long capturedAt
    ) {
        String dayKey = FamilyAutomationPolicy.dayKey(capturedAt);
        for (FamilyAutomationRule rule : currentRules) {
            if (!rule.enabled
                    || !rule.isPlaceRule()
                    || !FamilyAutomationPolicy.isDayEnabled(
                    rule.daysMask,
                    capturedAt
            )) {
                continue;
            }
            boolean inside = FamilyAutomationPolicy.insidePlace(
                    latitude,
                    longitude,
                    rule
            );
            boolean previousInside = state.wasInside(rule.ruleId, inside);
            if (inside != previousInside) {
                String eventType = inside
                        ? FamilyAutomationEvent.EVENT_ARRIVED
                        : FamilyAutomationEvent.EVENT_DEPARTED;
                String detail = inside
                        ? targetName + " arrived at " + rule.placeName + "."
                        : targetName + " left " + rule.placeName + ".";
                dispatchRuleEvent(
                        context,
                        activeFamilyId,
                        activeUid,
                        rule,
                        eventType,
                        FamilyAutomationEvent.SEVERITY_INFO,
                        detail,
                        dayKey + ":" + rule.ruleId + ":" + eventType,
                        capturedAt
                );
                state.markRuleEventForDay(
                        rule.ruleId,
                        eventType,
                        dayKey
                );
            }
            state.setInside(rule.ruleId, inside);

            if (inside) {
                state.markRuleEventForDay(
                        rule.ruleId,
                        FamilyAutomationEvent.EVENT_ARRIVED,
                        dayKey
                );
            } else {
                state.markRuleEventForDay(
                        rule.ruleId,
                        FamilyAutomationEvent.EVENT_DEPARTED,
                        dayKey
                );
            }

            if (!FamilyAutomationPolicy.isLateWindow(rule, capturedAt)) {
                continue;
            }

            if ((FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL.equals(rule.type)
                    || FamilyAutomationRule.TYPE_LATE_RETURN.equals(rule.type))
                    && !inside
                    && !state.hasRuleEventForDay(
                    rule.ruleId,
                    FamilyAutomationEvent.EVENT_LATE,
                    dayKey
            )) {
                String detail = targetName + " has not reached "
                        + rule.placeName + " within the expected time.";
                dispatchRuleEvent(
                        context,
                        activeFamilyId,
                        activeUid,
                        rule,
                        FamilyAutomationEvent.EVENT_LATE,
                        FamilyAutomationEvent.SEVERITY_WARNING,
                        detail,
                        dayKey + ":" + rule.ruleId + ":LATE",
                        capturedAt
                );
                state.markRuleEventForDay(
                        rule.ruleId,
                        FamilyAutomationEvent.EVENT_LATE,
                        dayKey
                );
            }

            if (FamilyAutomationRule.TYPE_EXPECTED_DEPARTURE.equals(rule.type)
                    && inside
                    && !state.hasRuleEventForDay(
                    rule.ruleId,
                    FamilyAutomationEvent.EVENT_MISSED,
                    dayKey
            )) {
                String detail = targetName + " has not left "
                        + rule.placeName + " within the expected time.";
                dispatchRuleEvent(
                        context,
                        activeFamilyId,
                        activeUid,
                        rule,
                        FamilyAutomationEvent.EVENT_MISSED,
                        FamilyAutomationEvent.SEVERITY_WARNING,
                        detail,
                        dayKey + ":" + rule.ruleId + ":MISSED",
                        capturedAt
                );
                state.markRuleEventForDay(
                        rule.ruleId,
                        FamilyAutomationEvent.EVENT_MISSED,
                        dayKey
                );
            }
        }
    }

    private void evaluateTrip(
            @NonNull Context context,
            @NonNull FamilyAutomationStateStore state,
            @NonNull String activeFamilyId,
            @NonNull String activeUid,
            @NonNull String movement,
            @NonNull String placeLabel,
            int battery,
            boolean charging,
            long capturedAt
    ) {
        boolean tripActive = state.tripActive();
        String previousMovement = state.previousMovement();
        long lastMovingAt = state.tripLastMovingAt();

        if (FamilyAutomationPolicy.shouldStartTrip(
                tripActive,
                previousMovement,
                movement
        )) {
            state.startTrip(capturedAt, placeLabel);
            String detail = targetName + " started a trip"
                    + (placeLabel.isEmpty() ? "." : " near " + placeLabel + ".");
            FamilyAutomationEventWriter.dispatch(
                    context,
                    activeFamilyId,
                    activeUid,
                    targetName,
                    null,
                    FamilyAutomationEvent.EVENT_TRIP_STARTED,
                    FamilyAutomationEvent.SEVERITY_INFO,
                    placeLabel,
                    detail,
                    "trip-start:" + capturedAt / 300_000L,
                    true,
                    capturedAt
            );
            tripActive = true;
        }

        if (FamilyAutomationPolicy.shouldEndTrip(
                tripActive,
                lastMovingAt,
                movement,
                capturedAt
        )) {
            long duration = Math.max(0L,
                    capturedAt - state.tripStartedAt());
            String detail = targetName + " ended a trip after "
                    + formatDuration(duration)
                    + (placeLabel.isEmpty() ? "." : " near " + placeLabel + ".");
            FamilyAutomationEventWriter.dispatch(
                    context,
                    activeFamilyId,
                    activeUid,
                    targetName,
                    null,
                    FamilyAutomationEvent.EVENT_TRIP_ENDED,
                    FamilyAutomationEvent.SEVERITY_INFO,
                    placeLabel,
                    detail,
                    "trip-end:" + capturedAt / 300_000L,
                    true,
                    capturedAt
            );
            state.endTrip();
        }

        if (FamilyAutomationPolicy.isLowBattery(battery, charging)) {
            // AdaptiveLocationPolicy already reduces GPS frequency. Runtime
            // records no extra event here to avoid duplicate low-battery noise.
        }
        state.updateMovement(movement, capturedAt);
    }

    private void dispatchRuleEvent(
            @NonNull Context context,
            @NonNull String activeFamilyId,
            @NonNull String activeUid,
            @NonNull FamilyAutomationRule rule,
            @NonNull String eventType,
            @NonNull String severity,
            @NonNull String detail,
            @NonNull String deduplicationKey,
            long occurredAt
    ) {
        FamilyAutomationEventWriter.dispatch(
                context,
                activeFamilyId,
                activeUid,
                targetName,
                rule,
                eventType,
                severity,
                rule.placeName,
                detail,
                deduplicationKey,
                rule.notifyTrustedViewers,
                occurredAt
        );
    }

    @NonNull
    private String formatDuration(long durationMs) {
        long minutes = Math.max(1L, durationMs / 60_000L);
        if (minutes < 60L) {
            return minutes + " min";
        }
        long hours = minutes / 60L;
        long remainder = minutes % 60L;
        return remainder == 0L
                ? hours + " hr"
                : String.format(Locale.getDefault(), "%d hr %d min",
                hours, remainder);
    }

    private synchronized void detachFirebaseListeners() {
        generation++;
        if (rulesReference != null && rulesListener != null) {
            rulesReference.removeEventListener(rulesListener);
        }
        if (locationReference != null && locationListener != null) {
            locationReference.removeEventListener(locationListener);
        }
        rulesReference = null;
        rulesListener = null;
        locationReference = null;
        locationListener = null;
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static long longValue(@NonNull DataSnapshot snapshot) {
        Number value = snapshot.getValue(Number.class);
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    private static int intValue(
            @NonNull DataSnapshot snapshot,
            int fallback
    ) {
        Number value = snapshot.getValue(Number.class);
        return value == null ? fallback : value.intValue();
    }

    @Nullable
    private static Double numberValue(@NonNull DataSnapshot snapshot) {
        Number value = snapshot.getValue(Number.class);
        return value == null ? null : value.doubleValue();
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }
}
