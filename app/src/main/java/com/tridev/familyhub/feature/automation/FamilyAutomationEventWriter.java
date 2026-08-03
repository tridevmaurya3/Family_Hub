package com.tridev.familyhub.feature.automation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/** Writes owner-device automation events without storing exact coordinates. */
public final class FamilyAutomationEventWriter {

    private FamilyAutomationEventWriter() {
    }

    public static void dispatch(
            @NonNull Context context,
            @NonNull String familyId,
            @NonNull String targetUid,
            @NonNull String targetName,
            @Nullable FamilyAutomationRule rule,
            @NonNull String eventType,
            @NonNull String severity,
            @Nullable String placeName,
            @NonNull String detail,
            @NonNull String deduplicationKey,
            boolean notifyTrustedViewers,
            long occurredAt
    ) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String safeFamilyId = bounded(familyId, 128);
        String safeTargetUid = bounded(targetUid, 128);
        String safeDeduplicationKey = bounded(deduplicationKey, 160);
        if (user == null
                || !user.isEmailVerified()
                || !user.getUid().equals(safeTargetUid)
                || safeFamilyId.isEmpty()
                || safeDeduplicationKey.isEmpty()) {
            return;
        }

        FamilyAutomationStateStore state =
                new FamilyAutomationStateStore(context);
        if (!state.shouldDispatch(safeDeduplicationKey, occurredAt)) {
            return;
        }

        DatabaseReference branch = FirebaseDatabase.getInstance()
                .getReference()
                .child("familyAutomationEvents")
                .child(safeFamilyId)
                .child(safeTargetUid);
        String eventId = branch.push().getKey();
        if (eventId == null) {
            return;
        }

        Map<String, Object> values = new HashMap<>();
        values.put("eventId", eventId);
        values.put("familyId", safeFamilyId);
        values.put("targetUid", safeTargetUid);
        values.put("targetName", bounded(targetName, 100));
        values.put(
                "ruleId",
                rule == null ? "" : bounded(rule.ruleId, 128)
        );
        values.put(
                "ruleTitle",
                rule == null ? "" : bounded(rule.safeTitle(), 100)
        );
        values.put("type", bounded(eventType, 32));
        values.put("severity", bounded(severity, 16));
        values.put("placeName", bounded(placeName, 100));
        values.put("detail", bounded(detail, 240));
        values.put("deduplicationKey", safeDeduplicationKey);
        values.put("notifyTrustedViewers", notifyTrustedViewers);
        values.put("occurredAt", Math.max(1L, occurredAt));
        values.put("createdAt", ServerValue.TIMESTAMP);

        branch.child(eventId)
                .setValue(values)
                .addOnSuccessListener(unused ->
                        state.recordDispatched(
                                safeDeduplicationKey,
                                occurredAt
                        ));
    }

    @NonNull
    private static String bounded(@Nullable String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength));
    }
}
