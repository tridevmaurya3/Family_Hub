package com.tridev.familyhub.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.data.model.FamilyRoles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Realtime Database gateway for family onboarding and role administration.
 * Access control is duplicated in firebase/database.rules.json.
 */
public class FamilyAccountRepository {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final long INVITE_VALIDITY_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final char[] INVITE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final DatabaseReference root;
    private final FirebaseAuth auth;
    private final SecureRandom secureRandom = new SecureRandom();

    public FamilyAccountRepository() {
        root = FirebaseDatabase.getInstance().getReference();
        auth = FirebaseAuth.getInstance();
    }

    public void loadSession(@NonNull ResultCallback<SessionState> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        root.child("users").child(user.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    String familyId = stringValue(snapshot.child("familyId"));
                    String pendingFamilyId =
                            stringValue(snapshot.child("pendingFamilyId"));
                    String pendingStatus =
                            stringValue(snapshot.child("pendingStatus"));

                    if (familyId == null || familyId.trim().isEmpty()) {
                        callback.onSuccess(new SessionState(
                                null,
                                null,
                                null,
                                pendingFamilyId,
                                pendingStatus
                        ));
                        return;
                    }

                    root.child("memberships")
                            .child(familyId)
                            .child(user.getUid())
                            .get()
                            .addOnSuccessListener(membership -> {
                                String role = stringValue(
                                        membership.child("role")
                                );
                                String status = stringValue(
                                        membership.child("status")
                                );
                                root.child("families")
                                        .child(familyId)
                                        .child("ownerUid")
                                        .get()
                                        .addOnSuccessListener(ownerSnapshot -> {
                                            String ownerUid =
                                                    stringValue(ownerSnapshot);
                                            if (user.getUid().equals(ownerUid)
                                                    && (!FamilyRoles.OWNER_ADMIN.equals(role)
                                                    || !STATUS_ACTIVE.equals(status))) {
                                                repairOwnerSession(
                                                        user,
                                                        familyId,
                                                        pendingFamilyId,
                                                        pendingStatus,
                                                        callback
                                                );
                                                return;
                                            }
                                            callback.onSuccess(new SessionState(
                                                    familyId,
                                                    role,
                                                    status,
                                                    pendingFamilyId,
                                                    pendingStatus
                                            ));
                                        })
                                        .addOnFailureListener(callback::onError);
                            })
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    private void repairOwnerSession(
            @NonNull FirebaseUser user,
            @NonNull String familyId,
            @Nullable String pendingFamilyId,
            @Nullable String pendingStatus,
            @NonNull ResultCallback<SessionState> callback
    ) {
        Map<String, Object> repairs = new HashMap<>();
        String membershipPath = "memberships/" + familyId
                + "/" + user.getUid();
        String userPath = "users/" + user.getUid();
        repairs.put(membershipPath + "/role", FamilyRoles.OWNER_ADMIN);
        repairs.put(membershipPath + "/status", STATUS_ACTIVE);
        repairs.put(userPath + "/role", FamilyRoles.OWNER_ADMIN);
        repairs.put(userPath + "/status", STATUS_ACTIVE);
        repairs.put(userPath + "/pendingFamilyId", null);
        repairs.put(userPath + "/pendingStatus", null);
        repairs.put("joinRequests/" + familyId
                + "/" + user.getUid(), null);

        root.updateChildren(repairs)
                .addOnSuccessListener(unused ->
                        callback.onSuccess(new SessionState(
                                familyId,
                                FamilyRoles.OWNER_ADMIN,
                                STATUS_ACTIVE,
                                null,
                                null
                        )))
                .addOnFailureListener(callback::onError);
    }

    public void loadAuthorisedMembers(
            @NonNull ResultCallback<List<Member>> callback
    ) {
        loadSession(new ResultCallback<SessionState>() {
            @Override
            public void onSuccess(@Nullable SessionState session) {
                if (session == null
                        || !session.isActive()
                        || session.familyId == null) {
                    callback.onSuccess(Collections.emptyList());
                    return;
                }
                root.child("memberships")
                        .child(session.familyId)
                        .get()
                        .addOnSuccessListener(snapshot ->
                                callback.onSuccess(parseMembers(snapshot)))
                        .addOnFailureListener(callback::onError);
            }

            @Override
            public void onError(@NonNull Exception error) {
                callback.onError(error);
            }
        });
    }

    public void createFamily(
            @NonNull String familyName,
            @NonNull ResultCallback<CreateFamilyResult> callback
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        String familyId = root.child("families").push().getKey();
        if (familyId == null) {
            callback.onError(new IllegalStateException("FAMILY_ID_UNAVAILABLE"));
            return;
        }

        Map<String, Object> family = new HashMap<>();
        family.put("familyId", familyId);
        family.put("name", familyName);
        family.put("ownerUid", user.getUid());
        family.put("active", true);
        family.put("createdAt", ServerValue.TIMESTAMP);

        Map<String, Object> membership =
                membershipFor(user, FamilyRoles.OWNER_ADMIN, STATUS_ACTIVE);

        root.child("families").child(familyId).setValue(family)
                .continueWithTask(task -> {
                    requireSuccess(task);
                    return root.child("memberships")
                            .child(familyId)
                            .child(user.getUid())
                            .setValue(membership);
                })
                .continueWithTask(task -> {
                    requireSuccess(task);
                    return root.child("users")
                            .child(user.getUid())
                            .updateChildren(activeUserValues(
                                    user,
                                    familyId,
                                    FamilyRoles.OWNER_ADMIN
                            ));
                })
                .continueWithTask(task -> {
                    requireSuccess(task);
                    return createInviteTask(familyId, user.getUid());
                })
                .addOnSuccessListener(code ->
                        callback.onSuccess(new CreateFamilyResult(familyId, code)))
                .addOnFailureListener(callback::onError);
    }

    public void requestJoin(
            @NonNull String suppliedCode,
            @NonNull ResultCallback<String> callback
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        final String inviteHash;
        try {
            inviteHash = sha256(normalizeCode(suppliedCode));
        } catch (NoSuchAlgorithmException error) {
            callback.onError(error);
            return;
        }

        root.child("invites").child(inviteHash).get()
                .addOnSuccessListener(snapshot -> {
                    String familyId = stringValue(snapshot.child("familyId"));
                    Boolean active = snapshot.child("active").getValue(Boolean.class);
                    Long expiresAt = snapshot.child("expiresAt").getValue(Long.class);

                    if (!snapshot.exists()
                            || familyId == null
                            || !Boolean.TRUE.equals(active)
                            || expiresAt == null
                            || expiresAt <= System.currentTimeMillis()) {
                        callback.onError(new IllegalArgumentException("INVALID_INVITE"));
                        return;
                    }

                    Map<String, Object> request = new HashMap<>();
                    request.put("uid", user.getUid());
                    request.put("familyId", familyId);
                    request.put("inviteHash", inviteHash);
                    request.put("displayName", safeDisplayName(user));
                    request.put("email", safeEmail(user));
                    request.put("requestedRole", FamilyRoles.ADULT_MEMBER);
                    request.put("status", STATUS_PENDING);
                    request.put("createdAt", ServerValue.TIMESTAMP);

                    root.child("joinRequests")
                            .child(familyId)
                            .child(user.getUid())
                            .setValue(request)
                            .continueWithTask(task -> {
                                requireSuccess(task);
                                Map<String, Object> pending = new HashMap<>();
                                pending.put("uid", user.getUid());
                                pending.put("displayName", safeDisplayName(user));
                                pending.put("email", safeEmail(user));
                                pending.put("pendingFamilyId", familyId);
                                pending.put("pendingStatus", STATUS_PENDING);
                                pending.put("updatedAt", ServerValue.TIMESTAMP);
                                return root.child("users")
                                        .child(user.getUid())
                                        .updateChildren(pending);
                            })
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(familyId))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    public void cancelJoinRequest(
            @NonNull String familyId,
            @NonNull ResultCallback<Void> callback
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        root.child("joinRequests")
                .child(familyId)
                .child(user.getUid())
                .removeValue()
                .continueWithTask(task -> {
                    requireSuccess(task);
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("pendingFamilyId", null);
                    updates.put("pendingStatus", null);
                    updates.put("updatedAt", ServerValue.TIMESTAMP);
                    return root.child("users")
                            .child(user.getUid())
                            .updateChildren(updates);
                })
                .addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(callback::onError);
    }

    public void loadAdminData(
            @NonNull String familyId,
            @NonNull ResultCallback<AdminData> callback
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        root.child("memberships").child(familyId).child(user.getUid()).get()
                .continueWithTask(roleTask -> {
                    requireSuccess(roleTask);
                    DataSnapshot roleSnapshot = roleTask.getResult();
                    String role = stringValue(roleSnapshot.child("role"));
                    if (!FamilyRoles.OWNER_ADMIN.equals(role)) {
                        throw new SecurityException("OWNER_REQUIRED");
                    }
                    return root.child("families").child(familyId).get();
                })
                .continueWithTask(familyTask -> {
                    requireSuccess(familyTask);
                    DataSnapshot familySnapshot = familyTask.getResult();
                    String familyName = stringValue(familySnapshot.child("name"));
                    return root.child("memberships").child(familyId).get()
                            .continueWithTask(memberTask -> {
                                requireSuccess(memberTask);
                                return root.child("joinRequests").child(familyId).get()
                                        .continueWith(requestTask -> {
                                            requireSuccess(requestTask);
                                            return new AdminData(
                                                    familyName,
                                                    parseMembers(memberTask.getResult()),
                                                    parsePending(requestTask.getResult())
                                            );
                                        });
                            });
                })
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(callback::onError);
    }

    public void createInvite(
            @NonNull String familyId,
            @NonNull ResultCallback<String> callback
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        createInviteTask(familyId, user.getUid())
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(callback::onError);
    }

    public void approveRequest(
            @NonNull String familyId,
            @NonNull PendingRequest request,
            @NonNull ResultCallback<Void> callback
    ) {
        if (!FamilyRoles.isAssignable(request.requestedRole)) {
            callback.onError(new IllegalArgumentException("INVALID_ROLE"));
            return;
        }

        root.child("families").child(familyId).child("ownerUid").get()
                .addOnSuccessListener(ownerSnapshot -> {
                    String ownerUid = stringValue(ownerSnapshot);
                    String approvedRole = request.uid.equals(ownerUid)
                            ? FamilyRoles.OWNER_ADMIN
                            : request.requestedRole;

                    Map<String, Object> membership = new HashMap<>();
                    membership.put("uid", request.uid);
                    membership.put("displayName", request.displayName);
                    membership.put("email", request.email);
                    membership.put("role", approvedRole);
                    membership.put("status", STATUS_ACTIVE);
                    membership.put("joinedAt", ServerValue.TIMESTAMP);

                    root.child("memberships")
                            .child(familyId)
                            .child(request.uid)
                            .setValue(membership)
                            .continueWithTask(task -> {
                                requireSuccess(task);
                                Map<String, Object> userValues =
                                        new HashMap<>();
                                userValues.put("familyId", familyId);
                                userValues.put("role", approvedRole);
                                userValues.put("status", STATUS_ACTIVE);
                                userValues.put("pendingFamilyId", null);
                                userValues.put("pendingStatus", null);
                                userValues.put(
                                        "updatedAt",
                                        ServerValue.TIMESTAMP
                                );
                                return root.child("users")
                                        .child(request.uid)
                                        .updateChildren(userValues);
                            })
                            .continueWithTask(task -> {
                                requireSuccess(task);
                                Map<String, Object> requestValues =
                                        new HashMap<>();
                                requestValues.put("status", "APPROVED");
                                requestValues.put(
                                        "resolvedAt",
                                        ServerValue.TIMESTAMP
                                );
                                return root.child("joinRequests")
                                        .child(familyId)
                                        .child(request.uid)
                                        .updateChildren(requestValues);
                            })
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess(null))
                            .addOnFailureListener(callback::onError);
                })
                .addOnFailureListener(callback::onError);
    }

    public void rejectRequest(
            @NonNull String familyId,
            @NonNull PendingRequest request,
            @NonNull ResultCallback<Void> callback
    ) {
        Map<String, Object> requestValues = new HashMap<>();
        requestValues.put("status", STATUS_REJECTED);
        requestValues.put("resolvedAt", ServerValue.TIMESTAMP);

        root.child("joinRequests").child(familyId).child(request.uid)
                .updateChildren(requestValues)
                .continueWithTask(task -> {
                    requireSuccess(task);
                    Map<String, Object> userValues = new HashMap<>();
                    userValues.put("pendingFamilyId", null);
                    userValues.put("pendingStatus", STATUS_REJECTED);
                    userValues.put("updatedAt", ServerValue.TIMESTAMP);
                    return root.child("users").child(request.uid)
                            .updateChildren(userValues);
                })
                .addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(callback::onError);
    }

    public void changeRole(
            @NonNull String familyId,
            @NonNull String targetUid,
            @NonNull String role,
            @NonNull ResultCallback<Void> callback
    ) {
        if (!FamilyRoles.isAssignable(role)) {
            callback.onError(new IllegalArgumentException("INVALID_ROLE"));
            return;
        }

        root.child("memberships").child(familyId).child(targetUid)
                .child("role").setValue(role)
                .continueWithTask(task -> {
                    requireSuccess(task);
                    return root.child("users").child(targetUid)
                            .child("role").setValue(role);
                })
                .addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(callback::onError);
    }

    @NonNull
    private Task<String> createInviteTask(
            @NonNull String familyId,
            @NonNull String creatorUid
    ) {
        String code = generateInviteCode();
        String hash;
        try {
            hash = sha256(code);
        } catch (NoSuchAlgorithmException error) {
            return com.google.android.gms.tasks.Tasks.forException(error);
        }

        Map<String, Object> invite = new HashMap<>();
        invite.put("familyId", familyId);
        invite.put("createdBy", creatorUid);
        invite.put("active", true);
        invite.put("createdAt", ServerValue.TIMESTAMP);
        invite.put("expiresAt", System.currentTimeMillis() + INVITE_VALIDITY_MS);

        return root.child("invites").child(hash).setValue(invite)
                .continueWith(task -> {
                    requireSuccess(task);
                    return code;
                });
    }

    @NonNull
    private String generateInviteCode() {
        StringBuilder code = new StringBuilder(10);
        for (int index = 0; index < 10; index++) {
            code.append(INVITE_ALPHABET[
                    secureRandom.nextInt(INVITE_ALPHABET.length)
            ]);
        }
        return code.toString();
    }

    @NonNull
    private static String normalizeCode(@NonNull String code) {
        return code.replace("-", "")
                .replace(" ", "")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    @NonNull
    private static String sha256(@NonNull String value)
            throws NoSuchAlgorithmException {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            output.append(String.format(Locale.US, "%02x", item));
        }
        return output.toString();
    }

    @NonNull
    private static Map<String, Object> membershipFor(
            @NonNull FirebaseUser user,
            @NonNull String role,
            @NonNull String status
    ) {
        Map<String, Object> membership = new HashMap<>();
        membership.put("uid", user.getUid());
        membership.put("displayName", safeDisplayName(user));
        membership.put("email", safeEmail(user));
        membership.put("role", role);
        membership.put("status", status);
        membership.put("joinedAt", ServerValue.TIMESTAMP);
        return membership;
    }

    @NonNull
    private static Map<String, Object> activeUserValues(
            @NonNull FirebaseUser user,
            @NonNull String familyId,
            @NonNull String role
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("uid", user.getUid());
        values.put("displayName", safeDisplayName(user));
        values.put("email", safeEmail(user));
        values.put("familyId", familyId);
        values.put("role", role);
        values.put("status", STATUS_ACTIVE);
        values.put("pendingFamilyId", null);
        values.put("pendingStatus", null);
        values.put("updatedAt", ServerValue.TIMESTAMP);
        return values;
    }

    @NonNull
    private static String safeDisplayName(@NonNull FirebaseUser user) {
        String displayName = user.getDisplayName();
        return displayName == null || displayName.trim().isEmpty()
                ? "Family member"
                : displayName.trim();
    }

    @NonNull
    private static String safeEmail(@NonNull FirebaseUser user) {
        String email = user.getEmail();
        return email == null ? "" : email;
    }

    @Nullable
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        return snapshot.getValue(String.class);
    }

    private static <T> void requireSuccess(@NonNull Task<T> task)
            throws Exception {
        if (!task.isSuccessful()) {
            Exception error = task.getException();
            throw error != null ? error : new IllegalStateException();
        }
    }

    @NonNull
    private static List<Member> parseMembers(@NonNull DataSnapshot snapshot) {
        List<Member> members = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            String uid = stringValue(child.child("uid"));
            String role = stringValue(child.child("role"));
            String status = stringValue(child.child("status"));
            if (uid != null && role != null && STATUS_ACTIVE.equals(status)) {
                members.add(new Member(
                        uid,
                        valueOrEmpty(child.child("displayName")),
                        valueOrEmpty(child.child("email")),
                        role
                ));
            }
        }
        return members;
    }

    @NonNull
    private static List<PendingRequest> parsePending(
            @NonNull DataSnapshot snapshot
    ) {
        List<PendingRequest> requests = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            String status = stringValue(child.child("status"));
            String uid = stringValue(child.child("uid"));
            String role = stringValue(child.child("requestedRole"));
            if (STATUS_PENDING.equals(status)
                    && uid != null
                    && role != null
                    && FamilyRoles.isAssignable(role)) {
                requests.add(new PendingRequest(
                        uid,
                        valueOrEmpty(child.child("displayName")),
                        valueOrEmpty(child.child("email")),
                        role
                ));
            }
        }
        return requests;
    }

