package com.tridev.familyhub.location;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.HashMap;
import java.util.Map;

/** Stores only alert state, timestamps and display labels; never coordinates. */
public final class FamilyDeviceSafetyAlertStateStore {

    private static final String PREFS =
            "family_hub_device_safety_alert_state";
    private static final String PREFIX_ACTIVE = "active_";
    private static final String PREFIX_LAST_ALERT = "last_alert_";
    private static final String PREFIX_MEMBER_NAME = "member_name_";

    private final SharedPreferences preferences;
    private final String viewerPrefix;

    public FamilyDeviceSafetyAlertStateStore(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        viewerPrefix = resolveViewerPrefix();
    }

    public boolean shouldDispatch(
            @NonNull String memberUid,
            @NonNull String alertType,
            long now
    ) {
        if (!FamilyDeviceSafetyAlertPolicy.isSupported(alertType)
                || now <= 0L) {
            return false;
        }
        boolean active = preferences.getBoolean(
                key(PREFIX_ACTIVE, memberUid, alertType),
                false
        );
        long lastAlertAt = preferences.getLong(
                key(PREFIX_LAST_ALERT, memberUid, alertType),
                0L
        );
        return !active
                || lastAlertAt <= 0L
                || now - lastAlertAt
                >= FamilyDeviceSafetyAlertPolicy.cooldownMs(alertType);
    }

    public void recordActive(
            @NonNull String memberUid,
            @NonNull String memberName,
            @NonNull String alertType,
            long alertAt,
            boolean alertDispatched
    ) {
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(key(PREFIX_ACTIVE, memberUid, alertType), true)
                .putString(nameKey(memberUid), safeName(memberName));
        if (alertDispatched && alertAt > 0L) {
            editor.putLong(
                    key(PREFIX_LAST_ALERT, memberUid, alertType),
                    alertAt
            );
        }
        editor.apply();
    }

    public void clearActive(
            @NonNull String memberUid,
            @NonNull String alertType
    ) {
        preferences.edit()
                .putBoolean(key(PREFIX_ACTIVE, memberUid, alertType), false)
                .apply();
    }

    public void clearAllConditions(@NonNull String memberUid) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(key(
                PREFIX_ACTIVE,
                memberUid,
                FamilyDeviceSafetyAlertPolicy.ALERT_NO_UPDATE
        ), false);
        editor.putBoolean(key(
                PREFIX_ACTIVE,
                memberUid,
                FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY
        ), false);
        editor.putBoolean(key(
                PREFIX_ACTIVE,
                memberUid,
                FamilyDeviceSafetyAlertPolicy.ALERT_DEVICE_OFFLINE
        ), false);
        editor.apply();
    }

    public void rememberMemberName(
            @NonNull String memberUid,
            @NonNull String memberName
    ) {
        preferences.edit()
                .putString(nameKey(memberUid), safeName(memberName))
                .apply();
    }

    @NonNull
    public String memberName(@NonNull String memberUid) {
        String value = preferences.getString(nameKey(memberUid), "");
        return value == null ? "" : value.trim();
    }

    @NonNull
    public Map<String, String> knownMemberNames() {
        Map<String, String> result = new HashMap<>();
        String prefix = viewerPrefix + PREFIX_MEMBER_NAME;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!key.startsWith(prefix) || !(value instanceof String)) {
                continue;
            }
            String memberUid = key.substring(prefix.length());
            String name = ((String) value).trim();
            if (!memberUid.isEmpty() && !name.isEmpty()) {
                result.put(
                        FamilyDeviceSafetyAlertPolicy.memberPlaceId(memberUid),
                        name
                );
            }
        }
        return result;
    }

    @NonNull
    private String key(
            @NonNull String prefix,
            @NonNull String memberUid,
            @NonNull String alertType
    ) {
        return viewerPrefix
                + prefix
                + safeKey(memberUid)
                + "_"
                + safeKey(alertType);
    }

    @NonNull
    private String nameKey(@NonNull String memberUid) {
        return viewerPrefix + PREFIX_MEMBER_NAME + safeKey(memberUid);
    }

    @NonNull
    private static String resolveViewerPrefix() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user == null ? "" : user.getUid();
        if (uid == null || uid.trim().isEmpty()) {
            uid = "local_member";
        }
        return safeKey(uid) + "_";
    }

    @NonNull
    private static String safeKey(@NonNull String value) {
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @NonNull
    private static String safeName(@NonNull String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 100
                ? trimmed
                : trimmed.substring(0, 100);
    }
}
