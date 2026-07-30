package com.tridev.familyhub.receiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationSharingStore;

/**
 * Restores location sharing only when the user previously enabled it and
 * location permission is still granted. Android may defer the service when
 * background-start restrictions apply; opening Family Live retries safely.
 */
public class LocationSharingBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !LocationSharingStore.isSharingEnabled(context)
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
}
