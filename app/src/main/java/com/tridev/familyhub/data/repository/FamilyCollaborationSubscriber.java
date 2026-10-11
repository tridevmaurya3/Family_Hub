package com.tridev.familyhub.data.repository;

import com.tridev.familyhub.core.security.AccountDataScope;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/** Reusable authenticated, family-scoped realtime listener for shared modules. */
final class FamilyCollaborationSubscriber {

    interface Callback {
        void onChanged(@NonNull String familyId, @NonNull DataSnapshot snapshot);
        void onRemoved(@NonNull String familyId, @NonNull String cloudId);
        default void onSnapshot(@NonNull String familyId, @NonNull java.util.Set<String> cloudIds) { }
    }

    interface SyncStatusCallback { void onStateChanged(boolean live, boolean connecting); }
    @Nullable private final SyncStatusCallback syncStatus;
    @Nullable private DatabaseReference connectionReference;
    @Nullable private ValueEventListener connectionListener, readyListener;
    private boolean connected, ready, cancelled;

    @NonNull private final String module;
    @NonNull private final Callback callback;
    @Nullable private DatabaseReference reference;
    @Nullable private ChildEventListener listener;
    @Nullable private ValueEventListener snapshotListener;
    private String listeningFamily = "";
    private final java.util.Set<String> remoteIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    boolean hasRemoteId(String cloudId) { return remoteIds.contains(cloudId); }
    private final FamilySyncOutbox.AcknowledgedListener acknowledged;
    private int generation;
    private final String scopeUid;
    @Nullable private com.google.firebase.auth.FirebaseAuth.AuthStateListener authListener;

    FamilyCollaborationSubscriber(@NonNull String expectedUid, @NonNull String module,
                                  @NonNull Callback callback) {
        this(expectedUid, module, callback, null);
    }

    FamilyCollaborationSubscriber(@NonNull String expectedUid, @NonNull String module, @NonNull Callback callback,
                                  @Nullable SyncStatusCallback syncStatus) {
        this.scopeUid = expectedUid;
        this.syncStatus = syncStatus;
        this.module = module;
        this.callback = callback;
        acknowledged = (uid, changedModule, family, id) -> {
            if (!scopeUid.equals(uid) || !module.equals(changedModule) || !listeningFamily.equals(family) || reference == null) return;
            int request = generation;
            reference.child(id).get().addOnSuccessListener(snapshot -> {
                if (!current(request)) return;
                if (snapshot.exists()) { remoteIds.add(id); callback.onChanged(family,snapshot); }
                else { remoteIds.remove(id); callback.onRemoved(family,id); }
            });
        };
    }

    void start() {
        if (scopeUid.isEmpty() || !scopeUid.equals(AccountDataScope.uid())) {
            reportState(false, false); return;
        }
        int requestGeneration = ++generation;
        FamilySyncOutbox.addListener(acknowledged);
        authListener = auth -> {
            if (!scopeUid.equals(AccountDataScope.uid())) stop();
        };
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(authListener);
        reportState(false, true);
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(
                            FamilyAccountRepository.SessionState state) {
                        if (requestGeneration != generation || !scopeUid.equals(AccountDataScope.uid())) return;
                        if (state == null || !state.isActive() || state.familyId == null) {
                            reportState(false, false); return;
                        }
                        attach(state.familyId);
                    }

                    @Override public void onError(@NonNull Exception error) {
                        if (requestGeneration == generation) reportState(false, false);
                        // Room remains the source of truth while account/session is unavailable.
                    }
                });
    }

    void stop() {
        generation++;
        FamilySyncOutbox.removeListener(acknowledged);
        if (authListener != null) com.google.firebase.auth.FirebaseAuth.getInstance().removeAuthStateListener(authListener);
        authListener = null;
        stopListenerOnly();
        reportState(false, false);
    }

    private void attach(@NonNull String familyId) {
        stopListenerOnly();
        listeningFamily = familyId;
        reference = FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(familyId).child(module);
        reference.keepSynced(true);
        final int request = generation;
        listener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot,
                                               @Nullable String previousChildName) {
                if (!current(request) || FamilySyncOutbox.pendingDelete(scopeUid,module,snapshot.getKey() == null ? "" : snapshot.getKey())) return;
                if (snapshot.getKey() != null) remoteIds.add(snapshot.getKey());
                callback.onChanged(familyId, snapshot);
            }

            @Override public void onChildChanged(@NonNull DataSnapshot snapshot,
                                                 @Nullable String previousChildName) {
                if (!current(request) || FamilySyncOutbox.pendingDelete(scopeUid,module,snapshot.getKey() == null ? "" : snapshot.getKey())) return;
                if (snapshot.getKey() != null) remoteIds.add(snapshot.getKey());
                callback.onChanged(familyId, snapshot);
            }

            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                if (!current(request)) return;
                if (snapshot.getKey() != null) remoteIds.remove(snapshot.getKey());
                if (FamilySyncOutbox.pending(scopeUid,module,snapshot.getKey() == null ? "" : snapshot.getKey())) return;
                callback.onRemoved(familyId, snapshot.getKey() == null
                        ? "" : snapshot.getKey());
            }

            @Override public void onChildMoved(@NonNull DataSnapshot snapshot,
                                               @Nullable String previousChildName) { }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (request == generation && reference != null) {
                    cancelled = true; reportState(false, false);
                }
                // Cached Room data stays available; Firebase will reconnect automatically.
            }
        };
        reference.addChildEventListener(listener);
        snapshotListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (current(request) && connected) reconcile(snapshot);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        reference.addValueEventListener(snapshotListener);
        observeConnection();
    }

    private void reconcile(DataSnapshot snapshot) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (DataSnapshot child : snapshot.getChildren()) if (child.getKey() != null) ids.add(child.getKey());
        remoteIds.retainAll(ids); remoteIds.addAll(ids);
        callback.onSnapshot(listeningFamily,ids);
    }

    private void reportState(boolean live, boolean connecting) {
        if (syncStatus != null) syncStatus.onStateChanged(live, connecting);
    }

    private boolean current(int request) {
        return request == generation && scopeUid.equals(AccountDataScope.uid());
    }

    /** Read-only status observers, enabled only for Notes. Existing module subscriptions stay unchanged. */
    private void observeConnection() {
        final int request = generation;
        connected = false; ready = false; cancelled = false;
        connectionReference = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (request != generation || reference == null) return;
                connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                if (connected) reference.get().addOnSuccessListener(full -> {
                    if (current(request) && connected && reference != null) reconcile(full);
                });
                reportState(connected && ready && !cancelled, connected && !ready && !cancelled);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (request == generation) { connected = false; reportState(false, false); }
            }
        };
        readyListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (request != generation || reference == null) return;
                ready = true;
                reportState(connected && !cancelled, false);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (request == generation) { cancelled = true; reportState(false, false); }
            }
        };
        connectionReference.addValueEventListener(connectionListener);
        reference.addListenerForSingleValueEvent(readyListener);
    }

    private void stopListenerOnly() {
        if (connectionReference != null && connectionListener != null)
            connectionReference.removeEventListener(connectionListener);
        if (reference != null && readyListener != null) reference.removeEventListener(readyListener);
        connectionReference = null; connectionListener = null; readyListener = null;
        connected = false; ready = false; cancelled = false;
        if (reference != null && listener != null) {
            reference.removeEventListener(listener);
        }
        if (reference != null && snapshotListener != null) reference.removeEventListener(snapshotListener);
        snapshotListener = null; listeningFamily = "";
        remoteIds.clear();
        reference = null;
        listener = null;
    }
}
