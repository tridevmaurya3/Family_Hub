package com.tridev.familyhub.feature.automation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Periodic fallback plus the next known scheduled-sharing boundary. */
public final class FamilyAutomationScheduler {

    private static final String UNIQUE_PERIODIC =
            "family_automation_periodic";
    private static final String UNIQUE_IMMEDIATE =
            "family_automation_immediate";
    private static final String UNIQUE_BOUNDARY =
            "family_automation_next_boundary";

    private FamilyAutomationScheduler() {
    }

    public static void enable(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                FamilyAutomationWorker.class,
                15L,
                TimeUnit.MINUTES
        )
                .addTag(UNIQUE_PERIODIC)
                .build();
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
        );
    }

    public static void scheduleNow(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                FamilyAutomationWorker.class
        )
                .addTag(UNIQUE_IMMEDIATE)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_IMMEDIATE,
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }

    public static void scheduleNextBoundary(
            @NonNull Context context,
            @NonNull List<FamilyAutomationRule> rules,
            long now
    ) {
        long next = Long.MAX_VALUE;
        for (FamilyAutomationRule rule : rules) {
            next = Math.min(
                    next,
                    FamilyAutomationPolicy.nextBoundaryAfter(rule, now)
            );
        }
        WorkManager manager = WorkManager.getInstance(
                context.getApplicationContext()
        );
        if (next == Long.MAX_VALUE) {
            manager.cancelUniqueWork(UNIQUE_BOUNDARY);
            return;
        }
        long delay = Math.max(30_000L, next - now + 2_000L);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                FamilyAutomationWorker.class
        )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_BOUNDARY)
                .build();
        manager.enqueueUniqueWork(
                UNIQUE_BOUNDARY,
                ExistingWorkPolicy.REPLACE,
                request
        );
    }
}
