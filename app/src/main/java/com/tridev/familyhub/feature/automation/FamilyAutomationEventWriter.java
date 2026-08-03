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
        if (user == null
                || !user.isEmailVerified()
                || !user.getUid().equals(targetUid)
                || familyId.trim().isEmpty()
                || deduplicationKey.trim().isEmpty()) {
            return;
        }

        FamilyAutomationStateStore state =
                new FamilyAutomationStateStore(context);
        if (!state.shouldDispatch(deduplicationKey, occurredAt)) {
            return;
        }

        DatabaseReference branch = FirebaseDatabase.getInstance()
                .getReference()
                .child("familyAutomationEvents")
                .child(familyId)
                .child(targetUid);
        String eventId = branch.push().getKey();
        if (eventId == null) {
            return;
        }

        Map<String, Object> values = new HashMap<>();
        values.put("eventId", eventId);
        values.put("familyId", familyId);
        values.put("targetUid", targetUid);
        values.put("targetName", trim(targetName));
        values.put("ruleId", rule == null ? "" : trim(rule.ruleId));
        values.put("ruleTitle", rule == null ? "" : trim(rule.safeTitle()));
        values.put("type", trim(eventType));
        values.put("severity", trim(severity));
        values.put("placeName", trim(placeName));
        values.put("detail", trim(detail));
        values.put("deduplicationKey", trim(deduplicationKey));
        values.put("notifyTrustedViewers", notifyTrustedViewers);
        values.put("occurredAt", occurredAt);
        values.put("createdAt", ServerValue.TIMESTAMP);

        branch.child(eventId)
                .setValue(values)
                .addOnSuccessListener(unused ->
                        state.recordDispatched(
                                deduplicationKey,
                                occurredAt
                        ));
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
