package com.tridev.familyhub.data.repository;

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
    private int generation;

    FamilyCollaborationSubscriber(@NonNull String module,
                                  @NonNull Callback callback) {
        this(module, callback, null);
    }

    FamilyCollaborationSubscriber(@NonNull String module, @NonNull Callback callback,
                                  @Nullable SyncStatusCallback syncStatus) {
        this.syncStatus = syncStatus;
        this.module = module;
        this.callback = callback;
    }

    void start() {
        int requestGeneration = ++generation;
        reportState(false, true);
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(
                            FamilyAccountRepository.SessionState state) {
                        if (requestGeneration != generation) return;
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
        stopListenerOnly();
        reportState(false, false);
    }

    private void attach(@NonNull String familyId) {
        stopListenerOnly();
        reference = FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(familyId).child(module);
        reference.keepSynced(true);
        final int request = generation;
        listener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot,
                                               @Nullable String previousChildName) {
                callback.onChanged(familyId, snapshot);
            }

            @Override public void onChildChanged(@NonNull DataSnapshot snapshot,
                                                 @Nullable String previousChildName) {
                callback.onChanged(familyId, snapshot);
            }

            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {
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
        if (syncStatus != null) observeConnection();
    }

    private void reportState(boolean live, boolean connecting) {
        if (syncStatus != null) syncStatus.onStateChanged(live, connecting);
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
        reference = null;
        listener = null;
    }
}
