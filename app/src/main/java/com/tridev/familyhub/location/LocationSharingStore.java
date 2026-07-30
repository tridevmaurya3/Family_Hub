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

    private LocationSharingStore() {
    }

    public static boolean isSharingEnabled(@NonNull Context context) {
        return preferences(context).getBoolean(KEY_SHARING_ENABLED, false);
    }

    public static void setSharingEnabled(
            @NonNull Context context,
            boolean enabled
    ) {
        preferences(context).edit()
                .putBoolean(KEY_SHARING_ENABLED, enabled)
                .apply();
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }
}
