package com.tridev.familyhub.feature.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Stores only family/member identifiers needed for offline schedule fallback. */
public final class FamilyAutomationSessionStore {

    private static final String PREFS = "family_automation_session";
    private static final String KEY_UID = "uid";
    private static final String KEY_FAMILY_ID = "family_id";
    private static final String KEY_DISPLAY_NAME = "display_name";

    private FamilyAutomationSessionStore() {
    }

    public static void save(
            @NonNull Context context,
            @NonNull String uid,
            @NonNull String familyId,
            @NonNull String displayName
    ) {
        preferences(context).edit()
                .putString(KEY_UID, uid.trim())
                .putString(KEY_FAMILY_ID, familyId.trim())
                .putString(KEY_DISPLAY_NAME, displayName.trim())
                .apply();
    }

    @NonNull
    public static Snapshot load(
            @NonNull Context context,
            @NonNull String expectedUid
    ) {
        SharedPreferences preferences = preferences(context);
        String uid = safe(preferences.getString(KEY_UID, ""));
        if (!expectedUid.equals(uid)) {
            return new Snapshot("", "");
        }
        return new Snapshot(
                safe(preferences.getString(KEY_FAMILY_ID, "")),
                safe(preferences.getString(KEY_DISPLAY_NAME, ""))
        );
    }

    public static void clear(@NonNull Context context) {
        preferences(context).edit().clear().apply();
    }

    public static final class Snapshot {
        @NonNull public final String familyId;
        @NonNull public final String displayName;

        Snapshot(
                @NonNull String familyId,
                @NonNull String displayName
        ) {
            this.familyId = familyId;
            this.displayName = displayName;
        }

        public boolean isValid() {
            return !familyId.isEmpty();
        }
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
