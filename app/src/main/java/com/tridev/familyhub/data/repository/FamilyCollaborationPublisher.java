package com.tridev.familyhub.data.repository;

import com.tridev.familyhub.core.security.AccountDataScope;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.UUID;

/** Privacy-gated publisher for opt-in family collaboration records. */
final class FamilyCollaborationPublisher {

    interface PublishedCallback {
        void onPublished(@NonNull String cloudId, @NonNull String familyId,
                         @NonNull String uid);
    }

    private FamilyCollaborationPublisher() { }

    static void publish(@NonNull String expectedUid, @NonNull String module, @NonNull String existingCloudId,
                        @NonNull Map<String, Object> values,
                        @NonNull PublishedCallback callback) {
        if (expectedUid.isEmpty() || !expectedUid.equals(AccountDataScope.uid())) return;
        String cloudId = existingCloudId.trim().isEmpty() ? UUID.randomUUID().toString() : existingCloudId;
        FamilySyncOutbox.enqueue(expectedUid, module, cloudId, values, callback);
    }

    static void remove(@NonNull String expectedUid, @NonNull String module, @NonNull String familyId,
                       @NonNull String cloudId) {
        if (expectedUid.isEmpty() || !expectedUid.equals(AccountDataScope.uid())) return;
        FamilySyncOutbox.remove(expectedUid,module,familyId,cloudId);
    }

}
