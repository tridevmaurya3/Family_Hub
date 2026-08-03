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

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.geofence.SafePlaceRegistrar;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationRecoveryNotifier;
import com.tridev.familyhub.location.LocationServiceRecoveryScheduler;
import com.tridev.familyhub.location.LocationSharingStore;
import com.tridev.familyhub.location.PendingLocationSyncScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restores user-enabled Family Live work after reboot, device unlock and app
 * replacement. Restoration never enables sharing by itself; it honours only
 * the choice already stored by the user.
 */
public class LocationSharingBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!isSupportedSystemAction(action)) {
            return;
        }

        Context appContext = context.getApplicationContext();
        restoreEnabledSafePlaces(appContext);

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

    private void restoreEnabledSafePlaces(@NonNull Context context) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) != PackageManager.PERMISSION_GRANTED)) {
            return;
        }

        PendingResult pendingResult = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                List<SafePlace> enabled = FamilyHubDatabase
                        .getInstance(context)
                        .safePlaceDao()
                        .getEnabled();
                for (SafePlace place : enabled) {
                    if (isValid(place)) {
                        SafePlaceRegistrar.register(
                                context,
                                String.valueOf(place.id),
                                place.latitude,
                                place.longitude,
                                place.radiusMeters
                        );
                    }
                }
            } catch (RuntimeException ignored) {
                // Opening Safe Places will retry registration visibly.
            } finally {
                pendingResult.finish();
                executor.shutdown();
            }
        });
    }

    private boolean isValid(@NonNull SafePlace place) {
        return place.id > 0L
                && place.alertsEnabled
                && Double.isFinite(place.latitude)
                && Double.isFinite(place.longitude)
                && place.latitude >= -90D
                && place.latitude <= 90D
                && place.longitude >= -180D
                && place.longitude <= 180D
                && !(place.latitude == 0D && place.longitude == 0D)
                && Float.isFinite(place.radiusMeters)
                && place.radiusMeters >= 100F
                && place.radiusMeters <= 5000F;
    }
}
