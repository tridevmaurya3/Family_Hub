package com.tridev.familyhub.core.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Per-Firebase-user App Lock settings. The four-digit PIN is encrypted by the
 * existing Android-Keystore-backed VaultCipher; Firebase passwords are never
 * stored here.
 */
public final class AppSecurityStore {

    private static final String PREFS = "family_hub_app_security_v1";
    private static final String KEY_ENABLED = "enabled_";
    private static final String KEY_PIN = "pin_";
    private static final String KEY_BIOMETRIC = "biometric_";
    private static final String KEY_TIMEOUT = "timeout_";
    private static final String KEY_FAILS = "fails_";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until_";

    public static final int DEFAULT_TIMEOUT_MINUTES = 2;
    private static final int MAX_PIN_FAILURES = 5;
    private static final long PIN_LOCKOUT_MILLIS = 30_000L;

    private AppSecurityStore() { }

    public static boolean isProtectionEnabled(@NonNull Context context) {
        String key = userKey();
        if (key.isEmpty()) return false;
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_ENABLED + key, false)
                && !safe(prefs.getString(KEY_PIN + key, "")).isEmpty();
    }

    public static boolean hasPin(@NonNull Context context) {
        String key = userKey();
        return !key.isEmpty()
                && !safe(prefs(context).getString(KEY_PIN + key, "")).isEmpty();
    }

    public static boolean savePin(@NonNull Context context, @NonNull String pin) {
        String key = userKey();
        if (key.isEmpty() || !pin.matches("\\d{4}")) return false;
        try {
            String encrypted = VaultCipher.encrypt(pin);
            if (encrypted.isEmpty()) return false;
            return prefs(context).edit()
                    .putString(KEY_PIN + key, encrypted)
                    .putInt(KEY_FAILS + key, 0)
                    .remove(KEY_LOCKOUT_UNTIL + key)
                    .commit();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static boolean verifyPin(@NonNull Context context, @NonNull String pin) {
        String key = userKey();
        if (key.isEmpty() || !pin.matches("\\d{4}")) return false;
        String encrypted = safe(prefs(context).getString(KEY_PIN + key, ""));
        if (encrypted.isEmpty()) return false;
        try {
            String saved = VaultCipher.decryptOrThrow(encrypted);
            return MessageDigest.isEqual(
                    pin.getBytes(StandardCharsets.UTF_8),
                    saved.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            return false;
        }
    }

    public static boolean setEnabled(@NonNull Context context, boolean enabled) {
        String key = userKey();
        if (key.isEmpty()) return false;
        if (enabled && !hasPin(context)) return false;
        return prefs(context).edit().putBoolean(KEY_ENABLED + key, enabled).commit();
    }

    public static boolean isBiometricEnabled(@NonNull Context context) {
        String key = userKey();
        return !key.isEmpty()
                && prefs(context).getBoolean(KEY_BIOMETRIC + key, false);
    }

    public static boolean setBiometricEnabled(@NonNull Context context, boolean enabled) {
        String key = userKey();
        if (key.isEmpty()) return false;
        return prefs(context).edit().putBoolean(KEY_BIOMETRIC + key, enabled).commit();
    }

    public static int getTimeoutMinutes(@NonNull Context context) {
        String key = userKey();
        if (key.isEmpty()) return DEFAULT_TIMEOUT_MINUTES;
        int value = prefs(context).getInt(KEY_TIMEOUT + key, DEFAULT_TIMEOUT_MINUTES);
        return isAllowedTimeout(value) ? value : DEFAULT_TIMEOUT_MINUTES;
    }

    public static boolean setTimeoutMinutes(@NonNull Context context, int minutes) {
        String key = userKey();
        if (key.isEmpty()) return false;
        int safeMinutes = isAllowedTimeout(minutes)
                ? minutes : DEFAULT_TIMEOUT_MINUTES;
        return prefs(context).edit().putInt(KEY_TIMEOUT + key, safeMinutes).commit();
    }

    public static long remainingPinLockoutMillis(@NonNull Context context) {
        String key = userKey();
        if (key.isEmpty()) return 0L;
        long until = prefs(context).getLong(KEY_LOCKOUT_UNTIL + key, 0L);
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L && until > 0L) {
            prefs(context).edit().remove(KEY_LOCKOUT_UNTIL + key).apply();
            return 0L;
        }
        return Math.max(0L, remaining);
    }

    public static void recordPinFailure(@NonNull Context context) {
        String key = userKey();
        if (key.isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        int failures = prefs.getInt(KEY_FAILS + key, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit();
        if (failures >= MAX_PIN_FAILURES) {
            editor.putInt(KEY_FAILS + key, 0)
                    .putLong(KEY_LOCKOUT_UNTIL + key,
                            System.currentTimeMillis() + PIN_LOCKOUT_MILLIS);
        } else {
            editor.putInt(KEY_FAILS + key, failures);
        }
        editor.apply();
    }

    public static void resetPinFailures(@NonNull Context context) {
        String key = userKey();
        if (key.isEmpty()) return;
        prefs(context).edit()
                .putInt(KEY_FAILS + key, 0)
                .remove(KEY_LOCKOUT_UNTIL + key)
                .apply();
    }

    @NonNull
    public static String currentUserEmail() {
        FirebaseUser user = currentUser();
        return user == null ? "" : safe(user.getEmail());
    }

    private static boolean isAllowedTimeout(int minutes) {
        return minutes == 1 || minutes == 2 || minutes == 5 || minutes == 10;
    }

    @NonNull
    private static String userKey() {
        FirebaseUser user = currentUser();
        if (user == null) return "";
        String uid = safe(user.getUid());
        if (uid.isEmpty()) return "";
        return sha256(uid).substring(0, 24);
    }

    private static FirebaseUser currentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String sha256(@NonNull String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                out.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            return String.format(Locale.US, "%064x", value.hashCode());
        }
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
