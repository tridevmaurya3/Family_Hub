package com.tridev.familyhub.geofence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Receives raw geofence signals and routes them through false-alert checks. */
public class FamilyGeofenceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null
                || event.hasError()
                || event.getTriggeringGeofences() == null) {
            return;
        }

        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER
                && transition != Geofence.GEOFENCE_TRANSITION_EXIT
                && transition != Geofence.GEOFENCE_TRANSITION_DWELL) {
            return;
        }

        List<Geofence> geofences = event.getTriggeringGeofences();
        PendingResult pendingResult = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Context appContext = context.getApplicationContext();

        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                SafePlaceAlertStateStore stateStore =
                        new SafePlaceAlertStateStore(appContext);

                for (Geofence geofence : geofences) {
                    String rawPlaceId = SafePlaceGeofencePolicy
                            .placeIdFromRequestId(geofence.getRequestId());
                    if (rawPlaceId == null) {
                        continue;
                    }

                    long placeId;
                    try {
                        placeId = Long.parseLong(rawPlaceId);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }

                    SafePlace place = FamilyHubDatabase
                            .getInstance(appContext)
                            .safePlaceDao()
                            .getById(placeId);
                    if (!SafePlaceGeofencePolicy.isValid(place)) {
                        SafePlaceTransitionConfirmationScheduler.cancel(
                                appContext,
                                placeId
                        );
                        stateStore.remove(placeId);
                        continue;
                    }

                    if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                        stateStore.markPendingEnter(placeId, now);
                        SafePlaceTransitionConfirmationScheduler.schedule(
                                appContext,
                                placeId,
                                SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                                now
                        );
                    } else if (transition
                            == Geofence.GEOFENCE_TRANSITION_EXIT) {
                        SafePlaceTransitionConfirmationScheduler.schedule(
                                appContext,
                                placeId,
                                SafePlaceSmartAlertPolicy.ALERT_LEFT,
                                now
                        );
                    } else {
                        SafePlaceTransitionConfirmationScheduler.cancel(
                                appContext,
                                placeId
                        );
                        handleConfirmedDwell(
                                appContext,
                                place,
                                stateStore,
                                now
                        );
                    }
                }
            } catch (RuntimeException ignored) {
                // A failed alert check must never crash the receiver process.
            } finally {
                pendingResult.finish();
                executor.shutdown();
            }
        });
    }

    private void handleConfirmedDwell(
            @NonNull Context context,
            @NonNull SafePlace place,
            @NonNull SafePlaceAlertStateStore stateStore,
            long now
    ) {
        String state = stateStore.state(place.id);
        if (!SafePlaceAlertStateStore.STATE_INSIDE.equals(state)) {
            SafePlaceAlertDispatcher.dispatch(
                    context,
                    place,
                    SafePlaceSmartAlertPolicy.ALERT_ARRIVED,
                    now
            );
            return;
        }

        SafePlaceAlertDispatcher.dispatch(
                context,
                place,
                SafePlaceSmartAlertPolicy.ALERT_DWELL,
                now
        );
    }
}
