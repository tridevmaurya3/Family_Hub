package com.tridev.familyhub.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/** Reusable authenticated, family-scoped realtime listener for shared modules. */
final class FamilyCollaborationSubscriber {

    interface Callback {
        void onChanged(@NonNull String familyId, @NonNull DataSnapshot snapshot);
        void onRemoved(@NonNull String familyId, @NonNull String cloudId);
    }

    @NonNull private final String module;
    @NonNull private final Callback callback;
    @Nullable private DatabaseReference reference;
    @Nullable private ChildEventListener listener;
    private int generation;

    FamilyCollaborationSubscriber(@NonNull String module,
                                  @NonNull Callback callback) {
        this.module = module;
        this.callback = callback;
    }

    void start() {
        int requestGeneration = ++generation;
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(
                            FamilyAccountRepository.SessionState state) {
                        if (requestGeneration != generation || state == null
                                || !state.isActive() || state.familyId == null) return;
                        attach(state.familyId);
                    }

                    @Override public void onError(@NonNull Exception error) {
                        // Room remains the source of truth while account/session is unavailable.
                    }
                });
    }

    void stop() {
        generation++;
        if (reference != null && listener != null) {
            reference.removeEventListener(listener);
        }
        reference = null;
        listener = null;
    }

    private void attach(@NonNull String familyId) {
        stopListenerOnly();
        reference = FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(familyId).child(module);
        reference.keepSynced(true);
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
                // Cached Room data stays available; Firebase will reconnect automatically.
            }
        };
        reference.addChildEventListener(listener);
    }

    private void stopListenerOnly() {
        if (reference != null && listener != null) {
            reference.removeEventListener(listener);
        }
        reference = null;
        listener = null;
    }
}
