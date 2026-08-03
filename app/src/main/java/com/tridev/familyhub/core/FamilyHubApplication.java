package com.tridev.familyhub.core;

import android.app.Application;
import android.content.Context;

import androidx.annotation.Nullable;

import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.location.LocationRecoveryNotifier;
import com.tridev.familyhub.location.LocationServiceRecoveryScheduler;
import com.tridev.familyhub.location.LocationSharingStore;
import com.tridev.familyhub.location.PendingLocationSyncScheduler;

/** Application-wide entry point for offline-first service setup. */
public class FamilyHubApplication extends Application {

    @Nullable
    private static Context applicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = getApplicationContext();
        enableFirebaseOfflinePersistence();
        restoreFamilyLiveSafetyNets();
    }

    @Nullable
    public static Context getAppContextOrNull() {
        return applicationContext;
    }

    private void enableFirebaseOfflinePersistence() {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (DatabaseException ignored) {
            // Firebase was already initialized; never log sensitive app data.
        }
    }

    /**
     * Re-registers durable sync and service recovery after Android recreates
     * the app process. Sharing is never enabled here unless the user had
     * already enabled it earlier.
     */
    private void restoreFamilyLiveSafetyNets() {
        if (!LocationSharingStore.isSharingEnabled(this)) {
            return;
        }

        PendingLocationSyncScheduler.enablePeriodicSync(this);
        PendingLocationSyncScheduler.schedule(this);
        LocationServiceRecoveryScheduler.scheduleNow(this);
        LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(this);
    }
}
