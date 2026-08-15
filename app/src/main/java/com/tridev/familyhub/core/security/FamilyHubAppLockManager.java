package com.tridev.familyhub.core.security;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.WindowCallbackWrapper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.main.SplashActivity;
import com.tridev.familyhub.feature.security.AppLockActivity;

import java.lang.ref.WeakReference;

/**
 * UI-only inactivity lock. Services, workers, ContentProviders, Family Live,
 * SOS, backups and MoneyManager integration continue to run while the visible
 * app is locked.
 */
public final class FamilyHubAppLockManager implements Application.ActivityLifecycleCallbacks {

    private static FamilyHubAppLockManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> activeActivity = new WeakReference<>(null);
    private long lastInteractionElapsedRealtime;
    private boolean sessionUnlocked;
    private boolean lockNavigationInProgress;
    @NonNull private String unlockedUid = "";

    private final Runnable timeoutRunnable = () -> {
        Activity activity = activeActivity.get();
        if (activity != null) lockNow(activity);
    };

    private FamilyHubAppLockManager() { }

    public static synchronized void register(@NonNull Application application) {
        if (instance != null) return;
        instance = new FamilyHubAppLockManager();
        application.registerActivityLifecycleCallbacks(instance);
    }

    public static void markUnlocked() {
        FamilyHubAppLockManager manager = instance;
        if (manager == null) return;
        manager.sessionUnlocked = true;
        manager.unlockedUid = currentUid();
        manager.lockNavigationInProgress = false;
        manager.markInteractionNow();
        Activity activity = manager.activeActivity.get();
        if (activity != null && !manager.isEntryOrLockActivity(activity)
                && AppSecurityStore.isProtectionEnabled(activity)) {
            manager.scheduleTimeout(manager.timeoutMillis(activity));
        }
    }

    public static void markSignedOut() {
        FamilyHubAppLockManager manager = instance;
        if (manager == null) return;
        manager.sessionUnlocked = false;
        manager.unlockedUid = "";
        manager.lockNavigationInProgress = false;
        manager.lastInteractionElapsedRealtime = 0L;
        manager.cancelTimeout();
    }

    public static void forceLock(@NonNull Activity activity) {
        FamilyHubAppLockManager manager = instance;
        if (manager == null) {
            activity.startActivity(AppLockActivity.intentForOverlay(activity));
            return;
        }
        manager.sessionUnlocked = false;
        manager.unlockedUid = "";
        manager.lockNow(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        activeActivity = new WeakReference<>(activity);

        if (isEntryOrLockActivity(activity)) {
            if (activity instanceof AppLockActivity) {
                lockNavigationInProgress = false;
            }
            cancelTimeout();
            return;
        }

        String uid = currentUid();
        if (!uid.equals(unlockedUid)) {
            sessionUnlocked = false;
            lastInteractionElapsedRealtime = 0L;
        }

        if (!AppSecurityStore.isProtectionEnabled(activity)) {
            sessionUnlocked = true;
            unlockedUid = uid;
            lockNavigationInProgress = false;
            markInteractionNow();
            cancelTimeout();
            return;
        }

        if (!sessionUnlocked) {
            lockNow(activity);
            return;
        }

        installInteractionCallback(activity);
        long now = SystemClock.elapsedRealtime();
        if (lastInteractionElapsedRealtime <= 0L) {
            lastInteractionElapsedRealtime = now;
        }
        long elapsed = Math.max(0L, now - lastInteractionElapsedRealtime);
        long timeout = timeoutMillis(activity);
        if (elapsed >= timeout) {
            sessionUnlocked = false;
            unlockedUid = "";
            lockNow(activity);
            return;
        }
        scheduleTimeout(timeout - elapsed);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        Activity current = activeActivity.get();
        if (current == activity) cancelTimeout();
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        Activity current = activeActivity.get();
        if (current == activity) {
            activeActivity.clear();
            cancelTimeout();
        }
    }

    public void onUserInteraction(@NonNull Activity activity) {
        Activity current = activeActivity.get();
        if (current != activity || isEntryOrLockActivity(activity)
                || !sessionUnlocked || !currentUid().equals(unlockedUid)
                || !AppSecurityStore.isProtectionEnabled(activity)
                || lockNavigationInProgress) {
            return;
        }
        markInteractionNow();
        scheduleTimeout(timeoutMillis(activity));
    }

    private void lockNow(@NonNull Activity activity) {
        if (lockNavigationInProgress || activity.isFinishing() || activity.isDestroyed()
                || isEntryOrLockActivity(activity)
                || !AppSecurityStore.isProtectionEnabled(activity)) {
            return;
        }
        sessionUnlocked = false;
        unlockedUid = "";
        lockNavigationInProgress = true;
        cancelTimeout();
        activity.startActivity(AppLockActivity.intentForOverlay(activity));
    }

    private void installInteractionCallback(@NonNull Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        Window.Callback callback = window.getCallback();
        if (callback == null || callback instanceof InteractionWindowCallback) return;
        window.setCallback(new InteractionWindowCallback(callback, activity, this));
    }

    private boolean isEntryOrLockActivity(@NonNull Activity activity) {
        return activity instanceof AppLockActivity
                || activity instanceof AuthActivity
                || activity instanceof SplashActivity;
    }

    private long timeoutMillis(@NonNull Activity activity) {
        return AppSecurityStore.getTimeoutMinutes(activity) * 60_000L;
    }

    private void markInteractionNow() {
        lastInteractionElapsedRealtime = SystemClock.elapsedRealtime();
    }

    private void scheduleTimeout(long delayMillis) {
        cancelTimeout();
        mainHandler.postDelayed(timeoutRunnable, Math.max(1L, delayMillis));
    }

    private void cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable);
    }

    @NonNull
    private static String currentUid() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String uid = user == null ? "" : user.getUid();
            return uid == null ? "" : uid.trim();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                       @NonNull Bundle outState) { }

    private static final class InteractionWindowCallback extends WindowCallbackWrapper {
        private final WeakReference<Activity> activityReference;
        private final WeakReference<FamilyHubAppLockManager> managerReference;

        InteractionWindowCallback(@NonNull Window.Callback wrapped,
                                  @NonNull Activity activity,
                                  @NonNull FamilyHubAppLockManager manager) {
            super(wrapped);
            activityReference = new WeakReference<>(activity);
            managerReference = new WeakReference<>(manager);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null) notifyInteraction();
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null) notifyInteraction();
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                notifyInteraction();
            }
            return super.dispatchKeyEvent(event);
        }

        private void notifyInteraction() {
            Activity activity = activityReference.get();
            FamilyHubAppLockManager manager = managerReference.get();
            if (activity != null && manager != null) manager.onUserInteraction(activity);
        }
    }
}
