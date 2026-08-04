package com.tridev.familyhub.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.core.security.VaultCipher;

import java.util.Arrays;

/** Device-local backup configuration; passwords are wrapped by Android Keystore. */
public final class BackupPreferences {

    public static final String FREQUENCY_DAILY = "DAILY";
    public static final String FREQUENCY_WEEKLY = "WEEKLY";

    private static final String PREFS = "family_hub_encrypted_backup";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";
    private static final String KEY_PASSWORD = "password_wrapped";
    private static final String KEY_AUTO_ENABLED = "auto_enabled";
    private static final String KEY_FREQUENCY = "frequency";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_CHARGING_ONLY = "charging_only";
    private static final String KEY_LAST_SUCCESS_AT = "last_success_at";
    private static final String KEY_LAST_FILE = "last_file";
    private static final String KEY_LAST_RECORDS = "last_records";
    private static final String KEY_LAST_ATTACHMENTS = "last_attachments";
    private static final String KEY_LAST_BYTES = "last_bytes";
    private static final String KEY_LAST_ERROR = "last_error";

    private final SharedPreferences preferences;

    public BackupPreferences(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setDestination(
            @NonNull Uri treeUri,
            @NonNull String label
    ) {
        preferences.edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_TREE_LABEL, label.trim())
                .apply();
    }

    public void clearDestination() {
        preferences.edit()
                .remove(KEY_TREE_URI)
                .remove(KEY_TREE_LABEL)
                .putBoolean(KEY_AUTO_ENABLED, false)
                .apply();
    }

    @Nullable
    public Uri destinationTreeUri() {
        String value = preferences.getString(KEY_TREE_URI, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    public String destinationLabel() {
        return preferences.getString(KEY_TREE_LABEL, "") == null
                ? ""
                : preferences.getString(KEY_TREE_LABEL, "");
    }

    public void savePassword(@NonNull char[] password) {
        String value = new String(password);
        try {
            preferences.edit()
                    .putString(KEY_PASSWORD, VaultCipher.encrypt(value))
                    .apply();
        } finally {
            value = "";
        }
    }

    public boolean hasPassword() {
        String wrapped = preferences.getString(KEY_PASSWORD, null);
        return wrapped != null && !wrapped.isEmpty();
    }

    @Nullable
    public char[] readPassword() {
        String wrapped = preferences.getString(KEY_PASSWORD, null);
        if (wrapped == null || wrapped.isEmpty()) {
            return null;
        }
        try {
            String plain = VaultCipher.decryptOrThrow(wrapped);
            char[] result = plain.toCharArray();
            plain = "";
            return result;
        } catch (Exception error) {
            preferences.edit()
                    .remove(KEY_PASSWORD)
                    .putBoolean(KEY_AUTO_ENABLED, false)
                    .apply();
            return null;
        }
    }

    public void clearPassword() {
        preferences.edit()
                .remove(KEY_PASSWORD)
                .putBoolean(KEY_AUTO_ENABLED, false)
                .apply();
    }

    public boolean autoBackupEnabled() {
        return preferences.getBoolean(KEY_AUTO_ENABLED, false);
    }

    public void setAutoBackupEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply();
    }

    @NonNull
    public String frequency() {
        String value = preferences.getString(
                KEY_FREQUENCY,
                FREQUENCY_WEEKLY
        );
        return FREQUENCY_DAILY.equals(value)
                ? FREQUENCY_DAILY
                : FREQUENCY_WEEKLY;
    }

    public void setFrequency(@NonNull String frequency) {
        preferences.edit().putString(
                KEY_FREQUENCY,
                FREQUENCY_DAILY.equals(frequency)
                        ? FREQUENCY_DAILY
                        : FREQUENCY_WEEKLY
        ).apply();
    }

    public boolean wifiOnly() {
        return preferences.getBoolean(KEY_WIFI_ONLY, true);
    }

    public void setWifiOnly(boolean enabled) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply();
    }

    public boolean chargingOnly() {
        return preferences.getBoolean(KEY_CHARGING_ONLY, true);
    }

    public void setChargingOnly(boolean enabled) {
        preferences.edit().putBoolean(KEY_CHARGING_ONLY, enabled).apply();
    }

    public void recordSuccess(
            long completedAt,
            @NonNull String fileName,
            long recordCount,
            int attachmentCount,
            long byteCount
    ) {
        preferences.edit()
                .putLong(KEY_LAST_SUCCESS_AT, completedAt)
                .putString(KEY_LAST_FILE, fileName)
                .putLong(KEY_LAST_RECORDS, recordCount)
                .putInt(KEY_LAST_ATTACHMENTS, attachmentCount)
                .putLong(KEY_LAST_BYTES, byteCount)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public void recordFailure(@NonNull String message) {
        preferences.edit().putString(KEY_LAST_ERROR, message).apply();
    }

    public long lastSuccessAt() {
        return preferences.getLong(KEY_LAST_SUCCESS_AT, 0L);
    }

    @NonNull
    public String lastFileName() {
        String value = preferences.getString(KEY_LAST_FILE, "");
        return value == null ? "" : value;
    }

    public long lastRecordCount() {
        return preferences.getLong(KEY_LAST_RECORDS, 0L);
    }

    public int lastAttachmentCount() {
        return preferences.getInt(KEY_LAST_ATTACHMENTS, 0);
    }

    public long lastByteCount() {
        return preferences.getLong(KEY_LAST_BYTES, 0L);
    }

    @NonNull
    public String lastError() {
        String value = preferences.getString(KEY_LAST_ERROR, "");
        return value == null ? "" : value;
    }

    public boolean isReadyForAutomaticBackup() {
        return autoBackupEnabled()
                && destinationTreeUri() != null
                && hasPassword();
    }

    public static void wipe(@Nullable char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
