package com.tridev.familyhub.core;

import android.app.Application;
import android.content.Context;

import androidx.annotation.Nullable;

import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.backup.BackupScheduler;
import com.tridev.familyhub.feature.automation.FamilyAutomationLiveMonitor;
import com.tridev.familyhub.feature.automation.FamilyAutomationRuntime;
import com.tridev.familyhub.feature.automation.FamilyAutomationScheduler;
import com.tridev.familyhub.feature.documents.DocumentExpiryScheduler;
import com.tridev.familyhub.feature.integration.MoneyManagerFormAutoBinder;
import com.tridev.familyhub.feature.journey.FamilyJourneyRecorder;
import com.tridev.familyhub.feature.sos.FamilySosLiveMonitor;
import com.tridev.familyhub.geofence.SafePlaceGeofenceSyncScheduler;
import com.tridev.familyhub.geofence.FamilyAlertCloudSyncScheduler;
import com.tridev.familyhub.location.FamilyDeviceSafetyMonitorScheduler;
import com.tridev.familyhub.location.FamilyLivePrecisionActivityCallbacks;
import com.tridev.familyhub.location.LocationRecoveryNotifier;
import com.tridev.familyhub.location.LocationServiceRecoveryScheduler;
import com.tridev.familyhub.location.LocationServiceWatchdogScheduler;
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
        BackupScheduler.sync(this);
        DocumentExpiryScheduler.sync(this);
        registerActivityLifecycleCallbacks(new FamilyLivePrecisionActivityCallbacks());
        MoneyManagerFormAutoBinder.register(this);
        FamilyJourneyRecorder.start(this);
        FamilySosLiveMonitor.start(this);
        FamilyAutomationRuntime.start(this);
        FamilyAutomationLiveMonitor.start(this);
        FamilyAutomationScheduler.enable(this);
        FamilyAutomationScheduler.scheduleNow(this);
        SafePlaceGeofenceSyncScheduler.scheduleNow(this);
        FamilyDeviceSafetyMonitorScheduler.enable(this);
        FamilyAlertCloudSyncScheduler.enable(this);
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

    private void restoreFamilyLiveSafetyNets() {
        if (!LocationSharingStore.isSharingEnabled(this)) {
            return;
        }

        PendingLocationSyncScheduler.enablePeriodicSync(this);
        PendingLocationSyncScheduler.schedule(this);
        LocationServiceWatchdogScheduler.enable(this);
        LocationServiceRecoveryScheduler.scheduleNow(this);
        LocationRecoveryNotifier.showBatteryRestrictionIfNeeded(this);
    }
}
