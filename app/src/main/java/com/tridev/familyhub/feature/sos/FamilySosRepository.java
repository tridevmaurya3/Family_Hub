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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private long lastLocalRequestAt;
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
        if (now - lastLocalRequestAt < FamilySosPolicy.LOCAL_REQUEST_COOLDOWN_MS) {
            callback.onError("PLEASE_WAIT");
            return;
        }
        lastLocalRequestAt = now;
        ensureSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session activeSession) {
                findExistingActiveSos(activeSession, existingId -> {
                    if (existingId != null) {
                        callback.onError("SOS_ALREADY_ACTIVE");
                        return;
                    }
                    createSosFromLatestLocation(activeSession, callback);
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
                        .updateChildren(updates)
                        .addOnSuccessListener(unused ->
                                callback.onSuccess(sosId))
                        .addOnFailureListener(error ->
                                callback.onError("CANCEL_FAILED"));
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
                response.put("displayName", activeSession.displayName);
                response.put("state", FamilySosPolicy.RESPONSE_RESPONDING);
                response.put("respondedAt", ServerValue.TIMESTAMP);

                root.child("familySos")
                        .child(activeSession.familyId)
                        .child(sosId)
                        .child("responses")
                        .child(activeSession.uid)
                        .setValue(response)
                        .addOnSuccessListener(unused ->
                                callback.onSuccess(sosId))
                        .addOnFailureListener(error ->
                                callback.onError("RESPONSE_FAILED"));
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
                    FamilySosAlert alert = child.getValue(FamilySosAlert.class);
                    if (alert == null
                            || !FamilySosPolicy.isSupportedStatus(alert.status)) {
                        continue;
                    }
                    if (alert.sosId.trim().isEmpty()) {
                        alert.sosId = child.getKey() == null
                                ? ""
                                : child.getKey();
                    }
                    alerts.add(alert);
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
                    listener.onError("SOS_LIST_FAILED");
                }
            }
        };
        sosReference.addValueEventListener(sosListener);
    }

    private void createSosFromLatestLocation(
            @NonNull Session activeSession,
            @NonNull ActionCallback callback
    ) {
        root.child("locations")
                .child(activeSession.familyId)
                .child(activeSession.uid)
                .get()
                .addOnSuccessListener(location -> {
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
                    values.put("senderName", activeSession.displayName);
                    values.put("status", FamilySosPolicy.STATUS_ACTIVE);
                    values.put("message", "Emergency help requested");
                    values.put("clientCreatedAt", now);
                    values.put("createdAt", ServerValue.TIMESTAMP);
                    values.put("updatedAt", ServerValue.TIMESTAMP);

                    appendLocation(values, location, now);
                    newReference.setValue(values)
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(sosId))
                            .addOnFailureListener(error ->
                                    callback.onError("SOS_CREATE_FAILED"));
                })
                .addOnFailureListener(error -> {
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
                    values.put("senderName", activeSession.displayName);
                    values.put("status", FamilySosPolicy.STATUS_ACTIVE);
                    values.put("message", "Emergency help requested");
                    values.put("hasLocation", false);
                    values.put("placeLabel", "");
                    values.put("locationUpdatedAt", 0L);
                    values.put("clientCreatedAt", now);
                    values.put("createdAt", ServerValue.TIMESTAMP);
                    values.put("updatedAt", ServerValue.TIMESTAMP);
                    newReference.setValue(values)
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(sosId))
                            .addOnFailureListener(writeError ->
                                    callback.onError("SOS_CREATE_FAILED"));
                });
    }

    private void appendLocation(
            @NonNull Map<String, Object> values,
            @NonNull DataSnapshot location,
            long now
    ) {
        Double latitude = location.child("latitude").getValue(Double.class);
        Double longitude = location.child("longitude").getValue(Double.class);
        Double accuracy = location.child("accuracy").getValue(Double.class);
        Long updatedAt = location.child("updatedAt").getValue(Long.class);
        Boolean sharingEnabled = location.child("sharingEnabled")
                .getValue(Boolean.class);
        String placeLabel = location.child("placeLabel").getValue(String.class);

        boolean valid = Boolean.TRUE.equals(sharingEnabled)
                && latitude != null
                && longitude != null
                && accuracy != null
                && updatedAt != null
                && FamilySosPolicy.validCoordinates(
                        latitude,
                        longitude,
                        accuracy
                )
                && FamilySosPolicy.isFreshLocation(updatedAt, now);

        values.put("hasLocation", valid);
        values.put("placeLabel", placeLabel == null ? "" : placeLabel.trim());
        values.put("locationUpdatedAt", valid ? updatedAt : 0L);
        if (valid) {
            values.put("latitude", latitude);
            values.put("longitude", longitude);
            values.put("accuracy", accuracy);
        }
    }

    private void findExistingActiveSos(
            @NonNull Session activeSession,
            @NonNull ExistingCallback existingCallback,
            @NonNull ActionCallback actionCallback
    ) {
        root.child("familySos")
                .child(activeSession.familyId)
                .get()
                .addOnSuccessListener(snapshot -> {
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
                })
                .addOnFailureListener(error ->
                        actionCallback.onError("SOS_CHECK_FAILED"));
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
                                        displayName.trim()
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

    private interface SessionCallback {
        void onReady(@NonNull Session session);

        void onError(@NonNull String reason);
    }

    private interface ExistingCallback {
        void onFound(@Nullable String existingId);
    }
}
