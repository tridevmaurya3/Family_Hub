package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Ends an expired sharing session even when the app process was recreated. */
public final class LocationSharingExpiryWorker extends Worker {

    public LocationSharingExpiryWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters
    ) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        long expiresAt = LocationSharingStore.sharingExpiresAt(context);
        if (expiresAt <= 0L || System.currentTimeMillis() < expiresAt) {
            return Result.success();
        }
        LocationSharingStore.setSharingEnabled(context, false);
        try {
            context.startService(FamilyLocationService.stopIntent(context));
        } catch (RuntimeException ignored) {
            // Server-side expiry immediately blocks readers. The foreground
            // service also observes the same local expiry and stops itself.
        }
        return Result.success();
    }
}
