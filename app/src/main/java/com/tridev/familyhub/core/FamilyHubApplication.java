package com.tridev.familyhub.core;

import android.app.Application;

import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.FirebaseDatabase;

/** Application-wide entry point for offline-first service setup. */
public class FamilyHubApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        enableFirebaseOfflinePersistence();
    }

    private void enableFirebaseOfflinePersistence() {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (DatabaseException ignored) {
            // Firebase was already initialized; never log sensitive app data.
        }
    }
}