    @NonNull
    private static String valueOrEmpty(@NonNull DataSnapshot snapshot) {
        String value = stringValue(snapshot);
        return value == null ? "" : value;
    }

    public interface ResultCallback<T> {
        void onSuccess(@Nullable T result);

        void onError(@NonNull Exception error);
    }

    public static final class SessionState {
        @Nullable public final String familyId;
        @Nullable public final String role;
        @Nullable public final String status;
        @Nullable public final String pendingFamilyId;
        @Nullable public final String pendingStatus;

        public SessionState(
                @Nullable String familyId,
                @Nullable String role,
                @Nullable String status,
                @Nullable String pendingFamilyId,
                @Nullable String pendingStatus
        ) {
            this.familyId = familyId;
            this.role = role;
            this.status = status;
            this.pendingFamilyId = pendingFamilyId;
            this.pendingStatus = pendingStatus;
        }

        public boolean isActive() {
            return familyId != null && STATUS_ACTIVE.equals(status);
        }

        public boolean isPending() {
            return pendingFamilyId != null && STATUS_PENDING.equals(pendingStatus);
        }
    }

    public static final class CreateFamilyResult {
        @NonNull public final String familyId;
        @NonNull public final String inviteCode;

        public CreateFamilyResult(
                @NonNull String familyId,
                @NonNull String inviteCode
        ) {
            this.familyId = familyId;
            this.inviteCode = inviteCode;
        }
    }

    public static final class Member {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String email;
        @NonNull public final String role;

        public Member(
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

    public static final class PendingRequest {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String email;
        @NonNull public final String requestedRole;

        public PendingRequest(
                @NonNull String uid,
                @NonNull String displayName,
                @NonNull String email,
                @NonNull String requestedRole
        ) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
            this.requestedRole = requestedRole;
        }
    }

    public static final class AdminData {
        @Nullable public final String familyName;
        @NonNull public final List<Member> members;
        @NonNull public final List<PendingRequest> pendingRequests;

        public AdminData(
                @Nullable String familyName,
                @NonNull List<Member> members,
                @NonNull List<PendingRequest> pendingRequests
        ) {
            this.familyName = familyName;
            this.members = Collections.unmodifiableList(members);
            this.pendingRequests = Collections.unmodifiableList(pendingRequests);
        }
    }
}
