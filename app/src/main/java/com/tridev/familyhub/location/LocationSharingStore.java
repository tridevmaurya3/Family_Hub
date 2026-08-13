package com.tridev.familyhub.location;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Stores only the user's explicit Family Live sharing choice.
 * Coordinates and other sensitive location data are never stored here.
 */
public final class LocationSharingStore {

    private static final String PREFERENCES = "family_live_location";
    private static final String KEY_SHARING_ENABLED = "sharing_enabled";
    private static final String KEY_SHARING_ENABLED_AT = "sharing_enabled_at";
    private static final String KEY_SHARING_EXPIRES_AT = "sharing_expires_at";
    private static final String KEY_REQUESTED_DURATION = "requested_duration";

    private LocationSharingStore() {
    }

    public static boolean isSharingEnabled(@NonNull Context context) {
        SharedPreferences preferences = preferences(context);
        boolean enabled = preferences.getBoolean(KEY_SHARING_ENABLED, false);
        long expiresAt = preferences.getLong(KEY_SHARING_EXPIRES_AT, 0L);
        if (enabled && expiresAt > 0L
                && System.currentTimeMillis() >= expiresAt) {
            setSharingEnabled(context, false);
            return false;
        }
        return enabled;
    }

    public static long sharingEnabledAt(@NonNull Context context) {
        return preferences(context).getLong(KEY_SHARING_ENABLED_AT, 0L);
    }

    public static long sharingExpiresAt(@NonNull Context context) {
        return preferences(context).getLong(KEY_SHARING_EXPIRES_AT, 0L);
    }

    public static void prepareSharingDuration(
            @NonNull Context context,
            long durationMs
    ) {
        preferences(context).edit()
                .putLong(KEY_REQUESTED_DURATION, Math.max(0L, durationMs))
                .apply();
    }

    public static void setSharingEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        boolean wasEnabled = preferences.getBoolean(
                KEY_SHARING_ENABLED,
                false
        );

        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_SHARING_ENABLED, enabled);
        if (enabled && !wasEnabled) {
            editor.putLong(KEY_SHARING_ENABLED_AT, System.currentTimeMillis());
            long duration = preferences.getLong(KEY_REQUESTED_DURATION, 0L);
            if (duration > 0L) {
                editor.putLong(
                        KEY_SHARING_EXPIRES_AT,
                        System.currentTimeMillis() + duration
                );
            } else {
                editor.remove(KEY_SHARING_EXPIRES_AT);
            }
            editor.remove(KEY_REQUESTED_DURATION);
        } else if (!enabled) {
            editor.remove(KEY_SHARING_ENABLED_AT)
                    .remove(KEY_SHARING_EXPIRES_AT)
                    .remove(KEY_REQUESTED_DURATION);
        }
        editor.apply();

        if (enabled) {
            LocationSharingExpiryScheduler.schedule(
                    appContext,
                    preferences(appContext).getLong(
                            KEY_SHARING_EXPIRES_AT,
                            0L
                    )
            );
            PendingLocationSyncScheduler.enablePeriodicSync(appContext);
            PendingLocationSyncScheduler.schedule(appContext);
            LocationServiceWatchdogScheduler.enable(appContext);
            LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(
                    appContext
            );
        } else {
            LocationSharingExpiryScheduler.cancel(appContext);
            PendingLocationSyncScheduler.disableAndClear(appContext);
            LocationServiceRecoveryScheduler.cancel(appContext);
            LocationServiceWatchdogScheduler.disable(appContext);
            LocationServiceDiagnosticsStore.clear(appContext);
            LocationRecoveryNotifier.cancelAll(appContext);
        }
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }
}
