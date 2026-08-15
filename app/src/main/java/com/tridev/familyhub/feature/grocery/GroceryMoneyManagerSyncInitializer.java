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
 * STEP 8 / STEP 13H reliability bootstrap for Family Hub Grocery -> MoneyManagerPro.
 *
 * Existing purchases are baselined and are never imported retrospectively.
 * New local purchase cycles are observed from Room and offered to MoneyManager.
 *
 * Reliability rules:
 * - Room invalidations that arrive while a scan is already running are never lost;
 *   a pending rescan is executed immediately after the current scan.
 * - A new local purchase first attempts a direct, type-safe MoneyManager post.
 *   The bridge revalidates the selected account/card and Expense category against
 *   MoneyManager's live master catalog, so the floating overlay does not depend
 *   on a stale cached catalog snapshot.
 * - NEEDS_REVIEW, QUEUED and mapping-required responses are not treated as a
 *   finalized send and therefore never clear the item's pending selections.
 * - If exact selections are missing, the foreground picker remains the safe
 *   fallback; the bridge never guesses or auto-creates finance masters.
 * - Undo and full item deletion both cancel only the exact previously-finalized
 *   Grocery event. Failed cancellation state is retained for a later retry.
 * - Monthly reset keeps purchaseCount unchanged and therefore never cancels the
 *   previous month's legitimate expense.
 * - Remote purchases made by another family member are not pushed into this
 *   device owner's personal MoneyManager ledger.
 */
public final class GroceryMoneyManagerSyncInitializer extends ContentProvider {

