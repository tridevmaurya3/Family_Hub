package com.tridev.familyhub.feature.sos;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** Firebase-friendly Family SOS record. */
public final class FamilySosAlert {

    @NonNull public String sosId = "";
    @NonNull public String familyId = "";
    @NonNull public String senderUid = "";
    @NonNull public String senderName = "";
    @NonNull public String status = FamilySosPolicy.STATUS_ACTIVE;
    @NonNull public String message = "";
    public boolean hasLocation;
    public double latitude;
    public double longitude;
    public double accuracy;
    @NonNull public String placeLabel = "";
    public long locationUpdatedAt;
    public long createdAt;
    public long clientCreatedAt;
    public long updatedAt;
    public long cancelledAt;
    @NonNull public String cancelledBy = "";
    @NonNull public Map<String, FamilySosResponse> responses =
            new LinkedHashMap<>();

    public FamilySosAlert() {
        // Required by Firebase.
    }

    public long effectiveCreatedAt() {
        return createdAt > 0L ? createdAt : clientCreatedAt;
    }

    public boolean hasResponseFrom(@NonNull String uid) {
        return responses.containsKey(uid);
    }
}
