package com.tridev.familyhub.location;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Handles the explicit, app-internal extension action from the warning. */
public final class LocationSharingExtendReceiver extends BroadcastReceiver {

    private static final long EXTENSION_MS = 60L * 60L * 1000L;

    @Override
    public void onReceive(
            @NonNull Context context,
            @NonNull Intent intent
    ) {
        LocationSharingStore.extendTimedSharing(context, EXTENSION_MS);
        LocationRecoveryNotifier.cancelExpiryWarning(context);
    }
}
