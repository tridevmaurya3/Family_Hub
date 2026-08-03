package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PendingLocationUploadDao;

/**
 * Deletes pending encrypted location data only while sharing remains disabled.
 *
 * This worker never uploads data. If the user quickly starts a new sharing
 * session before cleanup runs, it exits without touching the new session.
 */
public final class PendingLocationCleanupWorker extends Worker {

    public PendingLocationCleanupWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (LocationSharingStore.isSharingEnabled(context)) {
            return Result.success();
        }

        PendingLocationUploadDao dao = FamilyHubDatabase
                .getInstance(context)
                .pendingLocationUploadDao();
        dao.deleteAll();
        return Result.success();
    }
}
