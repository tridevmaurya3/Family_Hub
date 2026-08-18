package com.tridev.familyhub.feature.finance;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.InvalidationTracker;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.GroceryItemDao;
import com.tridev.familyhub.data.local.entity.FinanceEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Family Hub Finance -> MoneyManager synchronization.
 *
 * The first successfully-posted event/source identity is retained as the
 * canonical link. Later Family Hub edits update that exact MoneyManager row in
 * place instead of creating a replacement event. This keeps Expense/Income rows
 * visible when amount, category, account, date or type is corrected.
 */
public final class FinanceMoneyManagerSyncInitializer extends ContentProvider {

    private static final String PREFS = "finance_money_manager_sync_v1";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String PREFIX_EVENT = "event_";
    private static final String PREFIX_SOURCE = "source_";
    private static final String PREFIX_APPLIED = "applied_";
    private static final String PREFIX_PENDING = "pending_";
    private static final String PREFIX_FORCE_REVIEW = "force_review_";

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private final AtomicBoolean rescanRequested = new AtomicBoolean(false);
    @Nullable private Context appContext;
    @Nullable private InvalidationTracker.Observer observer;
    private long processStartedAt;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        processStartedAt = System.currentTimeMillis();
        appContext = context.getApplicationContext();

        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(
                                @NonNull Activity activity,
                                @Nullable Bundle savedInstanceState) { }
                        @Override public void onActivityStarted(@NonNull Activity activity) { }
                        @Override public void onActivityResumed(@NonNull Activity activity) {
                            // A temporary MoneyManager outage or an older installed
                            // MoneyManager build must not make one edit permanently
                            // disappear. Resume safely retries the same canonical id.
                            scheduleScan();
                        }
                        @Override public void onActivityPaused(@NonNull Activity activity) { }
                        @Override public void onActivityStopped(@NonNull Activity activity) { }
                        @Override public void onActivitySaveInstanceState(
                                @NonNull Activity activity,
                                @NonNull Bundle outState) { }
                        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
                    });
        }

        FamilyHubDatabase database = FamilyHubDatabase.getInstance(appContext);
        observer = new InvalidationTracker.Observer("finance_entries") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                scheduleScan();
            }
        };
        database.getInvalidationTracker().addObserver(observer);
        scheduleScan();
        return true;
    }

    private void scheduleScan() {
        Context context = appContext;
        if (context == null) return;
        if (!scanQueued.compareAndSet(false, true)) {
            rescanRequested.set(true);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                do {
                    rescanRequested.set(false);
                    scan(context);
                } while (rescanRequested.get());
            } finally {
                scanQueued.set(false);
                if (rescanRequested.get()) scheduleScan();
            }
        });
    }

    private void scan(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        List<FinanceEntry> entries = database.financeEntryDao().getAll();
        GroceryItemDao groceryDao = database.groceryItemDao();
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            SharedPreferences.Editor baseline = preferences.edit();
            for (FinanceEntry entry : entries) {
                if (!eligibleStructure(entry, groceryDao)) continue;
                // ContentProviders start before the Activity. An entry created
                // after this timestamp belongs to the current run and must be
                // posted, even if the first async baseline scan was delayed.
                boolean createdThisRun = entry.createdAt > 0L
                        && entry.createdAt >= processStartedAt;
                if (createdThisRun) continue;
                String key = FinanceMoneyManagerBridge.stableEntryKey(entry);
                String event = FinanceMoneyManagerBridge.eventIdFor(entry);
                baseline.putString(PREFIX_EVENT + key, event);
                baseline.putString(PREFIX_SOURCE + key,
                        FinanceMoneyManagerBridge.sourceRecordIdFor(entry));
                baseline.putString(PREFIX_APPLIED + key, event);
                baseline.putBoolean(PREFIX_PENDING + key, false);
                baseline.putBoolean(PREFIX_FORCE_REVIEW + key, false);
            }
            baseline.putBoolean(KEY_INITIALIZED, true).apply();
            // Do not return: any entry created while this first scan waited in
            // the executor is intentionally processed as a new MoneyManager row.
        }

        FirebaseUser currentUser = currentUser();
        Set<String> existingKeys = new HashSet<>();

        for (FinanceEntry entry : entries) {
            if (entry == null || entry.id <= 0L) continue;
            String key = FinanceMoneyManagerBridge.stableEntryKey(entry);
            existingKeys.add(key);

            if (!eligibleStructure(entry, groceryDao)
                    || !belongsToThisDeviceUser(entry, currentUser)) {
                continue;
            }

            String currentEvent = FinanceMoneyManagerBridge.eventIdFor(entry);
            String canonicalEvent = safe(preferences.getString(PREFIX_EVENT + key, ""));
            String canonicalSource = safe(preferences.getString(PREFIX_SOURCE + key, ""));
            String appliedEvent = safe(preferences.getString(
                    PREFIX_APPLIED + key, canonicalEvent));
            boolean pending = preferences.getBoolean(PREFIX_PENDING + key, false);

            if (currentEvent.equals(appliedEvent) && !pending) continue;

            if (canonicalEvent.isEmpty() || canonicalSource.isEmpty()) {
                sendNewAndRemember(context, preferences, key, entry);
                continue;
            }

            if (currentEvent.equals(canonicalEvent) && appliedEvent.equals(canonicalEvent)) {
                // Retry of the original create after a temporary MoneyManager outage.
                sendNewAndRemember(context, preferences, key, entry);
                continue;
            }

            updateAndRemember(context, preferences, key, entry,
                    canonicalEvent, canonicalSource, currentEvent);
        }

        cancelAndPruneDeletedSourceState(context, preferences, existingKeys);
    }

    private void sendNewAndRemember(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull FinanceEntry entry) {
        FinanceMoneyManagerBridge.Result result =
                FinanceMoneyManagerBridge.send(context, entry, false);
        if (result.accepted) {
            String event = FinanceMoneyManagerBridge.eventIdFor(entry);
            preferences.edit()
                    .putString(PREFIX_EVENT + key, event)
                    .putString(PREFIX_SOURCE + key,
                            FinanceMoneyManagerBridge.sourceRecordIdFor(entry))
                    .putString(PREFIX_APPLIED + key, event)
                    .putBoolean(PREFIX_PENDING + key, false)
                    .putBoolean(PREFIX_FORCE_REVIEW + key, false)
                    .apply();
            return;
        }
        preferences.edit()
                .putBoolean(PREFIX_PENDING + key,
                        "UNAVAILABLE".equals(result.status)
                                || "FAILED".equals(result.status)
                                || "QUEUED".equals(result.status))
                .apply();
    }

    private void updateAndRemember(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull FinanceEntry entry,
            @NonNull String canonicalEvent,
            @NonNull String canonicalSource,
            @NonNull String currentEvent) {
        FinanceMoneyManagerBridge.Result result =
                FinanceMoneyManagerBridge.updateLinked(
                        context, entry, canonicalEvent, canonicalSource);

        // An edit is complete only when MoneyManager confirms that the linked
        // ledger row itself was UPDATED. PRESERVED intentionally means no ledger
        // rewrite occurred, so advancing PREFIX_APPLIED there would permanently
        // hide the correction from later retries.
        if ("UPDATED".equalsIgnoreCase(result.status)) {
            preferences.edit()
                    .putString(PREFIX_APPLIED + key, currentEvent)
                    .putBoolean(PREFIX_PENDING + key, false)
                    .putBoolean(PREFIX_FORCE_REVIEW + key, false)
                    .apply();
            return;
        }

        preferences.edit()
                .putBoolean(PREFIX_PENDING + key,
                        "UNAVAILABLE".equals(result.status)
                                || "FAILED".equals(result.status)
                                || "QUEUED".equals(result.status)
                                || "PRESERVED".equals(result.status))
                .apply();
    }

    private boolean eligibleStructure(
            @Nullable FinanceEntry entry,
            @NonNull GroceryItemDao groceryDao) {
        if (!FinanceMoneyManagerBridge.isPostable(entry)) return false;
        if (entry == null) return false;
        if (entry.cloudId != null
                && entry.cloudId.toLowerCase(java.util.Locale.ROOT)
                .startsWith("grocery_")) return false;
        if (entry.note != null && entry.note.startsWith("[Grocery] ")) return false;
        if (entry.note != null
                && entry.note.startsWith("[LoanManagerProjection] ")) return false;
        if (entry.cloudId != null
                && entry.cloudId.toLowerCase(java.util.Locale.ROOT)
                .startsWith("loan_projection_")) return false;
        return groceryDao.getByFinanceEntryId(entry.id) == null;
    }

    private boolean belongsToThisDeviceUser(
            @NonNull FinanceEntry entry,
            @Nullable FirebaseUser currentUser) {
        if (!entry.isShared) return true;
        if (currentUser == null) return false;
        String editorUid = safe(entry.updatedByUid);
        return editorUid.isEmpty() || currentUser.getUid().equals(editorUid);
    }

    @Nullable
    private FirebaseUser currentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void cancelAndPruneDeletedSourceState(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull Set<String> existingKeys) {
        Map<String, ?> all = preferences.getAll();
        for (String prefKey : all.keySet()) {
            if (!prefKey.startsWith(PREFIX_EVENT)) continue;
            String key = prefKey.substring(PREFIX_EVENT.length());
            if (existingKeys.contains(key)) continue;

            String eventId = safe(preferences.getString(PREFIX_EVENT + key, ""));
            String sourceRecordId = safe(preferences.getString(PREFIX_SOURCE + key, ""));
            boolean canPrune;
            if (eventId.isEmpty() || sourceRecordId.isEmpty()) {
                canPrune = true;
            } else {
                FinanceMoneyManagerBridge.Result result =
                        FinanceMoneyManagerBridge.cancel(context, eventId, sourceRecordId);
                canPrune = result.accepted;
            }
            if (!canPrune) continue;

            preferences.edit()
                    .remove(PREFIX_EVENT + key)
                    .remove(PREFIX_SOURCE + key)
                    .remove(PREFIX_APPLIED + key)
                    .remove(PREFIX_PENDING + key)
                    .remove(PREFIX_FORCE_REVIEW + key)
                    .apply();
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri,
            @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri,
            @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
