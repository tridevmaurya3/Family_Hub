package com.tridev.familyhub.geofence;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Confirms an arrival or exit using a fresh location before alerting. */
public final class SafePlaceTransitionConfirmationWorker extends Worker {

    private static final long LOCATION_TIMEOUT_SECONDS = 25L;
    private static final int MAX_ATTEMPTS = 3;

    public SafePlaceTransitionConfirmationWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        long placeId = getInputData().getLong(
                SafePlaceTransitionConfirmationScheduler.INPUT_PLACE_ID,
                0L
        );
        String alertType = getInputData().getString(
                SafePlaceTransitionConfirmationScheduler.INPUT_ALERT_TYPE
        );
        long triggeredAt = getInputData().getLong(
                SafePlaceTransitionConfirmationScheduler.INPUT_TRIGGERED_AT,
                System.currentTimeMillis()
        );

        if (placeId <= 0L
                || (!SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(alertType)
                && !SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType))) {
            return Result.success();
        }

        Context context = getApplicationContext();
        if (!hasLocationPermission(context) || !isLocationEnabled(context)) {
            return Result.success();
        }

        SafePlace place;
        try {
            place = FamilyHubDatabase.getInstance(context)
                    .safePlaceDao()
                    .getById(placeId);
        } catch (RuntimeException error) {
            return retryOrFinish();
        }
        if (!SafePlaceGeofencePolicy.isValid(place)) {
            new SafePlaceAlertStateStore(context).remove(placeId);
            return Result.success();
        }

        Location location = freshCurrentLocation(context);
        if (location == null) {
            return retryOrFinish();
        }

        long now = System.currentTimeMillis();
        if (!SafePlaceSmartAlertPolicy.isFreshLocation(
                location.getTime(),
                now
        )) {
            return retryOrFinish();
        }

        float[] distance = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                place.latitude,
                place.longitude,
                distance
        );
        float accuracy = location.hasAccuracy()
                ? location.getAccuracy()
                : 20F;

        SafePlaceAlertStateStore stateStore =
                new SafePlaceAlertStateStore(context);
        boolean confirmed;
        if (SafePlaceSmartAlertPolicy.ALERT_LEFT.equals(alertType)) {
            confirmed = SafePlaceSmartAlertPolicy.confirmedOutside(
                    distance[0],
                    place.radiusMeters,
                    accuracy
            );
            if (!confirmed) {
                stateStore.markConfirmedState(placeId, true);
                return Result.success();
            }
        } else {
            confirmed = SafePlaceSmartAlertPolicy.confirmedInside(
                    distance[0],
                    place.radiusMeters,
                    accuracy
            );
            stateStore.clearPendingEnter(placeId);
            if (!confirmed) {
                stateStore.markConfirmedState(placeId, false);
                return Result.success();
            }
        }

        SafePlaceAlertDispatcher.dispatch(
                context,
                place,
                alertType,
                Math.max(triggeredAt, now)
        );
        return Result.success();
    }

    private Result retryOrFinish() {
        return getRunAttemptCount() + 1 < MAX_ATTEMPTS
                ? Result.retry()
                : Result.success();
    }

    private boolean hasLocationPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
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

    private Location freshCurrentLocation(@NonNull Context context) {
        FusedLocationProviderClient client =
                LocationServices.getFusedLocationProviderClient(context);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> result = new AtomicReference<>();

        try {
            client.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            null
                    )
                    .addOnSuccessListener(location -> {
                        result.set(location);
                        latch.countDown();
                    })
                    .addOnFailureListener(error -> latch.countDown())
                    .addOnCanceledListener(latch::countDown);

            if (!latch.await(
                    LOCATION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                return null;
            }
            return result.get();
        } catch (SecurityException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
