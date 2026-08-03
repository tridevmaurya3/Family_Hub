package com.tridev.familyhub.receiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.geofence.SafePlaceGeofenceSyncScheduler;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationRecoveryNotifier;
import com.tridev.familyhub.location.LocationServiceRecoveryScheduler;
import com.tridev.familyhub.location.LocationSharingStore;
import com.tridev.familyhub.location.PendingLocationSyncScheduler;

/**
 * Restores user-enabled Family Live work after reboot, device unlock and app
 * replacement. Restoration never enables sharing or Safe Place alerts by
 * itself; it only restores choices already stored by the user.
 */
public class LocationSharingBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!isSupportedSystemAction(action)) {
            return;
        }

        Context appContext = context.getApplicationContext();
        SafePlaceGeofenceSyncScheduler.scheduleNow(appContext);

        if (!LocationSharingStore.isSharingEnabled(appContext)) {
            LocationRecoveryNotifier.cancelAll(appContext);
            LocationServiceRecoveryScheduler.cancel(appContext);
            return;
        }

        PendingLocationSyncScheduler.enablePeriodicSync(appContext);
        PendingLocationSyncScheduler.schedule(appContext);
        LocationServiceRecoveryScheduler.scheduleAfterSystemEvent(appContext);
        LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(appContext);

        if (!hasRequiredPermissions(appContext)
                || !isLocationEnabled(appContext)) {
            LocationRecoveryNotifier.showResumeRequired(appContext);
            return;
        }

        try {
            ContextCompat.startForegroundService(
                    appContext,
                    FamilyLocationService.startIntent(appContext)
            );
            LocationRecoveryNotifier.cancelResumeRequired(appContext);
        } catch (RuntimeException blocked) {
            // Newer Android versions and some OEM battery managers can reject
            // a background foreground-service start. The scheduled recovery
            // worker retries and the notification offers a user-tapped action.
            LocationRecoveryNotifier.showResumeRequired(appContext);
        }
    }

    private boolean isSupportedSystemAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
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
