package com.tridev.familyhub.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tridev.familyhub.data.model.FamilyRoles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Secure Firebase gateway for households inside one authorised family. */
public class HouseholdRepository {

    private static final String PRIMARY_HOUSEHOLD_ID = "primary";
    private final DatabaseReference root =
            FirebaseDatabase.getInstance().getReference();

    public void load(
            @NonNull String familyId,
            @NonNull FamilyAccountRepository.ResultCallback<Data> callback
    ) {
        ensurePrimaryHousehold(familyId, new FamilyAccountRepository.ResultCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                loadSnapshots(familyId, callback);
            }

            @Override
            public void onError(@NonNull Exception error) {
                callback.onError(error);
            }
        });
    }

    private void loadSnapshots(
            @NonNull String familyId,
            @NonNull FamilyAccountRepository.ResultCallback<Data> callback
    ) {
        root.child("households").child(familyId).get()
                .addOnSuccessListener(households -> root
                        .child("memberships")
                        .child(familyId)
                        .get()
                        .addOnSuccessListener(members -> root
                                .child("householdAssignments")
                                .child(familyId)
                                .get()
                                .addOnSuccessListener(assignments ->
                                        callback.onSuccess(parse(
                                                households,
                                                members,
                                                assignments
                                        )))
                                .addOnFailureListener(callback::onError))
                        .addOnFailureListener(callback::onError))
                .addOnFailureListener(callback::onError);
    }

    private void ensurePrimaryHousehold(
            @NonNull String familyId,
            @NonNull FamilyAccountRepository.ResultCallback<Void> callback
    ) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        DatabaseReference primary = root.child("households")
                .child(familyId)
                .child(PRIMARY_HOUSEHOLD_ID);
        primary.get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        callback.onSuccess(null);
                        return;
                    }
                    Map<String, Object> updates = new HashMap<>();
                    String base = "households/" + familyId
                            + "/" + PRIMARY_HOUSEHOLD_ID;
                    updates.put(base + "/householdId", PRIMARY_HOUSEHOLD_ID);
                    updates.put(base + "/familyId", familyId);
                    updates.put(base + "/name", "Primary household");
                    updates.put(base + "/guardianUid", user.getUid());
                    updates.put(base + "/active", true);
                    updates.put(base + "/createdBy", user.getUid());
                    updates.put(base + "/createdAt", ServerValue.TIMESTAMP);
                    putAssignment(
                            updates,
                            familyId,
                            user.getUid(),
                            PRIMARY_HOUSEHOLD_ID,
                            user.getUid()
                    );
                    root.updateChildren(updates)
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(null))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    public void create(
            @NonNull String familyId,
            @NonNull String name,
            @NonNull String guardianUid,
            @NonNull FamilyAccountRepository.ResultCallback<Void> callback
    ) {
        FirebaseUser owner = FirebaseAuth.getInstance().getCurrentUser();
        if (owner == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }
        String householdId = root.child("households")
                .child(familyId)
                .push()
                .getKey();
        if (householdId == null) {
            callback.onError(new IllegalStateException("HOUSEHOLD_ID_REQUIRED"));
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        String base = "households/" + familyId + "/" + householdId;
        updates.put(base + "/householdId", householdId);
        updates.put(base + "/familyId", familyId);
        updates.put(base + "/name", name.trim());
        updates.put(base + "/guardianUid", guardianUid);
        updates.put(base + "/active", true);
        updates.put(base + "/createdBy", owner.getUid());
        updates.put(base + "/createdAt", ServerValue.TIMESTAMP);
        putAssignment(
                updates,
                familyId,
                guardianUid,
                householdId,
                owner.getUid()
        );
        if (!guardianUid.equals(owner.getUid())) {
            updates.put(
                    "memberships/" + familyId + "/"
                            + guardianUid + "/role",
                    FamilyRoles.GUARDIAN
            );
            updates.put(
                    "users/" + guardianUid + "/role",
                    FamilyRoles.GUARDIAN
            );
        }

        root.updateChildren(updates)
                .addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(callback::onError);
    }

    public void changeGuardian(
            @NonNull String familyId,
            @NonNull String householdId,
            @NonNull String guardianUid,
            @NonNull FamilyAccountRepository.ResultCallback<Void> callback
    ) {
        FirebaseUser owner = FirebaseAuth.getInstance().getCurrentUser();
        if (owner == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(
                "households/" + familyId + "/"
                        + householdId + "/guardianUid",
                guardianUid
        );
        putAssignment(
                updates,
                familyId,
                guardianUid,
                householdId,
                owner.getUid()
        );
        if (!guardianUid.equals(owner.getUid())) {
            updates.put(
                    "memberships/" + familyId + "/"
                            + guardianUid + "/role",
                    FamilyRoles.GUARDIAN
            );
            updates.put(
                    "users/" + guardianUid + "/role",
                    FamilyRoles.GUARDIAN
            );
        }
        root.updateChildren(updates)
                .addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(callback::onError);
    }

    public void assignMembers(
            @NonNull String familyId,
            @NonNull String householdId,
            @NonNull List<String> selectedUids,
            @NonNull FamilyAccountRepository.ResultCallback<Void> callback
    ) {
        FirebaseUser owner = FirebaseAuth.getInstance().getCurrentUser();
        if (owner == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }
        root.child("householdAssignments").child(familyId).get()
                .addOnSuccessListener(snapshot -> {
                    Map<String, Object> updates = new HashMap<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        String uid = text(child.child("uid"));
                        String assignedHousehold =
                                text(child.child("householdId"));
                        if (householdId.equals(assignedHousehold)
                                && !selectedUids.contains(uid)) {
                            updates.put(
                                    "householdAssignments/" + familyId
                                            + "/" + uid,
                                    null
                            );
                        }
                    }
                    for (String uid : selectedUids) {
                        putAssignment(
                                updates,
                                familyId,
                                uid,
                                householdId,
                                owner.getUid()
                        );
                    }
                    if (updates.isEmpty()) {
                        callback.onSuccess(null);
                        return;
                    }
                    root.updateChildren(updates)
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(null))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    private void putAssignment(
            @NonNull Map<String, Object> updates,
            @NonNull String familyId,
            @NonNull String uid,
            @NonNull String householdId,
            @NonNull String assignedBy
    ) {
        String path = "householdAssignments/" + familyId + "/" + uid;
        updates.put(path + "/uid", uid);
        updates.put(path + "/familyId", familyId);
        updates.put(path + "/householdId", householdId);
        updates.put(path + "/assignedBy", assignedBy);
        updates.put(path + "/updatedAt", ServerValue.TIMESTAMP);
    }

    @NonNull
    private Data parse(
            @NonNull DataSnapshot householdSnapshot,
            @NonNull DataSnapshot memberSnapshot,
            @NonNull DataSnapshot assignmentSnapshot
    ) {
        List<Member> members = new ArrayList<>();
        Map<String, Member> memberByUid = new HashMap<>();
        for (DataSnapshot child : memberSnapshot.getChildren()) {
            String status = text(child.child("status"));
            String uid = text(child.child("uid"));
            if (!"ACTIVE".equals(status) || uid.isEmpty()) {
                continue;
            }
            Member member = new Member(
                    uid,
                    text(child.child("displayName")),
                    text(child.child("email")),
                    text(child.child("role"))
            );
            members.add(member);
            memberByUid.put(uid, member);
        }

        Map<String, String> assignmentByUid = new HashMap<>();
        for (DataSnapshot child : assignmentSnapshot.getChildren()) {
            String uid = text(child.child("uid"));
            String householdId = text(child.child("householdId"));
            if (!uid.isEmpty() && !householdId.isEmpty()) {
                assignmentByUid.put(uid, householdId);
            }
        }

        List<Household> households = new ArrayList<>();
        for (DataSnapshot child : householdSnapshot.getChildren()) {
            if (!Boolean.TRUE.equals(
                    child.child("active").getValue(Boolean.class)
            )) {
                continue;
            }
            String householdId = text(child.child("householdId"));
            String guardianUid = text(child.child("guardianUid"));
            List<String> assigned = new ArrayList<>();
            for (Map.Entry<String, String> entry
                    : assignmentByUid.entrySet()) {
                if (householdId.equals(entry.getValue())) {
                    assigned.add(entry.getKey());
                }
            }
            Member guardian = memberByUid.get(guardianUid);
            households.add(new Household(
                    householdId,
                    text(child.child("name")),
                    guardianUid,
                    guardian == null ? "" : guardian.displayName,
                    assigned
            ));
        }
        return new Data(households, members, assignmentByUid);
    }

    @NonNull
    private String text(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    public static final class Data {
        @NonNull public final List<Household> households;
        @NonNull public final List<Member> members;
        @NonNull public final Map<String, String> assignmentByUid;

        Data(
                @NonNull List<Household> households,
                @NonNull List<Member> members,
                @NonNull Map<String, String> assignmentByUid
        ) {
            this.households = Collections.unmodifiableList(households);
            this.members = Collections.unmodifiableList(members);
            this.assignmentByUid = Collections.unmodifiableMap(
                    assignmentByUid
            );
        }
    }

    public static final class Household {
        @NonNull public final String householdId;
        @NonNull public final String name;
        @NonNull public final String guardianUid;
        @NonNull public final String guardianName;
        @NonNull public final List<String> assignedUids;

        Household(
                @NonNull String householdId,
                @NonNull String name,
                @NonNull String guardianUid,
                @NonNull String guardianName,
                @NonNull List<String> assignedUids
        ) {
            this.householdId = householdId;
            this.name = name;
            this.guardianUid = guardianUid;
            this.guardianName = guardianName;
            this.assignedUids = Collections.unmodifiableList(assignedUids);
        }
    }

    public static final class Member {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String email;
        @NonNull public final String role;

        Member(
                @NonNull String uid,
                @NonNull String displayName,
                @NonNull String email,
                @NonNull String role
        ) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
            this.role = role;
        }
    }
}
