package com.tridev.familyhub.location;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Checks whether the consent-based Family Live foreground service is alive.
 * A healthy check publishes a heartbeat without changing location freshness.
 * A missed service check schedules recovery and exposes truthful diagnostics.
 */
public final class LocationServiceWatchdogWorker extends Worker {

    private static final long FIREBASE_TIMEOUT_SECONDS = 20L;

    public LocationServiceWatchdogWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!LocationSharingStore.isSharingEnabled(context)) {
            LocationServiceWatchdogScheduler.disable(context);
            LocationServiceDiagnosticsStore.clear(context);
            return Result.success();
        }

        boolean serviceRunning = isLocationServiceRunning(context);
        LocationServiceDiagnosticsStore.Snapshot snapshot =
                LocationServiceDiagnosticsStore.recordCheck(
                        context,
                        serviceRunning
                );

        if (LocationHeartbeatPolicy.shouldRecover(
                true,
                serviceRunning
        )) {
            snapshot = LocationServiceDiagnosticsStore
                    .recordRecoveryAttempt(context);
            publishServiceDiagnostics(
                    context,
                    LocationHeartbeatPolicy.STATE_RECOVERY_PENDING,
                    snapshot
            );
            LocationRecoveryNotifier.showResumeRequired(context);
            LocationServiceRecoveryScheduler.scheduleNow(context);
        } else {
            publishServiceDiagnostics(
                    context,
                    LocationHeartbeatPolicy.STATE_RUNNING,
                    snapshot
            );
            LocationRecoveryNotifier.cancelResumeRequired(context);
        }

        if (LocationSharingStore.isSharingEnabled(context)) {
            LocationServiceWatchdogScheduler.scheduleNext(
                    context,
                    LocationHeartbeatPolicy.nextCheckDelay(serviceRunning)
            );
        }
        return Result.success();
    }

    private boolean isLocationServiceRunning(@NonNull Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE
        );
        if (manager == null) {
            return false;
        }

        try {
            List<ActivityManager.RunningServiceInfo> services =
                    manager.getRunningServices(Integer.MAX_VALUE);
            if (services == null) {
                return false;
            }

            String expectedClass = FamilyLocationService.class.getName();
            for (ActivityManager.RunningServiceInfo service : services) {
                ComponentName component = service.service;
                if (component != null
                        && expectedClass.equals(component.getClassName())) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // The recovery path is safe even when an OEM hides service state.
        }
        return false;
    }

    private void publishServiceDiagnostics(
            @NonNull Context context,
            @NonNull String state,
            @NonNull LocationServiceDiagnosticsStore.Snapshot snapshot
    ) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            return;
        }

        try {
            DatabaseReference root = FirebaseDatabase
                    .getInstance()
                    .getReference();
            DataSnapshot userSnapshot = Tasks.await(
                    root.child("users")
                            .child(user.getUid())
                            .get(),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            String familyId = userSnapshot
                    .child("familyId")
                    .getValue(String.class);
            String status = userSnapshot
                    .child("status")
                    .getValue(String.class);
            if (familyId == null
                    || familyId.trim().isEmpty()
                    || !"ACTIVE".equals(status)) {
                return;
            }

            Map<String, Object> values = new HashMap<>();
            values.put("uid", user.getUid());
            values.put("familyId", familyId);
            values.put("sharingEnabled", true);
            values.put("serviceState", state);
            values.put("serviceHeartbeatAt", ServerValue.TIMESTAMP);
            values.put("serviceHeartbeatClientAt", System.currentTimeMillis());
            values.put("serviceWatchdogCheckedAt", ServerValue.TIMESTAMP);
            values.put(
                    "serviceConsecutiveMisses",
                    snapshot.consecutiveMisses
            );
            values.put("serviceRecoveryCount", snapshot.recoveryCount);
            values.put("serviceLastHealthyAt", snapshot.lastHealthyAt);
            values.put("serviceLastRecoveryAt", snapshot.lastRecoveryAt);

            Tasks.await(
                    root.child("locations")
                            .child(familyId)
                            .child(user.getUid())
                            .updateChildren(values),
                    FIREBASE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception ignored) {
            // The rolling and periodic checks will try again when possible.
        }
    }
}
