package com.tridev.familyhub.feature.sos;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.feature.automation.FirebaseNumericValueReader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Authenticated Realtime Database access for Family SOS Option A. */
public final class FamilySosRepository {

    public interface Listener {
        void onLoaded(
                @NonNull Session session,
                @NonNull List<FamilySosAlert> alerts
        );

        void onError(@NonNull String reason);
    }

    public interface ActionCallback {
        void onSuccess(@Nullable String sosId);

        void onError(@NonNull String reason);
    }

    public static final class Session {
        @NonNull public final String uid;
        @NonNull public final String familyId;
        @NonNull public final String displayName;

        Session(
                @NonNull String uid,
                @NonNull String familyId,
                @NonNull String displayName
        ) {
            this.uid = uid;
            this.familyId = familyId;
            this.displayName = displayName;
        }
    }

    private final DatabaseReference root = FirebaseDatabase
            .getInstance()
            .getReference();

    @Nullable private Session session;
    @Nullable private DatabaseReference sosReference;
    @Nullable private ValueEventListener sosListener;
    @Nullable private Listener listener;
    private long lastSuccessfulRequestAt;
    private int generation;

    public void observe(@NonNull Listener callback) {
        stopObserving();
        listener = callback;
        int requestGeneration = ++generation;
        loadSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session loadedSession) {
                if (requestGeneration != generation || listener == null) {
                    return;
                }
                session = loadedSession;
                attachSosListener(loadedSession, requestGeneration);
            }

