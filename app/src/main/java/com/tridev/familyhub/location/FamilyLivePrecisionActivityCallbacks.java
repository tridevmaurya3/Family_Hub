package com.tridev.familyhub.location;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.feature.familylive.FamilyMapActivity;

import java.util.Map;
import java.util.WeakHashMap;

/** Starts and stops precision requests with the visible Family Map lifecycle. */
public final class FamilyLivePrecisionActivityCallbacks
        implements Application.ActivityLifecycleCallbacks {

    private final Map<Activity, FamilyLivePrecisionSessionController> sessions =
            new WeakHashMap<>();

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (!(activity instanceof FamilyMapActivity)) {
            return;
        }
        FamilyLivePrecisionSessionController previous = sessions.remove(activity);
        if (previous != null) {
            previous.stop();
        }
        FamilyLivePrecisionSessionController controller =
                new FamilyLivePrecisionSessionController(activity, true);
        sessions.put(activity, controller);
        controller.start();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        stopSession(activity);
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        stopSession(activity);
    }

    private void stopSession(@NonNull Activity activity) {
        FamilyLivePrecisionSessionController controller =
                sessions.remove(activity);
        if (controller != null) {
            controller.stop();
        }
    }

    @Override
    public void onActivityCreated(
            @NonNull Activity activity,
            @Nullable Bundle savedInstanceState
    ) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(
            @NonNull Activity activity,
            @NonNull Bundle outState
    ) {
    }
}
