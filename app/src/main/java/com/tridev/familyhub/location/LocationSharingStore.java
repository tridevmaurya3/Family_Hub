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

    private LocationSharingStore() {
    }

    public static boolean isSharingEnabled(@NonNull Context context) {
        return preferences(context).getBoolean(KEY_SHARING_ENABLED, false);
    }

    public static long sharingEnabledAt(@NonNull Context context) {
        return preferences(context).getLong(KEY_SHARING_ENABLED_AT, 0L);
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
        } else if (!enabled) {
            editor.remove(KEY_SHARING_ENABLED_AT);
        }
        editor.apply();

        if (enabled) {
            PendingLocationSyncScheduler.enablePeriodicSync(appContext);
            PendingLocationSyncScheduler.schedule(appContext);
            LocationServiceWatchdogScheduler.enable(appContext);
            LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(
                    appContext
            );
        } else {
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
