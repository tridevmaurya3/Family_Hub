package com.tridev.familyhub.geofence;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Restores the complete enabled Safe Place geofence set after process death. */
public final class SafePlaceGeofenceSyncWorker extends Worker {

    private static final long CALLBACK_TIMEOUT_SECONDS = 25L;

    public SafePlaceGeofenceSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!SafePlaceRegistrar.hasRequiredPermissions(context)) {
            return Result.success();
        }

        List<SafePlace> enabled;
        try {
            enabled = FamilyHubDatabase.getInstance(context)
                    .safePlaceDao()
                    .getEnabled();
        } catch (RuntimeException error) {
            return Result.retry();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger outcome = new AtomicInteger(0);
        boolean started = SafePlaceRegistrar.synchronize(
                context,
                enabled,
                new SafePlaceRegistrar.SynchronizationCallback() {
                    @Override
                    public void onSynchronized(int registeredCount) {
                        outcome.set(1);
                        latch.countDown();
                    }

                    @Override
                    public void onPermissionDenied() {
                        outcome.set(2);
                        latch.countDown();
                    }

                    @Override
                    public void onError() {
                        outcome.set(3);
                        latch.countDown();
                    }
                }
        );
        if (!started) {
            return Result.success();
        }

        try {
            if (!latch.await(
                    CALLBACK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                return Result.retry();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        return outcome.get() == 1 || outcome.get() == 2
                ? Result.success()
                : Result.retry();
    }
}
