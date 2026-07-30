package com.tridev.familyhub.location;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Stores only the latest on-device activity hint; no coordinates are persisted here. */
public final class MovementActivityStore {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String STILL = "STILL";
    public static final String WALKING = "WALKING";
    public static final String RUNNING = "RUNNING";
    public static final String CYCLING = "CYCLING";
    public static final String IN_VEHICLE = "IN_VEHICLE";

    private static final String PREFS = "family_live_movement";
    private static final String KEY_TYPE = "type";
    private static final String KEY_UPDATED_AT = "updated_at";

    private MovementActivityStore() {
    }

    public static void update(
            @NonNull Context context,
            @NonNull String type,
            long updatedAt
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TYPE, type)
                .putLong(KEY_UPDATED_AT, updatedAt)
                .apply();
    }

    public static void clear(@NonNull Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        return new Snapshot(
                preferences.getString(KEY_TYPE, UNKNOWN),
                preferences.getLong(KEY_UPDATED_AT, 0L)
        );
    }

    public static final class Snapshot {
        @NonNull public final String type;
        public final long updatedAt;

        Snapshot(@NonNull String type, long updatedAt) {
            this.type = type;
            this.updatedAt = updatedAt;
        }

        public boolean isFresh(long now, long maxAgeMillis) {
            return updatedAt > 0L
                    && now >= updatedAt
                    && now - updatedAt <= maxAgeMillis;
        }
    }
}
