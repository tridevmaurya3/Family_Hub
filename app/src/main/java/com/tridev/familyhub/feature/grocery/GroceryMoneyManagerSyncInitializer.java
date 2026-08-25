package com.tridev.familyhub.feature.grocery;

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
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reliable Grocery -> MoneyManager synchronization.
 *
 * The event/source identity of the first successful purchase remains canonical.
 * Later corrections to amount, store, MoneyManager account/card or Expense
 * category update that exact MoneyManager row rather than replacing/removing it.
 * Existing historical purchases stay baselined, while a purchase completed after
 * this process starts is never swallowed by the first asynchronous baseline scan.
 *
 * In addition to the payload signature, the last successfully-applied Grocery
 * updatedAt version is retained. This prevents an edit from being skipped when an
 * older sync-state migration accidentally baselined the new payload before the
 * linked MoneyManager row was actually updated.
 */
public final class GroceryMoneyManagerSyncInitializer extends ContentProvider {

    private static final String PREFS = "grocery_money_manager_sync_v1";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String PREFIX_EVENT = "event_";
    private static final String PREFIX_SOURCE = "source_";
    private static final String PREFIX_APPLIED = "applied_";
    private static final String PREFIX_VERSION = "version_";
    private static final String PREFIX_COUNT = "count_";
    private static final String PREFIX_SENT = "sent_";

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private final AtomicBoolean rescanRequested = new AtomicBoolean(false);
    private final Set<String> promptingKeys = Collections.newSetFromMap(
            new ConcurrentHashMap<>());

