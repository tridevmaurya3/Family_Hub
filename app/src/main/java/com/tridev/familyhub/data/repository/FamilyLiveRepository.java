package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyLiveStatusDao;
import com.tridev.familyhub.data.local.entity.FamilyLiveStatus;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository boundary for the local Family Live status feature.
 *
 * All Room operations are performed away from the main UI thread.
 */
public class FamilyLiveRepository {

    public interface StatusListCallback {
        void onStatusListLoaded(@NonNull List<FamilyLiveStatus> statusList);
    }

    public interface StatusCallback {
        void onStatusLoaded(@Nullable FamilyLiveStatus status);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final FamilyLiveStatusDao familyLiveStatusDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FamilyLiveRepository(@NonNull Context context) {
        familyLiveStatusDao = FamilyHubDatabase
                .getInstance(context)
                .familyLiveStatusDao();
    }

    public void loadAll(@NonNull StatusListCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyLiveStatus> statusList = familyLiveStatusDao.getAll();
            mainHandler.post(() -> callback.onStatusListLoaded(statusList));
        });
    }

    public void loadSharingEnabled(@NonNull StatusListCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyLiveStatus> statusList =
                    familyLiveStatusDao.getSharingEnabled();

            mainHandler.post(() -> callback.onStatusListLoaded(statusList));
        });
    }

    public void loadByMemberId(
            long familyMemberId,
            @NonNull StatusCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyLiveStatus status =
                    familyLiveStatusDao.getByMemberId(familyMemberId);

            mainHandler.post(() -> callback.onStatusLoaded(status));
        });
    }

    public void save(
            @NonNull FamilyLiveStatus status,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (status.lastUpdatedAt == 0L) {
                status.lastUpdatedAt = System.currentTimeMillis();
            }

            familyLiveStatusDao.save(status);
            mainHandler.post(callback::onComplete);
        });
    }

    public void updateSharingStatus(
            long familyMemberId,
            boolean enabled,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            familyLiveStatusDao.updateSharingStatus(
                    familyMemberId,
                    enabled,
                    System.currentTimeMillis()
            );

            mainHandler.post(callback::onComplete);
        });
    }

    public void deleteByMemberId(
            long familyMemberId,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            familyLiveStatusDao.deleteByMemberId(familyMemberId);
            mainHandler.post(callback::onComplete);
        });
    }
}