            @Override
            public void onError(@NonNull String reason) {
                if (requestGeneration == generation && listener != null) {
                    listener.onError(reason);
                }
            }
        });
    }

    public void requestSos(@NonNull ActionCallback callback) {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulRequestAt
                < FamilySosPolicy.LOCAL_REQUEST_COOLDOWN_MS) {
            callback.onError("PLEASE_WAIT");
            return;
        }
        ensureSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session activeSession) {
                findExistingActiveSos(activeSession, existingId -> {
                    if (existingId != null) {
                        callback.onError("SOS_ALREADY_ACTIVE");
                        return;
                    }
                    createBaseSos(activeSession, callback);
                }, callback);
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    public void cancelSos(
            @NonNull String sosId,
            @NonNull ActionCallback callback
    ) {
        ensureSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session activeSession) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("status", FamilySosPolicy.STATUS_CANCELLED);
                updates.put("updatedAt", ServerValue.TIMESTAMP);
                updates.put("cancelledAt", ServerValue.TIMESTAMP);
                updates.put("cancelledBy", activeSession.uid);
                root.child("familySos")
                        .child(activeSession.familyId)
                        .child(sosId)
                        .updateChildren(updates, (error, reference) -> {
                            if (error == null) {
                                callback.onSuccess(sosId);
                            } else {
                                callback.onError(mapDatabaseError(
                                        error,
                                        "CANCEL_FAILED"
                                ));
                            }
                        });
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    public void respondToSos(
            @NonNull String sosId,
            @NonNull ActionCallback callback
    ) {
        ensureSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session activeSession) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("uid", activeSession.uid);
                response.put("displayName", bounded(
                        activeSession.displayName,
                        100
                ));
                response.put("state", FamilySosPolicy.RESPONSE_RESPONDING);
                response.put("respondedAt", ServerValue.TIMESTAMP);

                root.child("familySos")
                        .child(activeSession.familyId)
                        .child(sosId)
                        .child("responses")
                        .child(activeSession.uid)
                        .setValue(response, (error, reference) -> {
                            if (error == null) {
                                callback.onSuccess(sosId);
                            } else {
                                callback.onError(mapDatabaseError(
                                        error,
                                        "RESPONSE_FAILED"
                                ));
                            }
                        });
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    @Nullable
    public Session currentSession() {
        return session;
    }

    public void close() {
        stopObserving();
        listener = null;
        session = null;
    }

    private void attachSosListener(
            @NonNull Session activeSession,
            int requestGeneration
    ) {
        sosReference = root.child("familySos").child(activeSession.familyId);
        sosListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (requestGeneration != generation || listener == null) {
                    return;
                }
                List<FamilySosAlert> alerts = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    try {
                        FamilySosAlert alert = child.getValue(
                                FamilySosAlert.class
                        );
                        if (alert == null
                                || !FamilySosPolicy.isSupportedStatus(
                                alert.status
                        )) {
                            continue;
                        }
                        if (alert.sosId == null) {
                            alert.sosId = "";
                        }
                        if (alert.sosId.trim().isEmpty()) {
                            alert.sosId = child.getKey() == null
                                    ? ""
                                    : child.getKey();
                        }
                        alerts.add(alert);
                    } catch (RuntimeException ignored) {
                        // One legacy/corrupt SOS must not block the centre.
                    }
                }
                alerts.sort(Comparator.comparingLong(
                        FamilySosAlert::effectiveCreatedAt
                ).reversed());
                if (alerts.size() > FamilySosPolicy.MAX_HISTORY_ITEMS) {
                    alerts = new ArrayList<>(alerts.subList(
                            0,
                            FamilySosPolicy.MAX_HISTORY_ITEMS
                    ));
                }
                listener.onLoaded(activeSession, alerts);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (requestGeneration == generation && listener != null) {
                    listener.onError(mapDatabaseError(
                            error,
                            "SOS_LIST_FAILED"
                    ));
                }
            }
        };
        sosReference.addValueEventListener(sosListener);
    }

    /**
     * Sends the emergency alert first without depending on location parsing.
     * A valid recent Family Live location is attached immediately afterwards.
     */
    private void createBaseSos(
            @NonNull Session activeSession,
            @NonNull ActionCallback callback
    ) {
        DatabaseReference newReference = root
                .child("familySos")
                .child(activeSession.familyId)
                .push();
        String sosId = newReference.getKey();
        if (sosId == null || sosId.trim().isEmpty()) {
            callback.onError("SOS_CREATE_FAILED");
            return;
        }

        long now = System.currentTimeMillis();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sosId", sosId);
        values.put("familyId", activeSession.familyId);
        values.put("senderUid", activeSession.uid);
        values.put("senderName", bounded(activeSession.displayName, 100));
        values.put("status", FamilySosPolicy.STATUS_ACTIVE);
        values.put("message", "Emergency help requested");
        values.put("hasLocation", false);
        values.put("placeLabel", "");
        values.put("locationUpdatedAt", 0L);
        values.put("clientCreatedAt", now);
        values.put("createdAt", ServerValue.TIMESTAMP);
        values.put("updatedAt", ServerValue.TIMESTAMP);

        newReference.setValue(values, (error, reference) -> {
            if (error != null) {
                callback.onError(mapDatabaseError(
                        error,
                        "SOS_CREATE_FAILED"
                ));
                return;
            }
            lastSuccessfulRequestAt = now;
            callback.onSuccess(sosId);
            attachLatestLocation(activeSession, sosId, now);
        });
    }

    private void attachLatestLocation(
            @NonNull Session activeSession,
            @NonNull String sosId,
            long requestAt
    ) {
        root.child("locations")
                .child(activeSession.familyId)
                .child(activeSession.uid)
                .get()
                .addOnSuccessListener(location -> {
                    Map<String, Object> update = safeLocationUpdate(
                            location,
                            requestAt
                    );
                    if (update.isEmpty()) {
                        return;
                    }
                    root.child("familySos")
                            .child(activeSession.familyId)
                            .child(sosId)
                            .updateChildren(update);
                });
    }

    @NonNull
    private Map<String, Object> safeLocationUpdate(
            @NonNull DataSnapshot location,
            long now
    ) {
        Map<String, Object> update = new LinkedHashMap<>();
        Double latitude = FirebaseNumericValueReader.doubleValue(
                location.child("latitude")
        );
        Double longitude = FirebaseNumericValueReader.doubleValue(
                location.child("longitude")
        );
        Double accuracy = FirebaseNumericValueReader.doubleValue(
                location.child("accuracy")
        );
        long updatedAt = FirebaseNumericValueReader.nonNegativeLong(
                location.child("updatedAt"),
                0L
        );
        if (updatedAt <= 0L) {
            updatedAt = FirebaseNumericValueReader.nonNegativeLong(
                    location.child("clientTimestamp"),
                    0L
            );
        }
        Boolean sharingEnabled = location.child("sharingEnabled")
                .getValue(Boolean.class);
        String placeLabel = location.child("placeLabel")
                .getValue(String.class);

        boolean valid = Boolean.TRUE.equals(sharingEnabled)
                && latitude != null
                && longitude != null
                && accuracy != null
                && FamilySosPolicy.validCoordinates(
                        latitude,
                        longitude,
                        accuracy
                )
                && FamilySosPolicy.isFreshLocation(updatedAt, now);
        if (!valid) {
            return update;
        }

        update.put("hasLocation", true);
        update.put("latitude", latitude);
        update.put("longitude", longitude);
        update.put("accuracy", accuracy);
        update.put("placeLabel", bounded(placeLabel, 160));
        update.put("locationUpdatedAt", updatedAt);
        update.put("updatedAt", ServerValue.TIMESTAMP);
        return update;
    }

    private void findExistingActiveSos(
            @NonNull Session activeSession,
            @NonNull ExistingCallback existingCallback,
            @NonNull ActionCallback actionCallback
    ) {
        root.child("familySos")
                .child(activeSession.familyId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String senderUid = child.child("senderUid")
                                    .getValue(String.class);
                            String status = child.child("status")
                                    .getValue(String.class);
                            if (activeSession.uid.equals(senderUid)
                                    && FamilySosPolicy.isActive(status)) {
                                existingCallback.onFound(child.getKey());
                                return;
                            }
                        }
                        existingCallback.onFound(null);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        actionCallback.onError(mapDatabaseError(
                                error,
                                "SOS_CHECK_FAILED"
                        ));
                    }
                });
    }

    private void ensureSession(@NonNull SessionCallback callback) {
        Session current = session;
        if (current != null) {
            callback.onReady(current);
            return;
        }
        loadSession(callback);
    }

    private void loadSession(@NonNull SessionCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            callback.onError("AUTH_REQUIRED");
            return;
        }
        root.child("users")
                .child(user.getUid())
                .get()
                .addOnSuccessListener(userSnapshot -> {
                    String familyId = userSnapshot.child("familyId")
                            .getValue(String.class);
                    String status = userSnapshot.child("status")
                            .getValue(String.class);
                    if (familyId == null
                            || familyId.trim().isEmpty()
                            || !"ACTIVE".equals(status)) {
                        callback.onError("ACTIVE_FAMILY_REQUIRED");
                        return;
                    }
                    String safeFamilyId = familyId.trim();
                    root.child("memberships")
                            .child(safeFamilyId)
                            .child(user.getUid())
                            .get()
                            .addOnSuccessListener(membership -> {
                                String memberStatus = membership
                                        .child("status")
                                        .getValue(String.class);
                                if (!"ACTIVE".equals(memberStatus)) {
                                    callback.onError(
                                            "MEMBERSHIP_NOT_ACTIVE"
                                    );
                                    return;
                                }
                                String displayName = membership
                                        .child("displayName")
                                        .getValue(String.class);
                                if (displayName == null
                                        || displayName.trim().isEmpty()) {
                                    displayName = user.getDisplayName();
                                }
                                if (displayName == null
                                        || displayName.trim().isEmpty()) {
                                    displayName = user.getEmail();
                                }
                                if (displayName == null
                                        || displayName.trim().isEmpty()) {
                                    displayName = "Family member";
                                }
                                Session loaded = new Session(
                                        user.getUid(),
                                        safeFamilyId,
                                        bounded(displayName, 100)
                                );
                                session = loaded;
                                callback.onReady(loaded);
                            })
                            .addOnFailureListener(error ->
                                    callback.onError("MEMBER_LOAD_FAILED"));
                })
                .addOnFailureListener(error ->
                        callback.onError("FAMILY_LOAD_FAILED"));
    }

    private void stopObserving() {
        generation++;
        if (sosReference != null && sosListener != null) {
            sosReference.removeEventListener(sosListener);
        }
        sosReference = null;
        sosListener = null;
    }

    @NonNull
    private static String mapDatabaseError(
            @NonNull DatabaseError error,
            @NonNull String fallback
    ) {
        switch (error.getCode()) {
            case DatabaseError.PERMISSION_DENIED:
            case DatabaseError.EXPIRED_TOKEN:
            case DatabaseError.INVALID_TOKEN:
                return "SOS_PERMISSION_DENIED";
            case DatabaseError.DISCONNECTED:
            case DatabaseError.NETWORK_ERROR:
            case DatabaseError.UNAVAILABLE:
                return "SOS_CONNECTION_FAILED";
            case DatabaseError.OPERATION_FAILED:
            case DatabaseError.USER_CODE_EXCEPTION:
                return "SOS_DATA_REJECTED";
            default:
                return fallback;
        }
    }

    @NonNull
    private static String bounded(@Nullable String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength));
    }

    private interface SessionCallback {
        void onReady(@NonNull Session session);

        void onError(@NonNull String reason);
    }

    private interface ExistingCallback {
        void onFound(@Nullable String existingId);
    }
}
