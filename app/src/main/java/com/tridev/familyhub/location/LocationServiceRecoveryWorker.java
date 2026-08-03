package com.tridev.familyhub.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Rechecks Family Live after reboot, package replacement, watchdog failure or
 * process restart.
 *
 * Restoration happens only when the user previously enabled sharing and all
 * required permissions are still present. When Android blocks a background
 * foreground-service launch, the worker posts a user-initiated Resume action.
 */
public final class LocationServiceRecoveryWorker extends Worker {

    public LocationServiceRecoveryWorker(
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
            LocationRecoveryNotifier.cancelAll(context);
            return Result.success();
        }

        LocationServiceDiagnosticsStore.recordRecoveryAttempt(context);
        PendingLocationSyncScheduler.enablePeriodicSync(context);
        PendingLocationSyncScheduler.schedule(context);
        LocationServiceWatchdogScheduler.enable(context);
        LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(context);

        if (!hasRequiredPermissions(context)
                || !isLocationEnabled(context)) {
            LocationRecoveryNotifier.showResumeRequired(context);
            LocationServiceWatchdogScheduler
                    .scheduleRecoveryVerification(context);
            return Result.success();
        }

        try {
            ContextCompat.startForegroundService(
                    context,
                    FamilyLocationService.startIntent(context)
            );
            LocationRecoveryNotifier.cancelResumeRequired(context);
        } catch (RuntimeException blocked) {
            LocationRecoveryNotifier.showResumeRequired(context);
        }

        LocationServiceWatchdogScheduler.scheduleRecoveryVerification(
                context
        );
        return Result.success();
    }

    private boolean hasRequiredPermissions(@NonNull Context context) {
        boolean foreground = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        boolean notifications =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;

        return foreground && background && notifications;
    }

    private boolean isLocationEnabled(@NonNull Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(
                Context.LOCATION_SERVICE
        );
        if (manager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }

        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
        );
    }
}