    @Nullable private Context appContext;
    @Nullable private InvalidationTracker.Observer observer;
    @NonNull private volatile WeakReference<Activity> foregroundActivity =
            new WeakReference<>(null);
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
                            foregroundActivity = new WeakReference<>(activity);
                            scheduleScan(false);
                        }
                        @Override public void onActivityPaused(@NonNull Activity activity) {
                            Activity current = foregroundActivity.get();
                            if (current == activity) foregroundActivity = new WeakReference<>(null);
                        }
                        @Override public void onActivityStopped(@NonNull Activity activity) { }
                        @Override public void onActivitySaveInstanceState(
                                @NonNull Activity activity, @NonNull Bundle outState) { }
                        @Override public void onActivityDestroyed(@NonNull Activity activity) {
                            Activity current = foregroundActivity.get();
                            if (current == activity) foregroundActivity = new WeakReference<>(null);
                        }
                    });
        }

        FamilyHubDatabase database = FamilyHubDatabase.getInstance(appContext);
        observer = new InvalidationTracker.Observer("grocery_items") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                scheduleScan(false);
            }
        };
        database.getInvalidationTracker().addObserver(observer);
        scheduleScan(true);
        return true;
    }

    private void scheduleScan(boolean startup) {
        Context context = appContext;
        if (context == null) return;
        if (!scanQueued.compareAndSet(false, true)) {
            rescanRequested.set(true);
            return;
        }
        EXECUTOR.execute(() -> {
            boolean firstPass = true;
            try {
                do {
                    rescanRequested.set(false);
                    scan(context, firstPass && startup);
                    firstPass = false;
                } while (rescanRequested.get());
            } finally {
                scanQueued.set(false);
                if (rescanRequested.get()) scheduleScan(false);
            }
        });
    }

    private void scan(@NonNull Context context, boolean startup) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<GroceryItem> items = FamilyHubDatabase.getInstance(context)
                .groceryItemDao().getAll();

        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            SharedPreferences.Editor baseline = preferences.edit();
            for (GroceryItem item : items) {
                if (item == null || !item.isPurchased || item.purchasedAt <= 0L) continue;
                // ContentProviders are created before the app Activity/Service.
                // Therefore a purchase timestamp newer than processStartedAt is a
                // real action from this run, not historical data to suppress.
                if (item.purchasedAt >= processStartedAt) continue;
                String key = itemPreferenceKey(item);
                baseline.putString(PREFIX_EVENT + key,
                        GroceryMoneyManagerBridge.eventIdFor(item));
                baseline.putString(PREFIX_SOURCE + key,
                        GroceryMoneyManagerBridge.sourceRecordIdFor(item));
                baseline.putString(PREFIX_APPLIED + key,
                        GroceryMoneyManagerBridge.moneyPayloadSignature(context, item));
                baseline.putLong(PREFIX_VERSION + key, Math.max(0L, item.updatedAt));
                baseline.putInt(PREFIX_COUNT + key, Math.max(0, item.purchaseCount));
                baseline.putBoolean(PREFIX_SENT + key, false);
            }
            baseline.putBoolean(KEY_INITIALIZED, true).apply();
            // Do not return. A Grocery purchase can be completed while the first
            // async scan is waiting for its executor; those current-run rows must
            // be processed immediately below instead of being silently baselined.
        }

        FirebaseUser currentUser = null;
        try {
            currentUser = FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException ignored) { }

        Set<String> existingKeys = new HashSet<>();

        for (GroceryItem item : items) {
            if (item == null || item.id <= 0L) continue;
            String key = itemPreferenceKey(item);
            existingKeys.add(key);

            String canonicalEvent = safe(preferences.getString(PREFIX_EVENT + key, ""));
            String canonicalSource = safe(preferences.getString(PREFIX_SOURCE + key, ""));
            String appliedPayload = safe(preferences.getString(PREFIX_APPLIED + key, ""));
            long appliedVersion = preferences.getLong(PREFIX_VERSION + key, 0L);
            int previousCount = preferences.getInt(PREFIX_COUNT + key, 0);
            boolean previousSent = preferences.getBoolean(PREFIX_SENT + key, false);

            if (item.isPurchased && item.purchasedAt > 0L) {
                String currentEvent = GroceryMoneyManagerBridge.eventIdFor(item);
                String currentSource = GroceryMoneyManagerBridge.sourceRecordIdFor(item);
                String currentPayload = GroceryMoneyManagerBridge.moneyPayloadSignature(context, item);

                // Only historical/local-only rows may be safely baselined here.
                // A row that was already sent to MoneyManager must never absorb a
                // newer edit into local state before the linked ledger row updates.
                if (appliedPayload.isEmpty()
                        && currentEvent.equals(canonicalEvent)
                        && !previousSent) {
                    appliedPayload = currentPayload;
                    appliedVersion = Math.max(0L, item.updatedAt);
                    preferences.edit()
                            .putString(PREFIX_APPLIED + key, appliedPayload)
                            .putLong(PREFIX_VERSION + key, appliedVersion)
                            .apply();
                }

                if (!belongsToThisDeviceUser(item, currentUser)) {
                    if (canonicalEvent.isEmpty()) {
                        rememberNew(preferences, key, currentEvent, currentSource,
                                currentPayload, item.updatedAt,
                                item.purchaseCount, false);
                    } else {
                        rememberApplied(preferences, key, currentPayload,
                                item.updatedAt, item.purchaseCount, previousSent);
                    }
                    continue;
                }

                // Reconstructed recurring purchases were previously visible only
                // in Grocery's local history and were therefore baselined before
                // MoneyManager ever received them. Backfill only those surviving
                // recovered occurrences once. Deleted Grocery rows are absent from
                // this scan, and PREFIX_SENT keeps successful rows idempotent.
                if (isRecoveredRecurringPurchase(item) && !previousSent) {
                    postNewPurchase(context, preferences, key, item,
                            currentEvent, currentSource, currentPayload);
                    continue;
                }

                if (canonicalEvent.isEmpty() || canonicalSource.isEmpty()) {
                    postNewPurchase(context, preferences, key, item,
                            currentEvent, currentSource, currentPayload);
                    continue;
                }

                boolean changed = !currentEvent.equals(canonicalEvent)
                        || !currentPayload.equals(appliedPayload)
                        || (previousSent && item.updatedAt > appliedVersion);
                if (!changed) continue;

                if (!previousSent) {
                    // A higher purchaseCount is a new explicit purchase cycle, not
                    // an edit of the historical baselined purchase. Restore the
                    // original behaviour: it must be offered to MoneyManager.
                    if (item.purchaseCount > previousCount) {
                        postNewPurchase(context, preferences, key, item,
                                currentEvent, currentSource, currentPayload);
                    } else {
                        // Historical baselined data remains local-only.
                        rememberApplied(preferences, key, currentPayload,
                                item.updatedAt, item.purchaseCount, false);
                    }
                    continue;
                }

                updateExistingPurchase(context, preferences, key, item,
                        canonicalEvent, canonicalSource, currentPayload);
                continue;
            }

            if (!canonicalEvent.isEmpty()) {
                boolean explicitUndo = item.purchaseCount < previousCount;
                if (explicitUndo && previousSent && !canonicalSource.isEmpty()) {
                    GroceryMoneyManagerBridge.Result cancelled =
                            GroceryMoneyManagerBridge.cancelPurchase(
                                    context, canonicalEvent, canonicalSource);
                    if (!cancelled.accepted) continue;
                }
                clearRemembered(preferences, key);
            }
        }

        cancelAndPruneDeletedItems(context, preferences, existingKeys);
    }

    private static boolean isRecoveredRecurringPurchase(
            @NonNull GroceryItem item) {
        String cloudId = item.cloudId == null ? "" : item.cloudId.trim();
        if (!cloudId.startsWith("recovered-purchase-")) return false;
        String cycle = GroceryRecurrenceEngine.normalizeCycle(item.listType);
        return GroceryRecurrenceEngine.isRecurringType(cycle);
    }

    private void postNewPurchase(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull GroceryItem item,
            @NonNull String currentEvent,
            @NonNull String currentSource,
            @NonNull String currentPayload) {
        GroceryMoneyManagerBridge.Result direct =
                GroceryMoneyManagerBridge.sendPurchase(context, item);
        if (direct.accepted) {
            rememberNew(preferences, key, currentEvent, currentSource,
                    currentPayload, item.updatedAt, item.purchaseCount, true);
            return;
        }
        // A background startup/realtime scan must never open an account picker.
        // Shared or recovered history can arrive in batches and previously caused
        // repeated "Paid from" dialogs on family-member devices. When mapping is
        // missing, preserve the unsent state and retry silently after the user
        // selects account/category in the normal Grocery Add/Edit/Post Purchase UI.
        if ("MAPPING_REQUIRED".equals(direct.status)) return;
    }

    private void updateExistingPurchase(
            @NonNull Context context,
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull GroceryItem item,
            @NonNull String canonicalEvent,
            @NonNull String canonicalSource,
            @NonNull String currentPayload) {
        GroceryMoneyManagerBridge.Result direct =
                GroceryMoneyManagerBridge.updateLinkedPurchase(
                        context, item, canonicalEvent, canonicalSource);
        if ("UPDATED".equalsIgnoreCase(direct.status)) {
            rememberApplied(preferences, key, currentPayload,
                    item.updatedAt, item.purchaseCount, true);
            return;
        }
        // PRESERVED means MoneyManager deliberately did not rewrite the linked
        // row. It is not an edit success and must never advance local applied state.
        // Do not interrupt a family member with a picker from a background edit
        // retry; the normal Grocery form remains the explicit mapping surface.
        if ("MAPPING_REQUIRED".equals(direct.status)) return;
    }

    private void cancelAndPruneDeletedItems(
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
            boolean sent = preferences.getBoolean(PREFIX_SENT + key, false);
            if (sent && !eventId.isEmpty() && !sourceRecordId.isEmpty()) {
                GroceryMoneyManagerBridge.Result cancelled =
                        GroceryMoneyManagerBridge.cancelPurchase(
                                context, eventId, sourceRecordId);
                if (!cancelled.accepted) continue;
            }
            clearRemembered(preferences, key);
        }
    }

    private boolean belongsToThisDeviceUser(
            @NonNull GroceryItem item,
            @Nullable FirebaseUser currentUser) {
        if (item.familyId == null || item.familyId.trim().isEmpty()) return true;
        if (currentUser == null) return item.updatedByUid == null
                || item.updatedByUid.trim().isEmpty();
        String editorUid = item.updatedByUid == null ? "" : item.updatedByUid.trim();
        return editorUid.isEmpty() || currentUser.getUid().equals(editorUid);
    }

    private void rememberNew(
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull String eventId,
            @NonNull String sourceRecordId,
            @NonNull String appliedPayload,
            long appliedVersion,
            int purchaseCount,
            boolean sent) {
        preferences.edit()
                .putString(PREFIX_EVENT + key, eventId)
                .putString(PREFIX_SOURCE + key, sourceRecordId)
                .putString(PREFIX_APPLIED + key, appliedPayload)
                .putLong(PREFIX_VERSION + key, Math.max(0L, appliedVersion))
                .putInt(PREFIX_COUNT + key, Math.max(0, purchaseCount))
                .putBoolean(PREFIX_SENT + key, sent)
                .apply();
    }

    private void rememberApplied(
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull String appliedPayload,
            long appliedVersion,
            int purchaseCount,
            boolean sent) {
        preferences.edit()
                .putString(PREFIX_APPLIED + key, appliedPayload)
                .putLong(PREFIX_VERSION + key, Math.max(0L, appliedVersion))
                .putInt(PREFIX_COUNT + key, Math.max(0, purchaseCount))
                .putBoolean(PREFIX_SENT + key, sent)
                .apply();
    }

    private void clearRemembered(
            @NonNull SharedPreferences preferences,
            @NonNull String key) {
        preferences.edit()
                .remove(PREFIX_EVENT + key)
                .remove(PREFIX_SOURCE + key)
                .remove(PREFIX_APPLIED + key)
                .remove(PREFIX_VERSION + key)
                .remove(PREFIX_COUNT + key)
                .remove(PREFIX_SENT + key)
                .apply();
    }

    @NonNull
    private String itemPreferenceKey(@NonNull GroceryItem item) {
        String raw = item.cloudId == null || item.cloudId.trim().isEmpty()
                ? "local:" + item.id
                : "cloud:" + item.cloudId.trim();
        return sha256(raw).substring(0, 24);
    }

    @NonNull
    private String sha256(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                output.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return output.toString();
        } catch (Exception impossibleOnAndroid) {
            return String.format(Locale.US, "%024x", value.hashCode());
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
