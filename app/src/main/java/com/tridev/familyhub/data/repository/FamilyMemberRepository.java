package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-first Room repository with authorised family-scoped Firebase sync. */
public class FamilyMemberRepository {

    public interface MembersCallback {
        void onMembersLoaded(List<FamilyMember> members);
    }

    public interface ActionCallback {
        void onComplete();
    }

    public interface UniquenessCallback {
        void onChecked(boolean phoneAvailable, boolean emailAvailable);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final FamilyMemberDao memberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FamilyAccountRepository accountRepository =
            new FamilyAccountRepository();
    private final DatabaseReference firebaseRoot =
            FirebaseDatabase.getInstance().getReference();

    public FamilyMemberRepository(Context context) {
        memberDao = FamilyHubDatabase.getInstance(context).familyMemberDao();
    }

    public void loadMembers(
            @NonNull String searchQuery,
            @NonNull MembersCallback callback
    ) {
        accountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState session
                    ) {
                        if (session == null
                                || !session.isActive()
                                || session.familyId == null) {
                            loadLegacyMembers(searchQuery, callback);
                            return;
                        }
                        syncAndLoad(
                                session.familyId,
                                searchQuery,
                                callback
                        );
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        loadLegacyMembers(searchQuery, callback);
                    }
                }
        );
    }

    private void syncAndLoad(
            @NonNull String familyId,
            @NonNull String searchQuery,
            @NonNull MembersCallback callback
    ) {
        DatabaseReference profiles = firebaseRoot
                .child("familyProfiles")
                .child(familyId);
        profiles.get()
                .addOnSuccessListener(snapshot -> {
                    List<FamilyMember> remoteMembers =
                            parseRemoteMembers(snapshot, familyId);
                    DATABASE_EXECUTOR.execute(() -> {
                        long now = System.currentTimeMillis();
                        for (FamilyMember legacy : memberDao.getUnscoped()) {
                            legacy.ownerFamilyId = familyId;
                            if (legacy.cloudProfileId.isEmpty()) {
                                String key = profiles.push().getKey();
                                if (key != null) {
                                    legacy.cloudProfileId = key;
                                }
                            }
                            legacy.updatedAt = legacy.updatedAt > 0L
                                    ? legacy.updatedAt
                                    : Math.max(legacy.createdAt, now);
                            memberDao.update(legacy);
                        }

                        Map<String, Long> remoteUpdatedAt = new HashMap<>();
                        for (FamilyMember remote : remoteMembers) {
                            remoteUpdatedAt.put(
                                    remote.cloudProfileId,
                                    remote.updatedAt
                            );
                            FamilyMember local = memberDao.getByCloudProfileId(
                                    remote.cloudProfileId
                            );
                            if (local == null) {
                                memberDao.insert(remote);
                            } else if (!local.syncPending
                                    && remote.updatedAt > local.updatedAt) {
                                long localId = local.id;
                                String localPhoto = local.profilePhotoUri;
                                copyProfile(remote, local);
                                local.id = localId;
                                local.profilePhotoUri = localPhoto;
                                memberDao.update(local);
                            }
                        }

                        List<FamilyMember> current =
                                memberDao.getForFamily(familyId);
                        List<FamilyMember> pendingUploads = new ArrayList<>();
                        for (FamilyMember local : current) {
                            if (local.cloudProfileId.isEmpty()) {
                                String key = profiles.push().getKey();
                                if (key == null) {
                                    continue;
                                }
                                local.cloudProfileId = key;
                                local.updatedAt = Math.max(
                                        local.updatedAt,
                                        now
                                );
                                memberDao.update(local);
                            }
                            Long cloudTimestamp = remoteUpdatedAt.get(
                                    local.cloudProfileId
                            );
                            if (local.syncPending) {
                                pendingUploads.add(local);
                            }
                        }

                        List<FamilyMember> visible =
                                queryFamily(familyId, searchQuery);
                        mainHandler.post(() -> {
                            uploadProfiles(familyId, pendingUploads);
                            callback.onMembersLoaded(visible);
                        });
                    });
                })
                .addOnFailureListener(error ->
                        loadScopedMembers(familyId, searchQuery, callback));
    }

    public void save(
            @NonNull FamilyMember member,
            @NonNull ActionCallback callback
    ) {
        accountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState session
                    ) {
                        String familyId = session != null
                                && session.isActive()
                                ? session.familyId
                                : null;
                        saveLocalAndCloud(member, familyId, callback);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        saveLocalAndCloud(member, null, callback);
                    }
                }
        );
    }

    private void saveLocalAndCloud(
            @NonNull FamilyMember member,
            @Nullable String familyId,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            if (member.createdAt == 0L) {
                member.createdAt = now;
            }
            member.updatedAt = now;
            member.syncPending = true;
            if (familyId != null) {
                member.ownerFamilyId = familyId;
                if (member.cloudProfileId.isEmpty()) {
                    String key = firebaseRoot
                            .child("familyProfiles")
                            .child(familyId)
                            .push()
                            .getKey();
                    if (key != null) {
                        member.cloudProfileId = key;
                    }
                }
            }

            if (member.id == 0) {
                member.id = memberDao.insert(member);
            } else {
                memberDao.update(member);
            }

            mainHandler.post(() -> {
                if (familyId != null
                        && !member.cloudProfileId.isEmpty()) {
                    firebaseRoot.child("familyProfiles")
                            .child(familyId)
                            .child(member.cloudProfileId)
                            .updateChildren(toCloudValues(
                                    familyId,
                                    member
                            ))
                            .addOnSuccessListener(unused ->
                                    markSynced(member.cloudProfileId));
                }
                callback.onComplete();
            });
        });
    }

    public void delete(
            @NonNull FamilyMember member,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            memberDao.delete(member);
            mainHandler.post(() -> {
                if (!member.ownerFamilyId.isEmpty()
                        && !member.cloudProfileId.isEmpty()) {
                    firebaseRoot.child("familyProfiles")
                            .child(member.ownerFamilyId)
                            .child(member.cloudProfileId)
                            .removeValue();
                }
                callback.onComplete();
            });
        });
    }

    public void checkUniqueContact(
            long memberId,
            @NonNull String phone,
            @NonNull String email,
            @NonNull UniquenessCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean phoneAvailable = phone.trim().isEmpty()
                    || memberDao.countOtherMembersWithPhone(
                    phone.trim(), memberId
            ) == 0;
            boolean emailAvailable = email.trim().isEmpty()
                    || memberDao.countOtherMembersWithEmail(
                    email.trim(), memberId
            ) == 0;
            mainHandler.post(() -> callback.onChecked(
                    phoneAvailable,
                    emailAvailable
            ));
        });
    }

    private void uploadProfiles(
            @NonNull String familyId,
            @NonNull List<FamilyMember> members
    ) {
        for (FamilyMember member : members) {
            firebaseRoot.child("familyProfiles")
                    .child(familyId)
                    .child(member.cloudProfileId)
                    .updateChildren(toCloudValues(familyId, member))
                    .addOnSuccessListener(unused ->
                            markSynced(member.cloudProfileId));
        }
    }

    @NonNull
    private Map<String, Object> toCloudValues(
            @NonNull String familyId,
            @NonNull FamilyMember member
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("profileId", member.cloudProfileId);
        values.put("familyId", familyId);
        values.put("name", member.name);
        values.put("relation", member.relation);
        values.put("phone", member.phone);
        values.put("email", member.email);
        values.put("dateOfBirth", member.dateOfBirth);
        values.put("note", member.note);
        values.put("gender", member.gender);
        values.put("bloodGroup", member.bloodGroup);
        values.put("address", member.address);
        values.put("emergencyContactName", member.emergencyContactName);
        values.put("emergencyContactPhone", member.emergencyContactPhone);
        values.put("familyRole", member.familyRole);
        values.put("isGuardian", member.isGuardian);
        values.put("createdAt", member.createdAt);
        values.put("updatedAt", member.updatedAt);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        values.put("updatedBy", user == null ? "" : user.getUid());
        return values;
    }

    @NonNull
    private List<FamilyMember> parseRemoteMembers(
            @NonNull DataSnapshot snapshot,
            @NonNull String familyId
    ) {
        List<FamilyMember> members = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            String profileId = stringValue(child.child("profileId"));
            String remoteFamilyId = stringValue(child.child("familyId"));
            String name = stringValue(child.child("name"));
            if (profileId.isEmpty()
                    || !familyId.equals(remoteFamilyId)
                    || name.isEmpty()) {
                continue;
            }

            FamilyMember member = new FamilyMember();
            member.cloudProfileId = profileId;
            member.ownerFamilyId = familyId;
            member.name = name;
            member.relation = stringValue(child.child("relation"));
            member.phone = stringValue(child.child("phone"));
            member.email = stringValue(child.child("email"));
            member.dateOfBirth = stringValue(child.child("dateOfBirth"));
            member.note = stringValue(child.child("note"));
            member.gender = stringValue(child.child("gender"));
            member.bloodGroup = stringValue(child.child("bloodGroup"));
            member.address = stringValue(child.child("address"));
            member.emergencyContactName = stringValue(
                    child.child("emergencyContactName")
            );
            member.emergencyContactPhone = stringValue(
                    child.child("emergencyContactPhone")
            );
            member.familyRole = stringValue(child.child("familyRole"));
            if (member.familyRole.isEmpty()) {
                member.familyRole = FamilyMember.ROLE_ADULT;
            }
            member.isGuardian = Boolean.TRUE.equals(
                    child.child("isGuardian").getValue(Boolean.class)
            );
            Long createdAt = child.child("createdAt").getValue(Long.class);
            Long updatedAt = child.child("updatedAt").getValue(Long.class);
            member.createdAt = createdAt == null ? 0L : createdAt;
            member.updatedAt = updatedAt == null ? 0L : updatedAt;
            member.syncPending = false;
            members.add(member);
        }
        return members;
    }

    private void copyProfile(
            @NonNull FamilyMember source,
            @NonNull FamilyMember target
    ) {
        target.cloudProfileId = source.cloudProfileId;
        target.ownerFamilyId = source.ownerFamilyId;
        target.name = source.name;
        target.relation = source.relation;
        target.phone = source.phone;
        target.email = source.email;
        target.dateOfBirth = source.dateOfBirth;
        target.note = source.note;
        target.gender = source.gender;
        target.bloodGroup = source.bloodGroup;
        target.address = source.address;
        target.emergencyContactName = source.emergencyContactName;
        target.emergencyContactPhone = source.emergencyContactPhone;
        target.familyRole = source.familyRole;
        target.isGuardian = source.isGuardian;
        target.createdAt = source.createdAt;
        target.updatedAt = source.updatedAt;
        target.syncPending = false;
    }

    private void markSynced(@NonNull String cloudProfileId) {
        DATABASE_EXECUTOR.execute(() ->
                memberDao.markSynced(cloudProfileId));
    }

    private void loadLegacyMembers(
            @NonNull String searchQuery,
            @NonNull MembersCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = searchQuery.trim().isEmpty()
                    ? memberDao.getAll()
                    : memberDao.search(searchQuery.trim());
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
    }

    private void loadScopedMembers(
            @NonNull String familyId,
            @NonNull String searchQuery,
            @NonNull MembersCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members =
                    queryFamily(familyId, searchQuery);
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
    }

    @NonNull
    private List<FamilyMember> queryFamily(
            @NonNull String familyId,
            @NonNull String searchQuery
    ) {
        String query = searchQuery.trim();
        return query.isEmpty()
                ? memberDao.getForFamily(familyId)
                : memberDao.searchForFamily(familyId, query);
    }

    @NonNull
    private String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }
}
