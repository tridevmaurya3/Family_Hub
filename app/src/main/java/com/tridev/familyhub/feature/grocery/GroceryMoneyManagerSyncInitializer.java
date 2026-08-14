package com.tridev.familyhub.feature.grocery;

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
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STEP 8 bootstrap for Family Hub Grocery -> MoneyManagerPro.
 *
 * It observes the existing grocery Room table without changing its schema.
 * Existing purchases are baselined on first install/update and are NOT imported
 * retrospectively. Only later completed purchase cycles are offered to
 * MoneyManagerPro.
 *
 * Remote purchases made by another signed-in family member are not pushed into
 * this device owner's personal MoneyManager ledger. Family Hub Finance continues
 * to hold the shared family expense independently.
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
    @Nullable private Context appContext;
    @Nullable private InvalidationTracker.Observer observer;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        appContext = context.getApplicationContext();

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
        if (context == null || !scanQueued.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                scan(context, startup);
            } finally {
                scanQueued.set(false);
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

        for (GroceryItem item : items) {
            if (item == null || item.id <= 0L) continue;
            String key = itemPreferenceKey(item);
            String previousEvent = safe(preferences.getString(PREFIX_EVENT + key, ""));
            String previousSource = safe(preferences.getString(PREFIX_SOURCE + key, ""));
            int previousCount = preferences.getInt(PREFIX_COUNT + key, 0);
            boolean previousSent = preferences.getBoolean(PREFIX_SENT + key, false);

            if (item.isPurchased && item.purchasedAt > 0L) {
                String currentEvent = GroceryMoneyManagerBridge.eventIdFor(item);
                if (currentEvent.equals(previousEvent)) continue;

                if (!belongsToThisDeviceUser(item, currentUser)) {
                    remember(preferences, key, currentEvent,
                            GroceryMoneyManagerBridge.sourceRecordIdFor(item),
                            item.purchaseCount, false);
                    continue;
                }

                GroceryMoneyManagerBridge.Result result =
                        GroceryMoneyManagerBridge.sendPurchase(context, item);
                if (result.accepted) {
                    remember(preferences, key, currentEvent,
                            GroceryMoneyManagerBridge.sourceRecordIdFor(item),
                            item.purchaseCount, true);
                }
                // If MoneyManager is absent/locked/signed differently, do not
                // mark it sent. A later app start/table change can retry safely.
                continue;
            }

            if (!previousEvent.isEmpty()) {
                boolean explicitUndo = item.purchaseCount < previousCount;
                if (explicitUndo && previousSent && !previousSource.isEmpty()) {
                    GroceryMoneyManagerBridge.cancelPurchase(
                            context, previousEvent, previousSource);
                }
                // Monthly reset keeps purchaseCount unchanged and therefore does
                // not cancel the previous month's legitimate expense.
                clearRemembered(preferences, key);
            }
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
