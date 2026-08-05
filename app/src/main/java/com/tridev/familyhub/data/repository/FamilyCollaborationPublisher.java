package com.tridev.familyhub.data.repository;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Privacy-gated publisher for opt-in family collaboration records. */
final class FamilyCollaborationPublisher {

    interface PublishedCallback {
        void onPublished(@NonNull String cloudId, @NonNull String familyId,
                         @NonNull String uid);
    }

    private FamilyCollaborationPublisher() { }

    static void publish(@NonNull String module, @NonNull String existingCloudId,
                        @NonNull Map<String, Object> values,
                        @NonNull PublishedCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return;
        }
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(FamilyAccountRepository.SessionState state) {
                        if (state == null || !state.isActive() || state.familyId == null) {
                            return;
                        }
                        String cloudId = existingCloudId.trim().isEmpty()
                                ? UUID.randomUUID().toString() : existingCloudId;
                        Map<String, Object> payload = new HashMap<>(values);
                        payload.put("cloudId", cloudId);
                        payload.put("familyId", state.familyId);
                        payload.put("updatedByUid", user.getUid());
                        payload.put("updatedAt", System.currentTimeMillis());
                        payload.put("serverUpdatedAt", ServerValue.TIMESTAMP);
                        FirebaseDatabase.getInstance().getReference()
                                .child("sharedModules").child(state.familyId)
                                .child(module).child(cloudId)
                                .updateChildren(payload)
                                .addOnSuccessListener(unused -> callback.onPublished(
                                        cloudId, state.familyId, user.getUid()
                                ));
                    }

                    @Override public void onError(@NonNull Exception error) {
                        // Local-first records remain available and can be shared later.
                    }
                }
        );
    }

    static void remove(@NonNull String module, @NonNull String familyId,
                       @NonNull String cloudId) {
        if (familyId.trim().isEmpty() || cloudId.trim().isEmpty()) {
            return;
        }
        FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(familyId)
                .child(module).child(cloudId).removeValue();
    }
}
