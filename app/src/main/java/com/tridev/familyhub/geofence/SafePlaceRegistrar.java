package com.tridev.familyhub.geofence;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Registers and synchronizes the complete Safe Place geofence set. */
public final class SafePlaceRegistrar {

    public interface RegistrationCallback {
        void onRegistered();

        void onPermissionDenied();

        void onError();
    }

    public interface SynchronizationCallback {
        void onSynchronized(int registeredCount);

        void onPermissionDenied();

        void onError();
    }

    private static final int PENDING_INTENT_REQUEST_CODE = 4301;

    private SafePlaceRegistrar() {
    }

    public static boolean register(
            @NonNull Context context,
            @NonNull String id,
            double latitude,
            double longitude,
            float radius
    ) {
        return register(context, id, latitude, longitude, radius, null);
    }

    public static boolean register(
            @NonNull Context context,
            @NonNull String id,
            double latitude,
            double longitude,
            float radius,
            @Nullable RegistrationCallback callback
    ) {
        long placeId;
        try {
            placeId = Long.parseLong(id.trim());
        } catch (NumberFormatException error) {
            notifyRegistrationError(callback);
            return false;
        }

        if (!hasRequiredPermissions(context)) {
            if (callback != null) {
                callback.onPermissionDenied();
            }
            return false;
        }
        if (placeId <= 0L
                || !SafePlaceGeofencePolicy.validCoordinates(
                latitude,
                longitude
        )
                || !Float.isFinite(radius)
                || radius < SafePlaceGeofencePolicy.MIN_RADIUS_METERS
                || radius > SafePlaceGeofencePolicy.MAX_RADIUS_METERS) {
            notifyRegistrationError(callback);
            return false;
        }

        Geofence geofence = buildGeofence(
                placeId,
                latitude,
                longitude,
                radius
        );
        GeofencingClient client = LocationServices.getGeofencingClient(
                context.getApplicationContext()
        );
        List<String> legacyAndCurrentIds = Arrays.asList(
                String.valueOf(placeId),
                SafePlaceGeofencePolicy.requestId(placeId)
        );

        client.removeGeofences(legacyAndCurrentIds)
                .addOnCompleteListener(ignored -> client.addGeofences(
                                buildRequest(Collections.singletonList(geofence)),
                                pendingIntent(context)
                        )
                        .addOnSuccessListener(result -> {
                            if (callback != null) {
                                callback.onRegistered();
                            }
                        })
                        .addOnFailureListener(error ->
                                notifyRegistrationError(callback)));
        return true;
    }

    public static boolean synchronize(
            @NonNull Context context,
            @Nullable List<SafePlace> places,
            @Nullable SynchronizationCallback callback
    ) {
        if (!hasRequiredPermissions(context)) {
            if (callback != null) {
                callback.onPermissionDenied();
            }
            return false;
        }

        List<SafePlace> validPlaces = SafePlaceGeofencePolicy.sanitize(places);
        List<Geofence> geofences = new ArrayList<>(validPlaces.size());
        for (SafePlace place : validPlaces) {
            geofences.add(buildGeofence(
                    place.id,
                    place.latitude,
                    place.longitude,
                    place.radiusMeters
            ));
        }

        Context appContext = context.getApplicationContext();
        GeofencingClient client = LocationServices.getGeofencingClient(
                appContext
        );
        PendingIntent pendingIntent = pendingIntent(appContext);

        client.removeGeofences(pendingIntent)
                .addOnCompleteListener(ignored -> {
                    if (geofences.isEmpty()) {
                        if (callback != null) {
                            callback.onSynchronized(0);
                        }
                        return;
                    }
                    client.addGeofences(
                                    buildRequest(geofences),
                                    pendingIntent
                            )
                            .addOnSuccessListener(result -> {
                                if (callback != null) {
                                    callback.onSynchronized(geofences.size());
                                }
                            })
                            .addOnFailureListener(error -> {
                                if (callback != null) {
                                    callback.onError();
                                }
                            });
                });
        return true;
    }

    public static void remove(
            @NonNull Context context,
            @NonNull String id
    ) {
        long placeId;
        try {
            placeId = Long.parseLong(id.trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (placeId <= 0L) {
            return;
        }
        LocationServices.getGeofencingClient(context.getApplicationContext())
                .removeGeofences(Arrays.asList(
                        String.valueOf(placeId),
                        SafePlaceGeofencePolicy.requestId(placeId)
                ));
    }

    public static boolean hasRequiredPermissions(@NonNull Context context) {
        boolean fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        return fine && background;
    }

    @NonNull
    private static Geofence buildGeofence(
            long placeId,
            double latitude,
            double longitude,
            float radius
    ) {
        return new Geofence.Builder()
                .setRequestId(SafePlaceGeofencePolicy.requestId(placeId))
                .setCircularRegion(latitude, longitude, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                        SafePlaceGeofencePolicy.transitionTypes()
                )
                .setLoiteringDelay(
                        SafePlaceGeofencePolicy.LOITERING_DELAY_MS
                )
                .setNotificationResponsiveness(
                        SafePlaceGeofencePolicy
                                .NOTIFICATION_RESPONSIVENESS_MS
                )
                .build();
    }

    @NonNull
    private static GeofencingRequest buildRequest(
            @NonNull List<Geofence> geofences
    ) {
        return new GeofencingRequest.Builder()
                .setInitialTrigger(
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
                )
                .addGeofences(geofences)
                .build();
    }

    @NonNull
    private static PendingIntent pendingIntent(@NonNull Context context) {
        Intent intent = new Intent(
                context.getApplicationContext(),
                FamilyGeofenceReceiver.class
        );
        return PendingIntent.getBroadcast(
                context.getApplicationContext(),
                PENDING_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_MUTABLE
        );
    }

    private static void notifyRegistrationError(
            @Nullable RegistrationCallback callback
    ) {
        if (callback != null) {
            callback.onError();
        }
    }
}
