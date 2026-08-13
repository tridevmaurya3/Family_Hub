package com.tridev.familyhub.location;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Warns shortly before the current timed sharing session expires. */
public final class LocationSharingExpiryWarningWorker extends Worker {

    public static final String KEY_EXPIRES_AT = "expires_at";

    public LocationSharingExpiryWarningWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters
    ) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        long expectedExpiry = getInputData().getLong(KEY_EXPIRES_AT, 0L);
        long currentExpiry = LocationSharingStore.sharingExpiresAt(context);
        if (!LocationSharingStore.isSharingEnabled(context)
                || expectedExpiry <= 0L
                || currentExpiry != expectedExpiry
                || System.currentTimeMillis() >= currentExpiry) {
            return Result.success();
        }
        LocationRecoveryNotifier.showExpiryWarning(context);
        return Result.success();
    }
}
