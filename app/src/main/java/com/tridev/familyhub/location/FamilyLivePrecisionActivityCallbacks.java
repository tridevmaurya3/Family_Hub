package com.tridev.familyhub.location;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.familylive.FamilyMapActivity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Starts precision tracking with the visible Family Map lifecycle and upgrades
 * Family Live refresh taps into short authorised precision sessions.
 */
public final class FamilyLivePrecisionActivityCallbacks
        implements Application.ActivityLifecycleCallbacks {

    private final Map<Activity, FamilyLivePrecisionSessionController> sessions =
            new WeakHashMap<>();
    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener>
            layoutListeners = new WeakHashMap<>();
    private final Map<View, Boolean> hookedRefreshButtons = new WeakHashMap<>();

    @Override
    public void onActivityCreated(
            @NonNull Activity activity,
            @Nullable Bundle savedInstanceState
    ) {
        installRefreshHook(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        installRefreshHook(activity);
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
        View root = activity.findViewById(android.R.id.content);
        ViewTreeObserver.OnGlobalLayoutListener listener =
                layoutListeners.remove(activity);
        if (root != null
                && listener != null
                && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    private void installRefreshHook(@NonNull Activity activity) {
        if (layoutListeners.containsKey(activity)) {
            hookRefreshButton(activity);
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        ViewTreeObserver.OnGlobalLayoutListener listener =
                () -> hookRefreshButton(activity);
        layoutListeners.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.post(() -> hookRefreshButton(activity));
    }

    private void hookRefreshButton(@NonNull Activity activity) {
        View refresh = activity.findViewById(R.id.buttonRefreshList);
        if (refresh == null || hookedRefreshButtons.containsKey(refresh)) {
            return;
        }
        hookedRefreshButtons.put(refresh, Boolean.TRUE);
        refresh.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                FamilyLivePrecisionSessionController.requestOneShot(
                        activity.getApplicationContext()
                );
            }
            return false;
        });
    }

    private void stopSession(@NonNull Activity activity) {
        FamilyLivePrecisionSessionController controller =
                sessions.remove(activity);
        if (controller != null) {
            controller.stop();
        }
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
