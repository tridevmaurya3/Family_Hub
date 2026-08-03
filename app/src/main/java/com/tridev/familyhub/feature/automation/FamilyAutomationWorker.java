package com.tridev.familyhub.feature.automation;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationSharingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Durable evaluator for scheduled sharing and time-based routine alerts. */
public final class FamilyAutomationWorker extends Worker {

    private static final long FIREBASE_TIMEOUT_SECONDS = 20L;

    public FamilyAutomationWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            return Result.success();
        }

        Context context = getApplicationContext();
        List<FamilyAutomationRule> cached = FamilyAutomationRuleCache.load(
                context,
                user.getUid()
        );
        Session session;
        List<FamilyAutomationRule> rules;
        DataSnapshot location = null;
        try {
            DatabaseReference root = FirebaseDatabase.getInstance()
                    .getReference();
            DataSnapshot userSnapshot = Tasks.await(
                    root.child("users").child(user.getUid()).get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            String familyId = stringValue(userSnapshot.child("familyId"));
            String status = stringValue(userSnapshot.child("status"));
            if (familyId.isEmpty() || !"ACTIVE".equals(status)) {
                return Result.success();
            }
            DataSnapshot membership = Tasks.await(
                    root.child("memberships")
                            .child(familyId)
                            .child(user.getUid())
                            .get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!"ACTIVE".equals(stringValue(membership.child("status")))) {
                return Result.success();
            }
            String targetName = stringValue(
                    membership.child("displayName")
            );
            if (targetName.isEmpty()) {
                targetName = user.getDisplayName();
            }
            if (targetName == null || targetName.trim().isEmpty()) {
                targetName = "Family member";
            }
            session = new Session(
                    user.getUid(),
                    familyId,
                    targetName.trim()
            );

            DataSnapshot rulesSnapshot = Tasks.await(
                    root.child("familyAutomationRules")
                            .child(familyId)
                            .child(user.getUid())
                            .get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            rules = parseRules(rulesSnapshot, user.getUid());
            FamilyAutomationRuleCache.save(context, user.getUid(), rules);
            try {
                location = Tasks.await(
                        root.child("locations")
                                .child(familyId)
                                .child(user.getUid())
                                .get(),
                        FIREBASE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
            } catch (Exception ignored) {
                location = null;
            }
        } catch (Exception remoteError) {
            if (cached.isEmpty()) {
                return Result.retry();
            }
            FamilyAutomationRule first = cached.get(0);
            session = new Session(
                    user.getUid(),
                    first.familyId,
                    first.targetName.isEmpty()
                            ? "Family member"
                            : first.targetName
            );
            rules = cached;
        }

        long now = System.currentTimeMillis();
        BatterySnapshot battery = readBattery(context);
        evaluateScheduledSharing(
                context,
                session,
                rules,
                battery,
                now
        );
        if (location != null) {
            evaluateRoutineDeadlines(
                    context,
                    session,
                    rules,
                    location,
                    now
            );
        }
        FamilyAutomationScheduler.scheduleNextBoundary(
                context,
                rules,
                now
        );
        return Result.success();
    }

    private void evaluateScheduledSharing(
            @NonNull Context context,
            @NonNull Session session,
            @NonNull List<FamilyAutomationRule> rules,
            @NonNull BatterySnapshot battery,
            long now
    ) {
        FamilyAutomationRule activeRule = null;
        FamilyAutomationRule anySchedule = null;
        for (FamilyAutomationRule rule : rules) {
            if (!rule.enabled || !rule.isScheduledSharing()) {
                continue;
            }
            if (anySchedule == null) {
                anySchedule = rule;
            }
            if (FamilyAutomationPolicy.shouldRunSharingWindow(rule, now)) {
                activeRule = rule;
                break;
            }
        }

        FamilyAutomationStateStore state =
                new FamilyAutomationStateStore(context);
        boolean sharingEnabled = LocationSharingStore.isSharingEnabled(context);
        if (activeRule != null) {
            if (sharingEnabled) {
                return;
            }
            if (FamilyAutomationPolicy.shouldPauseAutomaticStart(
                    battery.percentage,
                    battery.charging
            )) {
                String dayKey = FamilyAutomationPolicy.dayKey(now);
                FamilyAutomationEventWriter.dispatch(
                        context,
                        session.familyId,
                        session.uid,
                        session.targetName,
                        activeRule,
                        FamilyAutomationEvent.EVENT_LOW_BATTERY_PAUSED,
                        FamilyAutomationEvent.SEVERITY_WARNING,
                        "",
                        "Scheduled sharing was delayed because battery is "
                                + battery.percentage + "%.",
                        dayKey + ":" + activeRule.ruleId + ":battery",
                        activeRule.notifyTrustedViewers,
                        now
                );
                return;
            }

            state.setSharingStartedByAutomation(true);
            try {
                ContextCompat.startForegroundService(
                        context,
                        FamilyLocationService.startIntent(context)
                );
                FamilyAutomationEventWriter.dispatch(
                        context,
                        session.familyId,
                        session.uid,
                        session.targetName,
                        activeRule,
                        FamilyAutomationEvent.EVENT_SHARING_STARTED,
                        FamilyAutomationEvent.SEVERITY_INFO,
                        "",
                        "Scheduled Family Live sharing started.",
                        FamilyAutomationPolicy.dayKey(now)
                                + ":" + activeRule.ruleId + ":start",
                        activeRule.notifyTrustedViewers,
                        now
                );
            } catch (RuntimeException blocked) {
                state.setSharingStartedByAutomation(false);
            }
            return;
        }

        if (!state.sharingStartedByAutomation()) {
            return;
        }
        state.setSharingStartedByAutomation(false);
        if (sharingEnabled) {
            try {
                context.startService(FamilyLocationService.stopIntent(context));
            } catch (RuntimeException blocked) {
                try {
                    ContextCompat.startForegroundService(
                            context,
                            FamilyLocationService.stopIntent(context)
                    );
                } catch (RuntimeException ignored) {
                    return;
                }
            }
        }
        FamilyAutomationEventWriter.dispatch(
                context,
                session.familyId,
                session.uid,
                session.targetName,
                anySchedule,
                FamilyAutomationEvent.EVENT_SHARING_STOPPED,
                FamilyAutomationEvent.SEVERITY_INFO,
                "",
                "Scheduled Family Live sharing ended.",
                FamilyAutomationPolicy.dayKey(now)
                        + ":sharing-stop:" + now / 900_000L,
                anySchedule == null || anySchedule.notifyTrustedViewers,
                now
        );
    }

    private void evaluateRoutineDeadlines(
            @NonNull Context context,
            @NonNull Session session,
            @NonNull List<FamilyAutomationRule> rules,
            @NonNull DataSnapshot location,
            long now
    ) {
        if (!booleanValue(location.child("sharingEnabled"), false)
                || !"RELIABLE".equals(stringValue(
                location.child("locationQuality")
        ))) {
            return;
        }
        Double latitude = FirebaseNumericValueReader.doubleValue(
                location.child("latitude")
        );
        Double longitude = FirebaseNumericValueReader.doubleValue(
                location.child("longitude")
        );
        long locationAt = FirebaseNumericValueReader.nonNegativeLong(
                location.child("clientTimestamp"),
                0L
        );
        if (locationAt <= 0L) {
            locationAt = FirebaseNumericValueReader.nonNegativeLong(
                    location.child("updatedAt"),
                    0L
            );
        }
        if (latitude == null
                || longitude == null
                || !FamilyAutomationPolicy.isFreshLocation(locationAt, now)) {
            return;
        }

        FamilyAutomationStateStore state =
                new FamilyAutomationStateStore(context);
        String dayKey = FamilyAutomationPolicy.dayKey(now);
        for (FamilyAutomationRule rule : rules) {
            if (!rule.enabled
                    || !rule.isPlaceRule()
                    || !FamilyAutomationPolicy.isDayEnabled(rule.daysMask, now)) {
                continue;
            }
            boolean inside = FamilyAutomationPolicy.insidePlace(
                    latitude,
                    longitude,
                    rule
            );
            boolean previousInside = state.wasInside(rule.ruleId, inside);
            if (inside != previousInside) {
                String type = inside
                        ? FamilyAutomationEvent.EVENT_ARRIVED
                        : FamilyAutomationEvent.EVENT_DEPARTED;
                FamilyAutomationEventWriter.dispatch(
                        context,
                        session.familyId,
                        session.uid,
                        session.targetName,
                        rule,
                        type,
                        FamilyAutomationEvent.SEVERITY_INFO,
                        rule.placeName,
                        inside
                                ? session.targetName + " arrived at "
                                + rule.placeName + "."
                                : session.targetName + " left "
                                + rule.placeName + ".",
                        dayKey + ":" + rule.ruleId + ":" + type,
                        rule.notifyTrustedViewers,
                        now
                );
                state.markRuleEventForDay(rule.ruleId, type, dayKey);
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

            if (!FamilyAutomationPolicy.isLateWindow(rule, now)) {
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
                FamilyAutomationEventWriter.dispatch(
                        context,
                        session.familyId,
                        session.uid,
                        session.targetName,
                        rule,
                        FamilyAutomationEvent.EVENT_LATE,
                        FamilyAutomationEvent.SEVERITY_WARNING,
                        rule.placeName,
                        session.targetName + " has not reached "
                                + rule.placeName
                                + " within the expected time.",
                        dayKey + ":" + rule.ruleId + ":LATE",
                        rule.notifyTrustedViewers,
                        now
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
                FamilyAutomationEventWriter.dispatch(
                        context,
                        session.familyId,
                        session.uid,
                        session.targetName,
                        rule,
                        FamilyAutomationEvent.EVENT_MISSED,
                        FamilyAutomationEvent.SEVERITY_WARNING,
                        rule.placeName,
                        session.targetName + " has not left "
                                + rule.placeName
                                + " within the expected time.",
                        dayKey + ":" + rule.ruleId + ":MISSED",
                        rule.notifyTrustedViewers,
                        now
                );
                state.markRuleEventForDay(
                        rule.ruleId,
                        FamilyAutomationEvent.EVENT_MISSED,
                        dayKey
                );
            }
        }
    }

    @NonNull
    private List<FamilyAutomationRule> parseRules(
            @NonNull DataSnapshot snapshot,
            @NonNull String uid
    ) {
        List<FamilyAutomationRule> rules = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            FamilyAutomationRule rule = child.getValue(
                    FamilyAutomationRule.class
            );
            if (rule == null) {
                continue;
            }
            if ((rule.ruleId == null || rule.ruleId.trim().isEmpty())
                    && child.getKey() != null) {
                rule.ruleId = child.getKey();
            }
            if (uid.equals(rule.targetUid)
                    && FamilyAutomationPolicy.validRule(rule)) {
                rules.add(rule);
            }
        }
        return rules;
    }

    @NonNull
    private BatterySnapshot readBattery(@NonNull Context context) {
        Intent battery = context.registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        if (battery == null) {
            return new BatterySnapshot(-1, false);
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
        );
        int percentage = level >= 0 && scale > 0
                ? Math.min(100, Math.round(level * 100F / scale))
                : -1;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return new BatterySnapshot(percentage, charging);
    }

    private static final class Session {
        @NonNull final String uid;
        @NonNull final String familyId;
        @NonNull final String targetName;

        Session(
                @NonNull String uid,
                @NonNull String familyId,
                @NonNull String targetName
        ) {
            this.uid = uid;
            this.familyId = familyId;
            this.targetName = targetName;
        }
    }

    private static final class BatterySnapshot {
        final int percentage;
        final boolean charging;

        BatterySnapshot(int percentage, boolean charging) {
            this.percentage = percentage;
            this.charging = charging;
        }
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }
}
