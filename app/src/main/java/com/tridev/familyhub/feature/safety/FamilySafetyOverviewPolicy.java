package com.tridev.familyhub.feature.safety;

import androidx.annotation.NonNull;

/** Pure priority policy for the unified Family Safety Centre overview. */
public final class FamilySafetyOverviewPolicy {

    public static final String STATE_ALL_CLEAR = "ALL_CLEAR";
    public static final String STATE_ATTENTION = "ATTENTION";
    public static final String STATE_EMERGENCY = "EMERGENCY";

    private FamilySafetyOverviewPolicy() {
    }

    @NonNull
    public static String resolve(
            int activeSosCount,
            int attentionMemberCount,
            int unreadAlertCount
    ) {
        if (Math.max(0, activeSosCount) > 0) {
            return STATE_EMERGENCY;
        }
        if (Math.max(0, attentionMemberCount) > 0
                || Math.max(0, unreadAlertCount) > 0) {
            return STATE_ATTENTION;
        }
        return STATE_ALL_CLEAR;
    }
}
