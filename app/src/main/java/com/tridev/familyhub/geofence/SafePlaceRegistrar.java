package com.tridev.familyhub.geofence;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import java.util.Collections;

public final class SafePlaceRegistrar {
    private SafePlaceRegistrar() {}

    public static boolean register(
            @NonNull Context context,
            @NonNull String id,
            double latitude,
            double longitude,
            float radius
    ) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        Geofence geofence = new Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(latitude, longitude, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER
                        | Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(60_000)
                .build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 4301,
                new Intent(context, FamilyGeofenceReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        GeofencingClient client =
                LocationServices.getGeofencingClient(context);
        client.removeGeofences(Collections.singletonList(id))
                .addOnCompleteListener(ignored -> client.addGeofences(
                        new GeofencingRequest.Builder()
                                .setInitialTrigger(
                                        GeofencingRequest
                                                .INITIAL_TRIGGER_ENTER
                                )
                                .addGeofence(geofence)
                                .build(),
                        pendingIntent
                ));
        return true;
    }

    public static void remove(
            @NonNull Context context,
            @NonNull String id
    ) {
        LocationServices.getGeofencingClient(context)
                .removeGeofences(Collections.singletonList(id));
    }
}
