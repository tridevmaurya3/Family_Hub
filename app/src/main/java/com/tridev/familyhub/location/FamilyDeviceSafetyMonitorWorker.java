package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;

import java.util.concurrent.TimeUnit;

/**
 * Periodically reviews authorised family location metadata for safety issues.
 * It stores no coordinates and skips the signed-in viewer's own device.
 */
public final class FamilyDeviceSafetyMonitorWorker extends Worker {

    private static final long FIREBASE_TIMEOUT_SECONDS = 25L;

    public FamilyDeviceSafetyMonitorWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser viewer = FirebaseAuth.getInstance().getCurrentUser();
        if (viewer == null || !viewer.isEmailVerified()) {
            return Result.success();
        }

        Context context = getApplicationContext();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        try {
            DataSnapshot userSnapshot = Tasks.await(
                    root.child("users").child(viewer.getUid()).get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            String familyId = userSnapshot.child("familyId")
                    .getValue(String.class);
            String status = userSnapshot.child("status")
                    .getValue(String.class);
            if (familyId == null
                    || familyId.trim().isEmpty()
                    || !"ACTIVE".equals(status)) {
                return Result.success();
            }
            familyId = familyId.trim();

            DataSnapshot viewerMembership = Tasks.await(
                    root.child("memberships")
                            .child(familyId)
                            .child(viewer.getUid())
                            .get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!viewer.getUid().equals(stringValue(
                    viewerMembership.child("uid")
            )) || !"ACTIVE".equals(stringValue(
                    viewerMembership.child("status")
            ))) {
                return Result.success();
            }
            String viewerRole = stringValue(
                    viewerMembership.child("role")
            );
            if (!"OWNER_ADMIN".equals(viewerRole)
                    && !"GUARDIAN".equals(viewerRole)) {
                return Result.success();
            }

            DataSnapshot memberships = Tasks.await(
                    root.child("memberships").child(familyId).get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            evaluateMembers(
                    context,
                    viewer.getUid(),
                    memberships,
                    root,
                    familyId,
                    System.currentTimeMillis()
            );
            return Result.success();
        } catch (Exception error) {
            return Result.retry();
        }
    }

    private void evaluateMembers(
            @NonNull Context context,
            @NonNull String viewerUid,
            @NonNull DataSnapshot memberships,
            @NonNull DatabaseReference root,
            @NonNull String familyId,
            long now
    ) throws Exception {
        FamilyDeviceSafetyAlertStateStore stateStore =
                new FamilyDeviceSafetyAlertStateStore(context);

        for (DataSnapshot membership : memberships.getChildren()) {
            String memberUid = stringValue(membership.child("uid"));
            String memberStatus = stringValue(membership.child("status"));
            if (memberUid.isEmpty()
                    || !memberUid.equals(membership.getKey())
                    || viewerUid.equals(memberUid)
                    || !"ACTIVE".equals(memberStatus)) {
                continue;
            }

            String memberName = stringValue(
                    membership.child("displayName")
            );
            if (memberName.isEmpty()) {
                memberName = memberUid;
            }
            stateStore.rememberMemberName(memberUid, memberName);

            DataSnapshot location = Tasks.await(
                    root.child("locations").child(familyId)
                            .child(memberUid).get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!location.exists()) {
                stateStore.clearAllConditions(memberUid);
                continue;
            }

            boolean sharingEnabled = booleanValue(
                    location.child("sharingEnabled"),
                    false
            );
            if (!sharingEnabled) {
                stateStore.clearAllConditions(memberUid);
                continue;
            }

            long locationUpdatedAt = firstPositive(
                    longValue(location.child("locationUpdatedAt")),
                    longValue(location.child("clientTimestamp")),
                    longValue(location.child("updatedAt"))
            );
            long heartbeatAt = longValue(
                    location.child("serviceHeartbeatAt")
            );
            long disconnectedAt = longValue(
                    location.child("lastDisconnectedAt")
            );
            boolean online = booleanValue(
                    location.child("online"),
                    !FamilyLiveAvailability.DEVICE_OFFLINE.equals(
                            stringValue(location.child("availabilityReason"))
                    )
            );
            int battery = boundedBattery(
                    longObject(location.child("batteryPercentage"))
            );
            boolean charging = booleanValue(
                    location.child("charging"),
                    false
            );

            boolean offline = FamilyDeviceSafetyAlertPolicy
                    .shouldFlagOffline(
                            true,
                            online,
                            disconnectedAt,
                            heartbeatAt,
                            locationUpdatedAt,
                            now
                    );
            boolean noUpdate = FamilyDeviceSafetyAlertPolicy
                    .shouldFlagNoUpdate(
                            true,
                            !online,
                            locationUpdatedAt,
                            now
                    );
            boolean lowBattery = FamilyDeviceSafetyAlertPolicy
                    .shouldFlagLowBattery(
                            true,
                            battery,
                            charging,
                            locationUpdatedAt,
                            now
                    );

            evaluateCondition(
                    context,
                    stateStore,
                    memberUid,
                    memberName,
                    FamilyDeviceSafetyAlertPolicy.ALERT_DEVICE_OFFLINE,
                    offline,
                    now
            );
            evaluateCondition(
                    context,
                    stateStore,
                    memberUid,
                    memberName,
                    FamilyDeviceSafetyAlertPolicy.ALERT_NO_UPDATE,
                    noUpdate,
                    now
            );
            evaluateLowBatteryCondition(
                    context,
                    stateStore,
                    memberUid,
                    memberName,
                    battery,
                    charging,
                    lowBattery,
                    now
            );
        }
    }

    private void evaluateLowBatteryCondition(
            @NonNull Context context,
            @NonNull FamilyDeviceSafetyAlertStateStore stateStore,
            @NonNull String memberUid,
            @NonNull String memberName,
            int battery,
            boolean charging,
            boolean lowBatteryTriggered,
            long now
    ) {
        String alertType =
                FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY;
        if (FamilyDeviceSafetyAlertPolicy.lowBatteryRecovered(
                battery,
                charging
        )) {
            stateStore.clearActive(memberUid, alertType);
            return;
        }
        if (lowBatteryTriggered) {
            evaluateCondition(
                    context,
                    stateStore,
                    memberUid,
                    memberName,
                    alertType,
                    true,
                    now
            );
            return;
        }
        // Between 16% and 21%, retain any existing low-battery condition.
        // Recovery is confirmed only after charging or reaching 22%.
    }

    private void evaluateCondition(
            @NonNull Context context,
            @NonNull FamilyDeviceSafetyAlertStateStore stateStore,
            @NonNull String memberUid,
            @NonNull String memberName,
            @NonNull String alertType,
            boolean conditionActive,
            long now
    ) {
        if (!conditionActive) {
            stateStore.clearActive(memberUid, alertType);
            return;
        }

        boolean dispatched = false;
        if (stateStore.shouldDispatch(memberUid, alertType, now)) {
            dispatched = FamilyDeviceSafetyAlertDispatcher.dispatch(
                    context,
                    memberUid,
                    memberName,
                    alertType,
                    now
            );
        }
        stateStore.recordActive(
                memberUid,
                memberName,
                alertType,
                now,
                dispatched
        );
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static long longValue(@NonNull DataSnapshot snapshot) {
        Long value = snapshot.getValue(Long.class);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static long firstPositive(long first, long second, long third) {
        if (first > 0L) {
            return first;
        }
        if (second > 0L) {
            return second;
        }
        return Math.max(0L, third);
    }

    @Nullable
    private static Long longObject(@NonNull DataSnapshot snapshot) {
        return snapshot.getValue(Long.class);
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }

    private static int boundedBattery(@Nullable Long value) {
        if (value == null) {
            return -1;
        }
        return Math.max(-1, Math.min(100, value.intValue()));
    }
}
