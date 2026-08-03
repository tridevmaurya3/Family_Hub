package com.tridev.familyhub.location;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Stores privacy-safe service diagnostics only. No coordinates, addresses or
 * route history are written to these preferences.
 */
public final class LocationServiceDiagnosticsStore {

    private static final String PREFERENCES =
            "family_live_service_diagnostics";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String KEY_LAST_HEALTHY_AT = "last_healthy_at";
    private static final String KEY_LAST_RECOVERY_AT = "last_recovery_at";
    private static final String KEY_CONSECUTIVE_MISSES =
            "consecutive_misses";
    private static final String KEY_RECOVERY_COUNT = "recovery_count";

    private LocationServiceDiagnosticsStore() {
    }

    @NonNull
    public static Snapshot recordCheck(
            @NonNull Context context,
            boolean serviceRunning
    ) {
        SharedPreferences preferences = preferences(context);
        long now = System.currentTimeMillis();
        int misses = serviceRunning
                ? 0
                : preferences.getInt(KEY_CONSECUTIVE_MISSES, 0) + 1;

        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_LAST_CHECK_AT, now)
                .putInt(KEY_CONSECUTIVE_MISSES, misses);
        if (serviceRunning) {
            editor.putLong(KEY_LAST_HEALTHY_AT, now);
        }
        editor.apply();
        return read(context);
    }

    @NonNull
    public static Snapshot recordRecoveryAttempt(@NonNull Context context) {
        SharedPreferences preferences = preferences(context);
        int recoveryCount = preferences.getInt(KEY_RECOVERY_COUNT, 0) + 1;
        preferences.edit()
                .putLong(KEY_LAST_RECOVERY_AT, System.currentTimeMillis())
                .putInt(KEY_RECOVERY_COUNT, recoveryCount)
                .apply();
        return read(context);
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences preferences = preferences(context);
        return new Snapshot(
                preferences.getLong(KEY_LAST_CHECK_AT, 0L),
                preferences.getLong(KEY_LAST_HEALTHY_AT, 0L),
                preferences.getLong(KEY_LAST_RECOVERY_AT, 0L),
                Math.max(0, preferences.getInt(
                        KEY_CONSECUTIVE_MISSES,
                        0
                )),
                Math.max(0, preferences.getInt(KEY_RECOVERY_COUNT, 0))
        );
    }

    public static void clear(@NonNull Context context) {
        preferences(context).edit().clear().apply();
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    public static final class Snapshot {
        public final long lastCheckAt;
        public final long lastHealthyAt;
        public final long lastRecoveryAt;
        public final int consecutiveMisses;
        public final int recoveryCount;

        Snapshot(
                long lastCheckAt,
                long lastHealthyAt,
                long lastRecoveryAt,
                int consecutiveMisses,
                int recoveryCount
        ) {
            this.lastCheckAt = lastCheckAt;
            this.lastHealthyAt = lastHealthyAt;
            this.lastRecoveryAt = lastRecoveryAt;
            this.consecutiveMisses = consecutiveMisses;
            this.recoveryCount = recoveryCount;
        }
    }
}
