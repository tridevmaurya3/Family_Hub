package com.tridev.familyhub.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.tridev.familyhub.location.MovementActivityStore;

import java.util.List;

/** Receives explicit Google Play services activity transitions for Family Live. */
public class MovementTransitionReceiver extends BroadcastReceiver {

    public static final String ACTION_MOVEMENT_TRANSITION =
            "com.tridev.familyhub.action.MOVEMENT_TRANSITION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ACTION_MOVEMENT_TRANSITION.equals(intent.getAction())
                || !ActivityTransitionResult.hasResult(intent)) {
            return;
        }

        ActivityTransitionResult result =
                ActivityTransitionResult.extractResult(intent);
        if (result == null) {
            return;
        }

        List<ActivityTransitionEvent> events =
                result.getTransitionEvents();
        if (events.isEmpty()) {
            return;
        }

        ActivityTransitionEvent latest = events.get(events.size() - 1);
        String movement = mapActivity(latest.getActivityType());
        if (MovementActivityStore.UNKNOWN.equals(movement)) {
            return;
        }

        if (latest.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            MovementActivityStore.update(
                    context.getApplicationContext(),
                    movement,
                    System.currentTimeMillis()
            );
        } else if (latest.getTransitionType()
                == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
            MovementActivityStore.Snapshot current =
                    MovementActivityStore.read(context);
            if (movement.equals(current.type)) {
                MovementActivityStore.clear(context);
            }
        }
    }

    @NonNull
    private String mapActivity(int activityType) {
        switch (activityType) {
            case DetectedActivity.STILL:
                return MovementActivityStore.STILL;
            case DetectedActivity.WALKING:
                return MovementActivityStore.WALKING;
            case DetectedActivity.RUNNING:
                return MovementActivityStore.RUNNING;
            case DetectedActivity.ON_BICYCLE:
                return MovementActivityStore.CYCLING;
            case DetectedActivity.IN_VEHICLE:
                return MovementActivityStore.IN_VEHICLE;
            default:
                return MovementActivityStore.UNKNOWN;
        }
    }
}
