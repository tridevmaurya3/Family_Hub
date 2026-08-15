package com.tridev.familyhub.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * Single source of truth for Family Hub light/dark mode.
 *
 * Theme choice is stored outside View saved-state so Activity/Fragment recreation
 * can never restore an old switch value and bounce the app back to the opposite mode.
 */
public final class ThemeModeController {

    private static final String PREFS = "family_hub_theme_preferences";
    private static final String KEY_DARK = "dark_theme_enabled";
    private static final long RAPID_CHANGE_GUARD_MS = 1200L;

    private static long lastAppliedAt;
    private static boolean lastRequestedDark;

    private ThemeModeController() {
    }

    /** Apply persisted mode before activities are created. Safe to call from Application.onCreate(). */
    public static void applySavedMode(@NonNull Context context) {
        boolean dark = isDarkEnabled(context);
        int desiredMode = dark
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != desiredMode) {
            AppCompatDelegate.setDefaultNightMode(desiredMode);
        }
        lastRequestedDark = dark;
        lastAppliedAt = SystemClock.elapsedRealtime();
    }

    public static boolean isDarkEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DARK, false);
    }

    /**
     * Persist and apply one user-requested mode change.
     * Returns true only when a real mode change was requested.
     */
    public static synchronized boolean requestMode(
            @NonNull Context context,
            boolean dark
    ) {
        boolean stored = isDarkEnabled(context);
        int desiredMode = dark
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        int currentMode = AppCompatDelegate.getDefaultNightMode();

        if (stored == dark && currentMode == desiredMode) {
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastAppliedAt < RAPID_CHANGE_GUARD_MS
                && lastRequestedDark != dark) {
            // Reject stale restored-view callbacks that try to immediately undo
            // the user's most recent theme selection during recreation.
            return false;
        }

        prefs(context).edit().putBoolean(KEY_DARK, dark).commit();
        lastRequestedDark = dark;
        lastAppliedAt = now;

        if (currentMode != desiredMode) {
            AppCompatDelegate.setDefaultNightMode(desiredMode);
        }
        return true;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }
}
