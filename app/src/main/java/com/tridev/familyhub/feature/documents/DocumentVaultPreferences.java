package com.tridev.familyhub.feature.documents;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/** Device-local, non-sensitive preferences for the private Documents Vault. */
public final class DocumentVaultPreferences {

    public static final int DEFAULT_REMINDER_DAYS = 30;
    public static final long UNLOCK_SESSION_MILLIS = 2L * 60L * 1000L;

    private static final String PREFS = "family_hub_documents_vault";
    private static final String KEY_LOCK_ENABLED = "lock_enabled";
    private static final String KEY_EXPIRY_ALERTS = "expiry_alerts";
    private static final String KEY_REMINDER_DAYS = "reminder_days";
    private static final String KEY_FAVORITES = "favorite_document_ids";

    private static volatile long unlockedUntilElapsedRealtime;

    private final SharedPreferences preferences;

    public DocumentVaultPreferences(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean lockEnabled() {
        return preferences.getBoolean(KEY_LOCK_ENABLED, false);
    }

    public void setLockEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply();
        if (!enabled) {
            unlockedUntilElapsedRealtime = Long.MAX_VALUE;
        } else {
            lockNow();
        }
    }

    public boolean isUnlocked() {
        if (!lockEnabled()) {
            return true;
        }
        return android.os.SystemClock.elapsedRealtime()
                < unlockedUntilElapsedRealtime;
    }

    public void markUnlocked() {
        unlockedUntilElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                + UNLOCK_SESSION_MILLIS;
    }

    public void lockNow() {
        unlockedUntilElapsedRealtime = 0L;
    }

    public boolean expiryAlertsEnabled() {
        return preferences.getBoolean(KEY_EXPIRY_ALERTS, true);
    }

    public void setExpiryAlertsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_EXPIRY_ALERTS, enabled).apply();
    }

    public int reminderDays() {
        int saved = preferences.getInt(
                KEY_REMINDER_DAYS,
                DEFAULT_REMINDER_DAYS
        );
        if (saved == 7 || saved == 15 || saved == 30 || saved == 60) {
            return saved;
        }
        return DEFAULT_REMINDER_DAYS;
    }

    public void setReminderDays(int days) {
        int safeDays = days == 7 || days == 15 || days == 60
                ? days
                : DEFAULT_REMINDER_DAYS;
        preferences.edit().putInt(KEY_REMINDER_DAYS, safeDays).apply();
    }

    public boolean isFavorite(long documentId) {
        return favoriteIds().contains(String.valueOf(documentId));
    }

    public boolean toggleFavorite(long documentId) {
        Set<String> ids = favoriteIds();
        String id = String.valueOf(documentId);
        boolean favorite;
        if (ids.contains(id)) {
            ids.remove(id);
            favorite = false;
        } else {
            ids.add(id);
            favorite = true;
        }
        preferences.edit().putStringSet(KEY_FAVORITES, ids).apply();
        return favorite;
    }

    public void removeFavorite(long documentId) {
        Set<String> ids = favoriteIds();
        ids.remove(String.valueOf(documentId));
        preferences.edit().putStringSet(KEY_FAVORITES, ids).apply();
    }

    @NonNull
    private Set<String> favoriteIds() {
        Set<String> stored = preferences.getStringSet(KEY_FAVORITES, null);
        return stored == null
                ? new HashSet<>()
                : new HashSet<>(stored);
    }
}
