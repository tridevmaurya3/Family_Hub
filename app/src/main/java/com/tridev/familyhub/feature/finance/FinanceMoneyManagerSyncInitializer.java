package com.tridev.familyhub.feature.finance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

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
 * STEP 9/10 bootstrap for Family Hub Finance -> MoneyManagerPro.
 *
 * Grocery-owned rows and finalized LoanManager projections are excluded so
 * MoneyManager never receives an echo of a transaction it already finalized.
 * Family-owned Income/Expense deletions are forwarded to MoneyManager using the
 * original deterministic event identity. MoneyManager decides whether its row
 * is integration-owned and therefore removable, or whether external/manual
 * evidence requires the canonical ledger row to be preserved.
 */
public final class FinanceMoneyManagerSyncInitializer extends ContentProvider {

    private static final String PREFS = "finance_money_manager_sync_v1";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String PREFIX_EVENT = "event_";
    private static final String PREFIX_SOURCE = "source_";
    private static final String PREFIX_PENDING = "pending_";
    private static final String PREFIX_FORCE_REVIEW = "force_review_";

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private final AtomicBoolean rescanRequested = new AtomicBoolean(false);
    @Nullable private Context appContext;
    @Nullable private InvalidationTracker.Observer observer;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        appContext = context.getApplicationContext();

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
                if (rescanRequested.get()) {
                    scheduleScan();
                }
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
                String key = FinanceMoneyManagerBridge.stableEntryKey(entry);
                baseline.putString(PREFIX_EVENT + key,
                        FinanceMoneyManagerBridge.eventIdFor(entry));
                baseline.putString(PREFIX_SOURCE + key,
                        FinanceMoneyManagerBridge.sourceRecordIdFor(entry));
                baseline.putBoolean(PREFIX_PENDING + key, false);
                baseline.putBoolean(PREFIX_FORCE_REVIEW + key, false);
            }
            baseline.putBoolean(KEY_INITIALIZED, true).apply();
            return;
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
            String previousEvent = safe(preferences.getString(
                    PREFIX_EVENT + key, ""));
            boolean pending = preferences.getBoolean(PREFIX_PENDING + key, false);
            boolean pendingForceReview = preferences.getBoolean(
                    PREFIX_FORCE_REVIEW + key, false);

            if (!previousEvent.isEmpty() && currentEvent.equals(previousEvent)) {
                if (pending) {
                    sendAndRemember(context, preferences, key, entry,
                            pendingForceReview);
                }
                continue;
            }

            boolean forceReview = !previousEvent.isEmpty();
            sendAndRemember(context, preferences, key, entry, forceReview);
        }

        cancelAndPruneDeletedSourceState(context, preferences, existingKeys);
    }

    private void sendAndRemember(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull FinanceEntry entry,
            boolean forceReview) {
        FinanceMoneyManagerBridge.Result result =
                FinanceMoneyManagerBridge.send(context, entry, forceReview);

        String currentEvent = FinanceMoneyManagerBridge.eventIdFor(entry);
        String currentSource = FinanceMoneyManagerBridge.sourceRecordIdFor(entry);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(PREFIX_EVENT + key, currentEvent)
                .putString(PREFIX_SOURCE + key, currentSource)
                .putBoolean(PREFIX_FORCE_REVIEW + key, forceReview);

        if (result.accepted) {
            editor.putBoolean(PREFIX_PENDING + key, false);
        } else if ("UNAVAILABLE".equals(result.status)
                || "FAILED".equals(result.status)) {
            editor.putBoolean(PREFIX_PENDING + key, true);
        } else {
            editor.putBoolean(PREFIX_PENDING + key, false);
        }
        editor.apply();
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

            if (!canPrune) {
                // Keep the original event identity. A later Room invalidation or
                // process restart will retry cancellation when MoneyManager is ready.
                continue;
            }

            preferences.edit()
                    .remove(PREFIX_EVENT + key)
                    .remove(PREFIX_SOURCE + key)
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