    private static final String PREFS = "grocery_money_manager_sync_v1";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String PREFIX_EVENT = "event_";
    private static final String PREFIX_SOURCE = "source_";
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

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        appContext = context.getApplicationContext();

        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(
                                @NonNull Activity activity,
                                @Nullable Bundle savedInstanceState) { }
                        @Override public void onActivityStarted(
                                @NonNull Activity activity) { }
                        @Override public void onActivityResumed(
                                @NonNull Activity activity) {
                            foregroundActivity = new WeakReference<>(activity);
                            scheduleScan(false);
                        }
                        @Override public void onActivityPaused(
                                @NonNull Activity activity) {
                            Activity current = foregroundActivity.get();
                            if (current == activity) {
                                foregroundActivity = new WeakReference<>(null);
                            }
                        }
                        @Override public void onActivityStopped(
                                @NonNull Activity activity) { }
                        @Override public void onActivitySaveInstanceState(
                                @NonNull Activity activity,
                                @NonNull Bundle outState) { }
                        @Override public void onActivityDestroyed(
                                @NonNull Activity activity) {
                            Activity current = foregroundActivity.get();
                            if (current == activity) {
                                foregroundActivity = new WeakReference<>(null);
                            }
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
                if (rescanRequested.get()) {
                    scheduleScan(false);
                }
            }
        });
    }

    private void scan(@NonNull Context context, boolean startup) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        List<GroceryItem> items = FamilyHubDatabase.getInstance(context)
                .groceryItemDao().getAll();

        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            SharedPreferences.Editor baseline = preferences.edit();
            for (GroceryItem item : items) {
                if (item == null || !item.isPurchased || item.purchasedAt <= 0L) continue;
                String key = itemPreferenceKey(item);
                baseline.putString(PREFIX_EVENT + key,
                        GroceryMoneyManagerBridge.eventIdFor(item));
                baseline.putString(PREFIX_SOURCE + key,
                        GroceryMoneyManagerBridge.sourceRecordIdFor(item));
                baseline.putInt(PREFIX_COUNT + key, Math.max(0, item.purchaseCount));
                baseline.putBoolean(PREFIX_SENT + key, false);
            }
            baseline.putBoolean(KEY_INITIALIZED, true).apply();
            return;
        }

        FirebaseUser currentUser = null;
        try {
            currentUser = FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException ignored) {
            // Local-only Family Hub stays eligible for same-device integration.
        }

        Set<String> existingKeys = new HashSet<>();

        for (GroceryItem item : items) {
            if (item == null || item.id <= 0L) continue;
            String key = itemPreferenceKey(item);
            existingKeys.add(key);

            String previousEvent = safe(preferences.getString(PREFIX_EVENT + key, ""));
            String previousSource = safe(preferences.getString(PREFIX_SOURCE + key, ""));
            int previousCount = preferences.getInt(PREFIX_COUNT + key, 0);
            boolean previousSent = preferences.getBoolean(PREFIX_SENT + key, false);

            if (item.isPurchased && item.purchasedAt > 0L) {
                String currentEvent = GroceryMoneyManagerBridge.eventIdFor(item);
                if (currentEvent.equals(previousEvent)) {
                    // Includes the intentional first-install baseline. Never import
                    // an already-existing purchase retrospectively.
                    continue;
                }

                if (!belongsToThisDeviceUser(item, currentUser)) {
                    remember(preferences, key, currentEvent,
                            GroceryMoneyManagerBridge.sourceRecordIdFor(item),
                            item.purchaseCount, false);
                    continue;
                }

                GroceryMoneyManagerBridge.Result direct =
                        GroceryMoneyManagerBridge.sendPurchase(context, item);
                if (direct.accepted) {
                    remember(preferences, key, currentEvent,
                            GroceryMoneyManagerBridge.sourceRecordIdFor(item),
                            item.purchaseCount, true);
                    continue;
                }

                if (!"MAPPING_REQUIRED".equals(direct.status)) {
                    // MoneyManager unavailable/review/queued states remain untouched.
                    // A later Room invalidation/process resume retries the exact same
                    // deterministic event without creating a duplicate.
                    continue;
                }

                Activity activity = foregroundActivity.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    // The floating overlay already exposes account/category fields.
                    // If they were left unresolved, wait rather than guessing.
                    continue;
                }
                if (!promptingKeys.add(key)) continue;

                GroceryMoneyManagerAccountPicker.chooseForCompletedPurchase(
                        activity,
                        item,
                        () -> EXECUTOR.execute(() -> {
                            try {
                                GroceryMoneyManagerBridge.Result result =
                                        GroceryMoneyManagerBridge.sendPurchase(context, item);
                                if (result.accepted) {
                                    remember(preferences, key, currentEvent,
                                            GroceryMoneyManagerBridge.sourceRecordIdFor(item),
                                            item.purchaseCount, true);
                                }
                            } finally {
                                promptingKeys.remove(key);
                            }
                        }));
                continue;
            }

            if (!previousEvent.isEmpty()) {
                boolean explicitUndo = item.purchaseCount < previousCount;
                if (explicitUndo && previousSent && !previousSource.isEmpty()) {
                    GroceryMoneyManagerBridge.Result cancelled =
                            GroceryMoneyManagerBridge.cancelPurchase(
                                    context, previousEvent, previousSource);
                    if (!cancelled.accepted) {
                        // Retain the exact original identity and retry later.
                        continue;
                    }
                }
                clearRemembered(preferences, key);
            }
        }

        cancelAndPruneDeletedItems(context, preferences, existingKeys);
    }

    /**
     * A purchased Grocery item can be removed completely (Delete/Clear Purchased)
     * rather than first being unchecked. In that case the Room row no longer
     * exists, so cancellation must be driven from the retained event/source state.
     */
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
                if (!cancelled.accepted) {
                    // MoneyManager may be temporarily unavailable. Keep state so
                    // a later scan can finish the exact same safe cancellation.
                    continue;
                }
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

    private void remember(
            @NonNull SharedPreferences preferences,
            @NonNull String key,
            @NonNull String eventId,
            @NonNull String sourceRecordId,
            int purchaseCount,
            boolean sent) {
        preferences.edit()
                .putString(PREFIX_EVENT + key, eventId)
                .putString(PREFIX_SOURCE + key, sourceRecordId)
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

    // Initializer only; no public CRUD surface.
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
