package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.HealthRecordDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.HealthRecordWithMember;

import java.util.List;
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

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final HealthRecordDao healthRecordDao;
    private final FamilyMemberDao familyMemberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public HealthRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        healthRecordDao = database.healthRecordDao();
        familyMemberDao = database.familyMemberDao();
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
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = familyMemberDao.getAll();
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
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

            if (record.id == 0L) {
                record.id = healthRecordDao.insert(record);
            } else {
                healthRecordDao.update(record);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(
            @NonNull HealthRecord record,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            healthRecordDao.delete(record);
            mainHandler.post(callback::onComplete);
        });
    }
}
