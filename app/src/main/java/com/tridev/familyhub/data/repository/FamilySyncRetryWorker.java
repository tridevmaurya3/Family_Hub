package com.tridev.familyhub.data.repository;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.tasks.Tasks;
import com.tridev.familyhub.core.security.AccountDataScope;
import java.util.concurrent.TimeUnit;

/** Retries saved mutations without requiring the original form to remain open. */
public final class FamilySyncRetryWorker extends Worker {
    public FamilySyncRetryWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString(AccountDataScope.EXTRA_UID);
        if (uid == null || !uid.equals(AccountDataScope.uid())) return Result.success();
        try { Tasks.await(FamilySyncOutbox.drain(uid), 25, TimeUnit.SECONDS); return Result.success(); }
        catch (Exception error) { return Result.retry(); }
    }
}
