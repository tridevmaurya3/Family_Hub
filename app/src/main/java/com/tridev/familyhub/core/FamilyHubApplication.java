package com.tridev.familyhub.core;

import android.app.Application;

/** Application-wide entry point for dependency setup and future offline services. */
public class FamilyHubApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Room, WorkManager, encrypted preferences, and sync setup will live here.
    }
}
