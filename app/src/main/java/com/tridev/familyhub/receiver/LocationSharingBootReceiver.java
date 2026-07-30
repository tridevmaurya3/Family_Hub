package com.tridev.familyhub.receiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationSharingStore;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.geofence.SafePlaceRegistrar;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restores location sharing only when the user previously enabled it and
 * location permission is still granted. Android may defer the service when
 * background-start restrictions apply; opening Family Live retries safely.
 */
public class LocationSharingBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        restoreEnabledSafePlaces(context);

        if (!LocationSharingStore.isSharingEnabled(context)
                || ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            ContextCompat.startForegroundService(
                    context,
                    FamilyLocationService.startIntent(context)
            );
        } catch (RuntimeException ignored) {
            // The app will retry visibly when Family Live is opened.
        }
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
        Context appContext = context.getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                List<SafePlace> enabled = FamilyHubDatabase
                        .getInstance(appContext)
                        .safePlaceDao()
                        .getEnabled();
                for (SafePlace place : enabled) {
                    if (isValid(place)) {
                        SafePlaceRegistrar.register(
                                appContext,
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
