package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.HealthRecordDao;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.HealthRecordWithMember;
import com.tridev.familyhub.data.model.FamilyRoles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for private, member-linked health records. */
public class HealthRepository {

    public interface RecordsCallback {
        void onRecordsLoaded(@NonNull List<HealthRecordWithMember> records);
    }

    public interface MembersCallback {
        void onMembersLoaded(@NonNull List<FamilyMember> members);
    }

    public interface DocumentsCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntry> documents);
    }

    public interface ActionCallback {
        void onComplete();
    }

    public interface RealtimeCallback {
        void onChanged(@NonNull HealthRecord record);
        void onRemoved(long localId);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final HealthRecordDao healthRecordDao;
    private final FamilyMemberDao familyMemberDao;
    private final DocumentDao documentDao;
    private final FamilyAccountRepository familyAccountRepository =
            new FamilyAccountRepository();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public HealthRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        healthRecordDao = database.healthRecordDao();
        familyMemberDao = database.familyMemberDao();
        documentDao = database.documentDao();
    }

    public void loadDocuments(@NonNull DocumentsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<DocumentEntry> documents = documentDao.getAll();
            mainHandler.post(() -> callback.onDocumentsLoaded(documents));
        });
    }

    public void startRealtimeSync(@NonNull RealtimeCallback callback) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("health",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, callback);
                    }

                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            HealthRecord local = healthRecordDao.getByCloudId(cloudId);
                            if (local == null || !local.isShared) return;
                            long localId = local.id;
                            healthRecordDao.delete(local);
                            mainHandler.post(() -> callback.onRemoved(localId));
                        });
                    }
                });
        subscriber.start();
    }

    public void stopRealtimeSync() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
    }

    public void loadRecords(
            @NonNull String query,
            @NonNull RecordsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<HealthRecordWithMember> records = trimmedQuery.isEmpty()
                    ? healthRecordDao.getAllWithMember()
                    : healthRecordDao.searchWithMember(trimmedQuery);
            mainHandler.post(() -> callback.onRecordsLoaded(records));
        });
    }

    public void loadMembers(@NonNull MembersCallback callback) {
        familyAccountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<
                        FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            FamilyAccountRepository.SessionState session
                    ) {
                        if (session == null
                                || !session.isActive()
                                || session.familyId == null) {
                            loadLocalMembers(callback);
                            return;
                        }
                        familyAccountRepository.loadAuthorisedMembers(
                                new FamilyAccountRepository.ResultCallback<
                                        List<FamilyAccountRepository.Member>>() {
                                    @Override
                                    public void onSuccess(
                                            List<FamilyAccountRepository.Member>
                                                    cloudMembers
                                    ) {
                                        syncAuthorisedMembers(
                                                session.familyId,
                                                cloudMembers == null
                                                        ? new ArrayList<>()
                                                        : cloudMembers,
                                                callback
                                        );
                                    }

                                    @Override
                                    public void onError(
                                            @NonNull Exception error
                                    ) {
                                        loadLocalMembers(callback);
                                    }
                                }
                        );
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        loadLocalMembers(callback);
                    }
                }
        );
    }

    private void syncAuthorisedMembers(
            @NonNull String familyId,
            @NonNull List<FamilyAccountRepository.Member> cloudMembers,
            @NonNull MembersCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> localMembers = familyMemberDao.getAll();
            long now = System.currentTimeMillis();

            for (FamilyAccountRepository.Member cloud : cloudMembers) {
                FamilyMember matched = null;
                String cloudEmail = cloud.email.trim();

                for (FamilyMember local : localMembers) {
                    if ((!cloudEmail.isEmpty()
                            && cloudEmail.equalsIgnoreCase(
                                    local.email.trim()
                            ))
                            || ("account_" + cloud.uid).equals(
                                    local.cloudProfileId
                            )) {
                        matched = local;
                        break;
                    }
                }

                if (matched != null) {
                    continue;
                }

                FamilyMember linked = new FamilyMember();
                linked.name = cloud.displayName.trim().isEmpty()
                        ? cloud.email
                        : cloud.displayName;
                if (linked.name.trim().isEmpty()) {
                    linked.name = "Family member";
                }
                linked.email = cloud.email;
                linked.relation = roleLabel(cloud.role);
                linked.familyRole = familyRole(cloud.role);
                linked.isGuardian =
                        FamilyRoles.OWNER_ADMIN.equals(cloud.role)
                                || FamilyRoles.GUARDIAN.equals(cloud.role);
                linked.cloudProfileId = "account_" + cloud.uid;
                linked.ownerFamilyId = familyId;
                linked.createdAt = now;
                linked.updatedAt = now;
                linked.syncPending = false;
                linked.id = familyMemberDao.insert(linked);
                localMembers.add(linked);
            }

            List<FamilyMember> available =
                    familyMemberDao.getForFamily(familyId);
            mainHandler.post(() ->
                    callback.onMembersLoaded(available));
        });
    }

    private void loadLocalMembers(@NonNull MembersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = familyMemberDao.getAll();
            mainHandler.post(() ->
                    callback.onMembersLoaded(members));
        });
    }

    @NonNull
    private String roleLabel(@NonNull String role) {
        if (FamilyRoles.OWNER_ADMIN.equals(role)) {
            return "Owner/Admin";
        }
        if (FamilyRoles.GUARDIAN.equals(role)) {
            return "Guardian";
        }
        if (FamilyRoles.CHILD.equals(role)) {
            return "Child";
        }
        if (FamilyRoles.SENIOR_CITIZEN.equals(role)) {
            return "Senior citizen";
        }
        if (FamilyRoles.GUEST.equals(role)) {
            return "Guest";
        }
        return "Adult member";
    }

    @NonNull
    private String familyRole(@NonNull String role) {
        if (FamilyRoles.OWNER_ADMIN.equals(role)
                || FamilyRoles.GUARDIAN.equals(role)) {
            return FamilyMember.ROLE_GUARDIAN;
        }
        if (FamilyRoles.CHILD.equals(role)) {
            return FamilyMember.ROLE_CHILD;
        }
        return FamilyMember.ROLE_ADULT;
    }

    public void save(
            @NonNull HealthRecord record,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (record.recordedAt == 0L) {
                record.recordedAt = System.currentTimeMillis();
            }
            if (record.createdAt == 0L) {
                record.createdAt = System.currentTimeMillis();
            }
            record.updatedAt = System.currentTimeMillis();

            if (record.id == 0L) {
                record.id = healthRecordDao.insert(record);
            } else {
                healthRecordDao.update(record);
            }
            if (record.isShared) {
                publish(record);
            } else {
                String oldFamilyId = record.familyId;
                String oldCloudId = record.cloudId;
                record.familyId = "";
                record.cloudId = "";
                record.updatedByUid = "";
                healthRecordDao.update(record);
                FamilyCollaborationPublisher.remove("health", oldFamilyId, oldCloudId);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    private void publish(@NonNull HealthRecord record) {
        Map<String, Object> values = new HashMap<>();
        values.put("memberName", record.assignedMemberName);
        values.put("recordType", record.recordType);
        values.put("title", record.title);
        values.put("value", record.value);
        values.put("notes", record.notes);
        values.put("recordedAt", record.recordedAt);
        values.put("linkedDocumentTitle", record.linkedDocumentTitle);
        values.put("timelineNote", record.timelineNote);
        values.put("shared", true);
        values.put("createdAt", record.createdAt);
        FamilyCollaborationPublisher.publish("health", record.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    record.cloudId = cloudId;
                    record.familyId = familyId;
                    record.updatedByUid = uid;
                    healthRecordDao.update(record);
                }));
    }

    private void mergeRemote(@NonNull String familyId,
                             @NonNull DataSnapshot snapshot,
                             @NonNull RealtimeCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(snapshot, "cloudId");
            if (cloudId.isEmpty()) return;
            long remoteUpdatedAt = number(snapshot, "updatedAt");
            HealthRecord record = healthRecordDao.getByCloudId(cloudId);
            if (record != null && record.updatedAt > remoteUpdatedAt) return;
            boolean insert = record == null;
            if (insert) record = new HealthRecord();

            String memberName = text(snapshot, "memberName");
            FamilyMember member = familyMemberDao.getByName(memberName);
            if (member == null) return;

            record.cloudId = cloudId;
            record.familyId = familyId;
            record.familyMemberId = member.id;
            record.assignedMemberName = member.name;
            record.recordType = fallback(text(snapshot, "recordType"), HealthRecord.TYPE_OTHER);
            record.title = text(snapshot, "title");
            record.value = text(snapshot, "value");
            record.notes = text(snapshot, "notes");
            record.recordedAt = number(snapshot, "recordedAt");
            record.linkedDocumentTitle = text(snapshot, "linkedDocumentTitle");
            record.timelineNote = text(snapshot, "timelineNote");
            record.isShared = true;
            record.createdAt = number(snapshot, "createdAt");
            if (record.createdAt == 0L) record.createdAt = remoteUpdatedAt;
            record.updatedAt = remoteUpdatedAt;
            record.updatedByUid = text(snapshot, "updatedByUid");
            if (insert) record.id = healthRecordDao.insert(record);
            else healthRecordDao.update(record);
            HealthRecord changed = record;
            mainHandler.post(() -> callback.onChanged(changed));
        });
    }

    @NonNull private static String text(DataSnapshot snapshot, String key) {
        String value = snapshot.child(key).getValue(String.class);
        return value == null ? "" : value;
    }

    private static long number(DataSnapshot snapshot, String key) {
        Number value = snapshot.child(key).getValue(Number.class);
        return value == null ? 0L : value.longValue();
    }

    @NonNull private static String fallback(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    public void delete(
            @NonNull HealthRecord record,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("health", record.familyId, record.cloudId);
            healthRecordDao.delete(record);
            mainHandler.post(callback::onComplete);
        });
    }
}
