package com.tridev.familyhub.feature.sos;

import androidx.annotation.NonNull;

/** Firebase-friendly acknowledgement from an approved family member. */
public final class FamilySosResponse {

    @NonNull public String uid = "";
    @NonNull public String displayName = "";
    @NonNull public String state = FamilySosPolicy.RESPONSE_RESPONDING;
    public long respondedAt;

    public FamilySosResponse() {
        // Required by Firebase.
    }

    public FamilySosResponse(
            @NonNull String uid,
            @NonNull String displayName,
            @NonNull String state,
            long respondedAt
    ) {
        this.uid = uid;
        this.displayName = displayName;
        this.state = state;
        this.respondedAt = respondedAt;
    }
}
