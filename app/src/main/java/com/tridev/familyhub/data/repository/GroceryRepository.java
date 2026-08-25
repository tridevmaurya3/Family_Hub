package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.GroceryItemDao;
import com.tridev.familyhub.data.local.dao.FinanceEntryDao;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetProvider;
import com.tridev.familyhub.feature.grocery.GroceryMoneyManagerBridge;
import com.tridev.familyhub.feature.grocery.GroceryNotificationHelper;
import com.tridev.familyhub.feature.grocery.GroceryRecurrenceEngine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline-first Room cache backed by the family's private Firebase list. */
public class GroceryRepository {

    public interface ItemsCallback {
        void onItemsLoaded(@NonNull List<GroceryItem> items);
    }

    public interface ActionCallback {
        void onComplete();
    }

    public interface PurchaseHistoryCallback {
        void onLoaded(@Nullable GroceryPurchase purchase);
    }
    public interface PurchasesCallback {
        void onLoaded(@NonNull List<GroceryPurchase> purchases);
    }
    public interface StoreComparisonCallback {
        void onLoaded(@Nullable GroceryPurchase latest,
                      @Nullable GroceryPurchase cheapest);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();
    // UI reads must not wait behind Firebase uploads, snapshot merges, purchase
    // writes or cross-app reconciliation queued on DATABASE_EXECUTOR.
    private static final ExecutorService LOAD_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final ExecutorService MAINTENANCE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final GroceryItemDao groceryItemDao;
    private final FinanceEntryDao financeEntryDao;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FamilyAccountRepository accountRepository;
    private final DatabaseReference firebaseRoot;

    @Nullable private DatabaseReference activeItemsReference;
    @Nullable private ValueEventListener activeListener;
    @Nullable private Runnable changeCallback;
    @Nullable private Set<String> lastRemoteItemIds;
    @NonNull private String activeFamilyId = "";
    private final AtomicBoolean financeReconciliationScheduled =
            new AtomicBoolean(false);
    private final Set<String> recoveredSyncScheduled = new HashSet<>();

    public GroceryRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        groceryItemDao = FamilyHubDatabase.getInstance(appContext)
                .groceryItemDao();
        financeEntryDao = FamilyHubDatabase.getInstance(appContext)
                .financeEntryDao();
        accountRepository = new FamilyAccountRepository();
        firebaseRoot = FirebaseDatabase.getInstance().getReference();
    }

    /**
     * Repairs Grocery purchase links from another repository boundary, such as
     * the Finance screen. Call only from a background thread. Existing
     * financeEntryId/cloudId identities are reused, so the operation is idempotent.
     */
    public static void reconcileFinanceLinksNow(@NonNull Context context) {
        GroceryRepository repository = new GroceryRepository(context);
        repository.reconcileFinanceLinksInternal();
    }

    /** Starts one family-scoped realtime listener; safe to call repeatedly. */
    public void startRealtimeSync(@NonNull Runnable onChanged) {
        stopListenerOnly();
        changeCallback = onChanged;
        accountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<
                        FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState state
                    ) {
                        if (state == null || !state.isActive()
                                || state.familyId == null) {
                            return;
                        }
                        attachListener(state.familyId);
                        uploadLocalOnlyItems(state.familyId);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        // Room remains fully usable while account/network is unavailable.
                    }
                }
        );
    }

    public void stopRealtimeSync() {
        if (activeItemsReference != null && activeListener != null) {
            activeItemsReference.removeEventListener(activeListener);
        }
        activeItemsReference = null;
        activeListener = null;
        changeCallback = null;
        activeFamilyId = "";
    }

    public void loadItems(
            @NonNull String query,
            @NonNull ItemsCallback callback
    ) {
        LOAD_EXECUTOR.execute(() -> {
            resetMonthlyMastersIfNeeded();
            String trimmedQuery = query.trim();
            List<GroceryItem> all = groceryItemDao.getAll();
            repairRecurringIdentity(all);
            List<GroceryItem> restoredHistory = restoreMissingPurchaseHistory(all);
            all.addAll(restoredHistory);
            List<GroceryItem> items;
            if (trimmedQuery.isEmpty()) {
                items = all;
            } else {
                items = groceryItemDao.search(trimmedQuery);
                for (GroceryItem history : restoredHistory) {
                    if (matchesHistoryQuery(history, trimmedQuery)) items.add(history);
                }
            }
            annotateRecurrence(items, all, System.currentTimeMillis());
            // Render Room/history results first. Expensive cross-app reconciliation
            // and recovered cloud uploads must never sit ahead of the UI callback.
            mainHandler.post(() -> {
                callback.onItemsLoaded(items);
                scheduleFinanceReconciliation();
                syncRecoveredHistoryBatch(restoredHistory);
            });
        });
    }

    private void scheduleFinanceReconciliation() {
        if (!financeReconciliationScheduled.compareAndSet(false, true)) return;
        MAINTENANCE_EXECUTOR.execute(this::reconcileFinanceLinksInternal);
    }

    private void syncRecoveredHistoryBatch(
            @NonNull List<GroceryItem> recoveredItems) {
        if (recoveredItems.isEmpty()) return;
        List<GroceryItem> pending = new java.util.ArrayList<>();
        synchronized (recoveredSyncScheduled) {
            for (GroceryItem item : recoveredItems) {
                if (item.cloudId.isEmpty()
                        || !recoveredSyncScheduled.add(item.cloudId)) {
                    continue;
                }
                pending.add(item);
            }
        }
        if (pending.isEmpty()) return;
        accountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<
                        FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState state) {
                        if (state == null || !state.isActive()
                                || state.familyId == null) return;
                        String familyId = state.familyId;
                        activeFamilyId = familyId;
                        for (GroceryItem item : pending) {
                            item.familyId = familyId;
                            firebaseRoot.child("sharedShopping").child(familyId)
                                    .child("items").child(item.cloudId)
                                    .updateChildren(toCloudValues(item));
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        synchronized (recoveredSyncScheduled) {
                            for (GroceryItem item : pending) {
                                recoveredSyncScheduled.remove(item.cloudId);
                            }
                        }
                    }
                });
    }

    public void save(
            @NonNull GroceryItem item,
            @NonNull ActionCallback callback
    ) {
        if (item.historyOnly) {
            saveRecoveredPurchase(item, callback);
            return;
        }
        long now = System.currentTimeMillis();
        if (!item.isPurchased) {
            recoveredHistoryPreferences().edit()
                    .remove(deletedMasterKey(item.name)).apply();
        }
        if (item.createdAt == 0L) {
            item.createdAt = now;
        }
        item.updatedAt = now;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        item.updatedByUid = user == null ? "" : user.getUid();
        item.updatedByName = displayName(user);
        if (item.cloudId.isEmpty()) {
            item.cloudId = UUID.randomUUID().toString();
        }

        DATABASE_EXECUTOR.execute(() -> {
            if (item.id == 0L) {
                GroceryItem duplicate = groceryItemDao.findDuplicate(item.name);
                if (duplicate != null) {
                    item.duplicateMerged = true;
                    item.id = duplicate.id;
                    item.cloudId = duplicate.cloudId;
                    item.familyId = duplicate.familyId;
                    item.createdAt = duplicate.createdAt;
                    item.purchaseCount = duplicate.purchaseCount;
                    // A new shopping cycle must not remove the previous expense.
                    item.financeEntryId = 0L;
                    item.isPurchased = false;
                    item.purchasedAt = 0L;
                    item.buyingStatus = GroceryItem.STATUS_PENDING;
                }
            }
            linkFinance(item);
            upsertLocal(item);
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                syncItem(item);
            });
        });
    }

    /**
     * Saves edits made from the Purchased list and keeps the immutable purchase
     * history date aligned with the visible purchased occurrence.
     */
    public void savePurchasedEdit(
            @NonNull GroceryItem item,
            long originalPurchasedAt,
            long selectedPurchasedAt,
            @NonNull ActionCallback callback
    ) {
        long normalizedPurchasedAt = normalizePurchaseTimestamp(selectedPurchasedAt);
        item.purchasedAt = normalizedPurchasedAt;
        save(item, () -> DATABASE_EXECUTOR.execute(() -> {
            FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                    .updateMatchingPurchaseDate(item.name, originalPurchasedAt,
                            normalizedPurchasedAt);
            refreshRecurringAnchorFromHistory(item.name);
            mainHandler.post(callback::onComplete);
        }));
    }

    private void refreshRecurringAnchorFromHistory(@NonNull String itemName) {
        GroceryItem master = groceryItemDao.findRecurringMaster(itemName);
        if (master == null) return;
        GroceryPurchase latest = FamilyHubDatabase.getInstance(appContext)
                .groceryPurchaseDao().getLatestForItem(itemName);
        if (latest == null || latest.purchasedAt <= 0L) return;
        master.createdAt = latest.purchasedAt;
        master.purchasedAt = latest.purchasedAt;
        master.updatedAt = System.currentTimeMillis();
        markCurrentEditor(master);
        groceryItemDao.update(master);
        mainHandler.post(() -> syncItem(master));
    }

    public void setPurchased(
            @NonNull GroceryItem item,
            boolean purchased,
            @NonNull ActionCallback callback
    ) {
        setPurchased(item, purchased, System.currentTimeMillis(), callback);
    }

    public void setPurchased(
            @NonNull GroceryItem item,
            boolean purchased,
            long requestedPurchasedAt,
            @NonNull ActionCallback callback
    ) {
        long purchasedAt = normalizePurchaseTimestamp(requestedPurchasedAt);
        String originalCycle = GroceryRecurrenceEngine.originalCycle(item);
        if (purchased && !item.isPurchased
                && GroceryRecurrenceEngine.isRecurringType(originalCycle)
                && !GroceryItem.LIST_DAILY.equals(item.listType)) {
            purchaseRecurringMaster(item, originalCycle, purchasedAt, callback);
            return;
        }
        boolean recordPurchase = purchased && !item.isPurchased;
        item.isPurchased = purchased;
        item.purchasedAt = purchased ? purchasedAt : 0L;
        item.buyingStatus = purchased
                ? GroceryItem.STATUS_PURCHASED : GroceryItem.STATUS_PENDING;
        if (purchased) {
            item.purchaseCount++;
            if (item.actualCost <= 0D) {
                item.actualCost = item.estimatedCost;
            }
        }
        item.purchasedByName = purchased
                ? displayName(FirebaseAuth.getInstance().getCurrentUser())
                : "";
        if (recordPurchase) {
            GroceryPurchase purchase = new GroceryPurchase();
            purchase.sourceItemId = item.id;
            purchase.itemName = item.name;
            purchase.category = item.category;
            purchase.quantity = item.quantity;
            purchase.storeName = item.storeName;
            purchase.actualCost = item.actualCost > 0D
                    ? item.actualCost : item.estimatedCost;
            purchase.purchasedAt = item.purchasedAt;
            DATABASE_EXECUTOR.execute(() -> FamilyHubDatabase
                    .getInstance(appContext).groceryPurchaseDao()
                    .insert(purchase));
        }
        ActionCallback completion = callback;
        if (recordPurchase && GroceryRecurrenceEngine.isRecurringType(originalCycle)) {
            completion = () -> resetMasterAnchor(item.name, item.purchasedAt, callback);
        }
        save(item, completion);
    }

    public void undoPurchase(
            @NonNull GroceryItem item,
            @NonNull ActionCallback callback
    ) {
        if (!item.lastPurchaseOccurrenceCloudId.isEmpty()) {
            undoRecurringMasterPurchase(item, callback);
            return;
        }
        long completedAt = item.purchasedAt;
        DATABASE_EXECUTOR.execute(() -> {
            FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                    .deletePurchase(item.id, completedAt);
            item.isPurchased = false;
            item.purchasedAt = 0L;
            item.buyingStatus = GroceryItem.STATUS_PENDING;
            item.purchasedByName = "";
            if (item.purchaseCount > 0) item.purchaseCount--;
            linkFinance(item);
            item.updatedAt = System.currentTimeMillis();
            markCurrentEditor(item);
            upsertLocal(item);
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                syncItem(item);
            });
        });
    }

    public void setBuyingStatus(
            @NonNull GroceryItem item,
            @NonNull String status,
            @NonNull ActionCallback callback
    ) {
        item.buyingStatus = status;
        if (GroceryItem.STATUS_PURCHASED.equals(status)) {
            setPurchased(item, true, callback);
        } else {
            save(item, callback);
        }
    }

    public void loadSuggestions(@NonNull ItemsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<GroceryItem> items = groceryItemDao.getRecurringSuggestions(8);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadLatestPurchase(
            @NonNull String itemName,
            @NonNull PurchaseHistoryCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            GroceryPurchase purchase = FamilyHubDatabase.getInstance(appContext)
                    .groceryPurchaseDao().getLatestForItem(itemName.trim());
            mainHandler.post(() -> callback.onLoaded(purchase));
        });
    }

    public void loadStoreComparison(
            @NonNull String itemName,
            @NonNull String quantity,
            @NonNull StoreComparisonCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            com.tridev.familyhub.data.local.dao.GroceryPurchaseDao dao =
                    FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao();
            GroceryPurchase latest = dao.getLatestForItem(itemName.trim());
            String comparableQuantity = quantity.trim();
            if (comparableQuantity.isEmpty() && latest != null) {
                comparableQuantity = latest.quantity;
            }
            GroceryPurchase cheapest = dao.getCheapestStoreForItem(
                    itemName.trim(), comparableQuantity);
            mainHandler.post(() -> callback.onLoaded(latest, cheapest));
        });
    }

    public void estimatePrice(
            @NonNull String name,
            @NonNull String locationKey,
            @NonNull PriceCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            GroceryItem history = locationKey.isEmpty() ? null
                    : groceryItemDao.findLocalPrice(name.trim(), locationKey);
            int confidence = history == null ? 0 : 90;
            if (history == null) {
                history = groceryItemDao.findAnyPrice(name.trim());
                confidence = history == null ? 0 : 60;
            }
            GroceryItem result = history;
            int resultConfidence = confidence;
            mainHandler.post(() -> callback.onPrice(
                    result == null ? 0D : result.actualCost,
                    resultConfidence
            ));
        });
    }

    public interface PriceCallback {
        void onPrice(double amount, int confidence);
    }

    public double getMonthlyBudget() {
        return Double.longBitsToDouble(appContext.getSharedPreferences(
                "grocery_budget", Context.MODE_PRIVATE
        ).getLong(currentMonth(), Double.doubleToRawLongBits(0D)));
    }

    public void setMonthlyBudget(double budget) {
        appContext.getSharedPreferences("grocery_budget", Context.MODE_PRIVATE)
                .edit().putLong(currentMonth(),
                        Double.doubleToRawLongBits(Math.max(0D, budget))).apply();
    }

    public double getCategoryBudget(@NonNull String category) {
        return Double.longBitsToDouble(appContext.getSharedPreferences(
                "grocery_category_budgets", Context.MODE_PRIVATE)
                .getLong(currentMonth() + "|" + category.toLowerCase(Locale.ENGLISH),
                        Double.doubleToRawLongBits(0D)));
    }

    public void setCategoryBudget(@NonNull String category, double budget) {
        appContext.getSharedPreferences("grocery_category_budgets",
                Context.MODE_PRIVATE).edit().putLong(
                currentMonth() + "|" + category.toLowerCase(Locale.ENGLISH),
                Double.doubleToRawLongBits(Math.max(0D, budget))).apply();
    }

    public void loadCurrentMonthPurchases(@NonNull PurchasesCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            java.util.Calendar start = java.util.Calendar.getInstance();
            start.set(java.util.Calendar.DAY_OF_MONTH, 1);
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);
            java.util.Calendar end = (java.util.Calendar) start.clone();
            end.add(java.util.Calendar.MONTH, 1);
            List<GroceryPurchase> purchases = FamilyHubDatabase
                    .getInstance(appContext).groceryPurchaseDao()
                    .getForPeriod(start.getTimeInMillis(), end.getTimeInMillis());
            mainHandler.post(() -> callback.onLoaded(purchases));
        });
    }

    public void delete(
            @NonNull GroceryItem item,
            @NonNull ActionCallback callback
    ) {
        if (item.historyOnly) {
            DATABASE_EXECUTOR.execute(() -> {
                long historyId = recoveredHistoryId(item);
                if (historyId > 0L) {
                    FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                            .deleteById(historyId);
                    recoveredHistoryPreferences().edit()
                            .remove("cycle_" + historyId).apply();
                }
                mainHandler.post(() -> {
                    callback.onComplete();
                    removeRemoteItem(item);
                });
            });
            return;
        }
        String cloudId = item.cloudId;
        DATABASE_EXECUTOR.execute(() -> {
            if (!item.isPurchased && GroceryRecurrenceEngine.isRecurringType(
                    GroceryRecurrenceEngine.originalCycle(item))) {
                recoveredHistoryPreferences().edit()
                        .putBoolean(deletedMasterKey(item.name), true).apply();
            }
            if (item.isPurchased && item.purchasedAt > 0L) {
                FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                        .deleteMatchingPurchase(item.name, item.purchasedAt);
            }
            groceryItemDao.delete(item);
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                removeRemoteItem(item);
            });
        });
    }

    public void clearPurchased(@NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<GroceryItem> all = groceryItemDao.getAll();
            for (GroceryItem item : all) {
                if (!item.isPurchased) {
                    continue;
                }
                if (item.purchasedAt > 0L) {
                    FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                            .deleteMatchingPurchase(item.name, item.purchasedAt);
                }
                groceryItemDao.delete(item);
                mainHandler.post(() -> removeRemoteItem(item));
            }
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(callback::onComplete);
        });
    }

    private void attachListener(@NonNull String familyId) {
        if (familyId.equals(activeFamilyId) && activeListener != null) {
            return;
        }
        stopListenerOnly();
        activeFamilyId = familyId;
        activeItemsReference = firebaseRoot.child("sharedShopping")
                .child(familyId).child("items");
        activeItemsReference.keepSynced(true);
        activeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                DATABASE_EXECUTOR.execute(() -> {
                    Set<String> remoteIds = new HashSet<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        GroceryItem remote = fromSnapshot(child, familyId);
                        if (remote == null) {
                            continue;
                        }
                        remoteIds.add(remote.cloudId);
                        GroceryItem local = groceryItemDao.getByCloudId(
                                remote.cloudId);
                        if (local == null || remote.updatedAt >= local.updatedAt) {
                            boolean statusChanged = local != null
                                    && !remote.buyingStatus.equals(local.buyingStatus);
                            boolean assignmentChanged = local != null
                                    && !remote.assignedMemberId.equals(local.assignedMemberId);
                            FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
                            boolean fromAnotherMember = current == null
                                    || !remote.updatedByUid.equals(current.getUid());
                            if (local != null) {
                                remote.id = local.id;
                                remote.financeEntryId = local.financeEntryId;
                            }
                            linkFinance(remote);
                            upsertLocal(remote);
                            if (fromAnotherMember && (statusChanged
                                    || assignmentChanged
                                    || (local == null
                                    && !remote.assignedMemberId.isEmpty()))) {
                                mainHandler.post(() -> GroceryNotificationHelper
                                        .notifyUpdate(appContext, remote));
                            }
                        }
                    }
                    // Never treat the first remote snapshot as an authoritative
                    // delete list. A previously rejected/offline upload can be absent
                    // remotely while still being valid locally. Only remove an item
                    // after this listener has actually observed that same cloud id
                    // in an earlier successful snapshot and it later disappears.
                    Set<String> previousRemoteIds = lastRemoteItemIds;
                    if (previousRemoteIds != null) {
                        for (GroceryItem local : groceryItemDao.getAllSynced()) {
                            if (familyId.equals(local.familyId)
                                    && previousRemoteIds.contains(local.cloudId)
                                    && !remoteIds.contains(local.cloudId)) {
                                groceryItemDao.delete(local);
                            }
                        }
                    }
                    lastRemoteItemIds = new HashSet<>(remoteIds);
                    GroceryWidgetProvider.refreshAll(appContext);
                    Runnable callback = changeCallback;
                    if (callback != null) {
                        mainHandler.post(callback);
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Local data stays available and Firebase will retry later.
            }
        };
        activeItemsReference.addValueEventListener(activeListener);
    }

    private void stopListenerOnly() {
        if (activeItemsReference != null && activeListener != null) {
            activeItemsReference.removeEventListener(activeListener);
        }
        activeItemsReference = null;
        activeListener = null;
        lastRemoteItemIds = null;
    }

    private void uploadLocalOnlyItems(@NonNull String familyId) {
        DATABASE_EXECUTOR.execute(() -> {
            for (GroceryItem item : groceryItemDao.getAll()) {
                if (!item.familyId.isEmpty() && !familyId.equals(item.familyId)) {
                    continue;
                }
                // Retry every family-owned row with its stable cloud id. This is
                // idempotent and repairs records whose earlier Firebase write was
                // rejected or interrupted; previously such rows were skipped forever.
                if (item.cloudId.isEmpty()) {
                    item.cloudId = UUID.randomUUID().toString();
                }
                item.familyId = familyId;
                if (item.updatedAt == 0L) {
                    item.updatedAt = Math.max(item.createdAt,
                            System.currentTimeMillis());
                }
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null) {
                    item.updatedByUid = user.getUid();
                    item.updatedByName = displayName(user);
                }
                upsertLocal(item);
                firebaseRoot.child("sharedShopping").child(familyId)
                        .child("items").child(item.cloudId)
                        .updateChildren(toCloudValues(item));
            }
        });
    }

    private void syncItem(@NonNull GroceryItem item) {
        if (!activeFamilyId.isEmpty()) {
            item.familyId = activeFamilyId;
            DATABASE_EXECUTOR.execute(() -> upsertLocal(item));
            firebaseRoot.child("sharedShopping").child(activeFamilyId)
                    .child("items").child(item.cloudId)
                    .updateChildren(toCloudValues(item));
            return;
        }
        startRealtimeSync(() -> { });
    }

    private void upsertLocal(@NonNull GroceryItem item) {
        if (item.id == 0L) {
            GroceryItem matching = item.cloudId.isEmpty()
                    ? null : groceryItemDao.getByCloudId(item.cloudId);
            if (matching != null) {
                item.id = matching.id;
            }
        }
        if (item.id == 0L) {
            item.id = groceryItemDao.insert(item);
        } else {
            groceryItemDao.update(item);
        }
    }

    @NonNull
    private Map<String, Object> toCloudValues(@NonNull GroceryItem item) {
        Map<String, Object> values = new HashMap<>();
        values.put("cloudId", item.cloudId);
        values.put("familyId", item.familyId);
        values.put("name", item.name);
        values.put("category", item.category);
        values.put("quantity", item.quantity);
        values.put("estimatedCost", item.estimatedCost);
        values.put("actualCost", item.actualCost);
        values.put("storeName", item.storeName);
        values.put("autoPriceEnabled", item.autoPriceEnabled);
        values.put("priceLocationKey", item.priceLocationKey);
        values.put("priceConfidence", item.priceConfidence);
        values.put("priority", item.priority);
        values.put("purchased", item.isPurchased);
        values.put("buyingStatus", item.buyingStatus);
        values.put("isMonthlyMaster", item.isMonthlyMaster);
        values.put("lastResetMonth", item.lastResetMonth);
        values.put("purchaseCount", item.purchaseCount);
        values.put("notes", item.notes);
        values.put("listType", item.listType);
        values.put("assignedMemberId", item.assignedMemberId);
        values.put("assignedMemberName", item.assignedMemberName);
        values.put("purchasedByName", item.purchasedByName);
        values.put("createdAt", item.createdAt);
        values.put("purchasedAt", item.purchasedAt);
        values.put("updatedAt", item.updatedAt);
        values.put("updatedByUid", item.updatedByUid);
        values.put("updatedByName", item.updatedByName);
        values.put("serverUpdatedAt", ServerValue.TIMESTAMP);
        return values;
    }

    @Nullable
    private GroceryItem fromSnapshot(
            @NonNull DataSnapshot snapshot,
            @NonNull String familyId
    ) {
        String cloudId = stringValue(snapshot.child("cloudId"));
        String name = stringValue(snapshot.child("name"));
        if (cloudId.isEmpty() || name.isEmpty()) {
            return null;
        }
        GroceryItem item = new GroceryItem();
        item.cloudId = cloudId;
        item.familyId = familyId;
        item.name = name;
        item.category = stringValue(snapshot.child("category"));
        item.quantity = stringValue(snapshot.child("quantity"));
        item.estimatedCost = doubleValue(snapshot.child("estimatedCost"));
        item.actualCost = doubleValue(snapshot.child("actualCost"));
        item.storeName = stringValue(snapshot.child("storeName"));
        item.autoPriceEnabled = booleanValue(snapshot.child("autoPriceEnabled"), true);
        item.priceLocationKey = stringValue(snapshot.child("priceLocationKey"));
        item.priceConfidence = intValue(snapshot.child("priceConfidence"));
        item.priority = stringValue(snapshot.child("priority"));
        if (item.priority.isEmpty()) {
            item.priority = GroceryItem.PRIORITY_NORMAL;
        }
        Boolean purchased = snapshot.child("purchased").getValue(Boolean.class);
        item.isPurchased = Boolean.TRUE.equals(purchased);
        item.buyingStatus = stringValue(snapshot.child("buyingStatus"));
        if (item.buyingStatus.isEmpty()) {
            item.buyingStatus = item.isPurchased
                    ? GroceryItem.STATUS_PURCHASED : GroceryItem.STATUS_PENDING;
        }
        item.isMonthlyMaster = booleanValue(snapshot.child("isMonthlyMaster"), false);
        item.lastResetMonth = stringValue(snapshot.child("lastResetMonth"));
        item.purchaseCount = intValue(snapshot.child("purchaseCount"));
        item.notes = stringValue(snapshot.child("notes"));
        item.listType = stringValue(snapshot.child("listType"));
        if (item.listType.isEmpty()) {
            item.listType = GroceryItem.LIST_DAILY;
        }
        item.assignedMemberId = stringValue(
                snapshot.child("assignedMemberId")
        );
        item.assignedMemberName = stringValue(
                snapshot.child("assignedMemberName")
        );
        item.purchasedByName = stringValue(
                snapshot.child("purchasedByName")
        );
        item.createdAt = longValue(snapshot.child("createdAt"));
        item.purchasedAt = longValue(snapshot.child("purchasedAt"));
        item.updatedAt = longValue(snapshot.child("updatedAt"));
        item.updatedByUid = stringValue(snapshot.child("updatedByUid"));
        item.updatedByName = stringValue(snapshot.child("updatedByName"));
        return item;
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value;
    }

    private static long longValue(@NonNull DataSnapshot snapshot) {
        Long value = snapshot.getValue(Long.class);
        return value == null ? 0L : value;
    }

    private static double doubleValue(@NonNull DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return 0D;
            }
        }
        return 0D;
    }

    private static int intValue(@NonNull DataSnapshot snapshot) {
        return (int) longValue(snapshot);
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }

    /**
     * Completes a recurring master through a purchased history occurrence, while
     * keeping the same master row as the one active occurrence for its next cycle.
     */
    private void purchaseRecurringMaster(@NonNull GroceryItem master,
                                         @NonNull String originalCycle,
                                         long purchasedAt,
                                         @NonNull ActionCallback callback) {
        long updatedAt = System.currentTimeMillis();
        GroceryItem purchase = copyForPurchase(master);
        purchase.id = 0L;
        purchase.cloudId = "recurrence-purchase-" + UUID.randomUUID();
        // Persist purchase history in its original recurring list. The row is
        // purchased/immutable, while the separate master remains the only pending
        // occurrence. Daily purchases never enter this recurring-only method.
        purchase.listType = GroceryRecurrenceEngine.normalizeCycle(originalCycle);
        purchase.isMonthlyMaster = false;
        purchase.lastResetMonth = GroceryRecurrenceEngine.occurrenceMetadata(originalCycle);
        purchase.createdAt = purchasedAt;
        purchase.purchasedAt = purchasedAt;
        purchase.updatedAt = updatedAt;
        purchase.isPurchased = true;
        purchase.buyingStatus = GroceryItem.STATUS_PURCHASED;
        purchase.purchaseCount = master.purchaseCount + 1;
        purchase.financeEntryId = 0L;
        purchase.purchasedByName = displayName(FirebaseAuth.getInstance().getCurrentUser());
        if (purchase.actualCost <= 0D) purchase.actualCost = purchase.estimatedCost;

        master.previousRecurrenceAnchorAt = master.purchasedAt > 0L
                ? master.purchasedAt : master.createdAt;
        master.createdAt = purchasedAt;
        master.purchasedAt = purchasedAt; // preserved Last Purchase and new anchor
        master.listType = GroceryRecurrenceEngine.normalizeCycle(originalCycle);
        master.lastResetMonth = GroceryRecurrenceEngine.occurrenceMetadata(originalCycle);
        master.purchaseCount++;
        master.isPurchased = false;
        master.buyingStatus = GroceryItem.STATUS_PENDING;
        master.purchasedByName = "";
        master.actualCost = 0D;
        master.financeEntryId = 0L;
        master.updatedAt = updatedAt;
        markCurrentEditor(master);

        DATABASE_EXECUTOR.execute(() -> {
            GroceryPurchase history = new GroceryPurchase();
            history.sourceItemId = master.id;
            history.itemName = purchase.name;
            history.category = purchase.category;
            history.quantity = purchase.quantity;
            history.storeName = purchase.storeName;
            history.actualCost = purchase.actualCost;
            history.purchasedAt = purchasedAt;
            FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao().insert(history);
            linkFinance(purchase);
            upsertLocal(purchase);
            // The recurring master remains pending, so send the immutable
            // purchased occurrence to MoneyManager. Carry over the exact routing
            // selected in the purchase editor before using the same bridge as a
            // normal Daily purchase.
            GroceryMoneyManagerBridge.rememberNextPurchaseSelections(
                    appContext,
                    purchase,
                    GroceryMoneyManagerBridge.selectedAccountRef(appContext, master),
                    GroceryMoneyManagerBridge.selectedCategoryRef(appContext, master)
            );
            GroceryMoneyManagerBridge.sendPurchase(appContext, purchase);
            upsertLocal(master);
            master.lastPurchaseOccurrenceCloudId = purchase.cloudId;
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                syncItem(purchase);
                syncItem(master);
            });
        });
    }

    @NonNull
    private static GroceryItem copyForPurchase(@NonNull GroceryItem source) {
        GroceryItem target = new GroceryItem();
        target.name = source.name; target.category = source.category;
        target.quantity = source.quantity; target.estimatedCost = source.estimatedCost;
        target.actualCost = source.actualCost; target.storeName = source.storeName;
        target.autoPriceEnabled = source.autoPriceEnabled;
        target.priceLocationKey = source.priceLocationKey;
        target.priceConfidence = source.priceConfidence; target.priority = source.priority;
        target.notes = source.notes; target.assignedMemberId = source.assignedMemberId;
        target.assignedMemberName = source.assignedMemberName; target.familyId = source.familyId;
        target.updatedByUid = source.updatedByUid; target.updatedByName = source.updatedByName;
        return target;
    }

    private void resetMasterAnchor(@NonNull String name, long purchasedAt,
                                   @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            GroceryItem master = groceryItemDao.findRecurringMaster(name);
            if (master != null) {
                String originalCycle = GroceryRecurrenceEngine.originalCycle(master);
                master.createdAt = purchasedAt;
                master.purchasedAt = purchasedAt;
                if (GroceryRecurrenceEngine.isRecurringType(originalCycle)) {
                    master.listType = GroceryRecurrenceEngine.normalizeCycle(originalCycle);
                    master.lastResetMonth =
                            GroceryRecurrenceEngine.occurrenceMetadata(originalCycle);
                }
                master.isPurchased = false;
                master.buyingStatus = GroceryItem.STATUS_PENDING;
                master.purchasedByName = "";
                master.financeEntryId = 0L;
                master.updatedAt = purchasedAt;
                markCurrentEditor(master);
                groceryItemDao.update(master);
                mainHandler.post(() -> syncItem(master));
            }
            mainHandler.post(callback::onComplete);
        });
    }

    @NonNull
    private List<GroceryItem> restoreMissingPurchaseHistory(
            @NonNull List<GroceryItem> persistedItems) {
        Set<String> existingPurchases = new HashSet<>();
        Map<Long, GroceryItem> itemById = new HashMap<>();
        Map<String, String> originByName = new HashMap<>();
        Map<String, String> recoveredOriginByName = new HashMap<>();
        Map<String, GroceryPurchase> latestHistoryByName = new HashMap<>();
        for (GroceryItem item : persistedItems) {
            itemById.put(item.id, item);
            String key = item.name.trim().toLowerCase(Locale.ENGLISH);
            String origin = GroceryRecurrenceEngine.originalCycle(item);
            if (GroceryRecurrenceEngine.isRecurringType(origin)) {
                originByName.put(key, origin);
            }
            if (item.isPurchased && item.purchasedAt > 0L) {
                existingPurchases.add(purchaseHistoryKey(item.name, item.purchasedAt));
            }
        }

        List<GroceryItem> restored = new java.util.ArrayList<>();
        List<GroceryPurchase> purchases = FamilyHubDatabase.getInstance(appContext)
                .groceryPurchaseDao().getAll();
        for (GroceryPurchase history : purchases) {
            String historyKey = purchaseHistoryKey(
                    history.itemName, history.purchasedAt);
            if (existingPurchases.contains(historyKey)) continue;

            GroceryItem master = itemById.get(history.sourceItemId);
            String nameKey = history.itemName.trim().toLowerCase(Locale.ENGLISH);
            String storedOrigin = recoveredHistoryPreferences().getString(
                    "cycle_" + history.id, "");
            String inferredOrigin = master == null
                    ? originByName.getOrDefault(nameKey, "")
                    : GroceryRecurrenceEngine.originalCycle(master);
            String origin = GroceryRecurrenceEngine.isRecurringType(storedOrigin)
                    ? storedOrigin
                    : GroceryRecurrenceEngine.isRecurringType(inferredOrigin)
                    ? inferredOrigin : legacyRecoveredCycle(history.itemName);
            origin = GroceryRecurrenceEngine.normalizeCycle(origin);
            if (GroceryRecurrenceEngine.isRecurringType(origin)) {
                recoveredOriginByName.put(nameKey, origin);
                GroceryPurchase previous = latestHistoryByName.get(nameKey);
                if (previous == null || history.purchasedAt > previous.purchasedAt) {
                    latestHistoryByName.put(nameKey, history);
                }
            }

            GroceryItem row = new GroceryItem();
            row.id = -Math.max(1L, history.id);
            row.name = history.itemName;
            row.category = history.category;
            row.quantity = history.quantity;
            row.storeName = history.storeName;
            row.actualCost = history.actualCost;
            row.estimatedCost = history.actualCost;
            row.priority = master == null
                    ? GroceryItem.PRIORITY_NORMAL : master.priority;
            row.listType = origin;
            row.lastResetMonth = GroceryRecurrenceEngine.isRecurringType(origin)
                    ? GroceryRecurrenceEngine.occurrenceMetadata(origin) : "";
            row.originalRecurringType = GroceryRecurrenceEngine.isRecurringType(origin)
                    ? origin : "";
            row.isPurchased = true;
            row.buyingStatus = GroceryItem.STATUS_PURCHASED;
            row.purchasedAt = history.purchasedAt;
            row.createdAt = history.purchasedAt;
            row.updatedAt = history.purchasedAt;
            row.purchasedByName = master == null
                    ? "" : master.updatedByName;
            row.cloudId = "recovered-purchase-" + history.id + "-"
                    + history.purchasedAt;
            row.familyId = activeFamilyId;
            markCurrentEditor(row);
            row.historyOnly = true;
            restored.add(row);
            existingPurchases.add(historyKey);
            // Upload is batched only after the UI has rendered. Do not enqueue one
            // realtime-session lookup per historical row here.
        }
        Set<String> pendingNames = new HashSet<>();
        for (GroceryItem item : persistedItems) {
            if (item.isPurchased) continue;
            String key = item.name.trim().toLowerCase(Locale.ENGLISH);
            String recoveredOrigin = recoveredOriginByName.get(key);
            if (!GroceryRecurrenceEngine.isRecurringType(recoveredOrigin)) continue;
            item.listType = recoveredOrigin;
            item.lastResetMonth =
                    GroceryRecurrenceEngine.occurrenceMetadata(recoveredOrigin);
            groceryItemDao.update(item);
            pendingNames.add(key);
        }
        for (Map.Entry<String, String> entry : recoveredOriginByName.entrySet()) {
            if (pendingNames.contains(entry.getKey())) continue;
            GroceryPurchase latest = latestHistoryByName.get(entry.getKey());
            if (latest != null && recoveredHistoryPreferences().getBoolean(
                    deletedMasterKey(latest.itemName), false)) continue;
            if (latest == null) continue;
            GroceryItem master = new GroceryItem();
            master.name = latest.itemName;
            master.category = latest.category;
            master.quantity = latest.quantity;
            master.storeName = latest.storeName;
            master.estimatedCost = latest.actualCost;
            master.actualCost = 0D;
            master.priority = GroceryItem.PRIORITY_NORMAL;
            master.listType = entry.getValue();
            master.lastResetMonth =
                    GroceryRecurrenceEngine.occurrenceMetadata(entry.getValue());
            master.isPurchased = false;
            master.buyingStatus = GroceryItem.STATUS_PENDING;
            master.createdAt = latest.purchasedAt;
            master.purchasedAt = latest.purchasedAt;
            master.updatedAt = System.currentTimeMillis();
            master.cloudId = UUID.randomUUID().toString();
            master.familyId = activeFamilyId;
            markCurrentEditor(master);
            upsertLocal(master);
            restored.add(master);
        }
        return restored;
    }

    private void saveRecoveredPurchase(
            @NonNull GroceryItem item, @NonNull ActionCallback callback) {
        long historyId = recoveredHistoryId(item);
        if (historyId <= 0L) {
            mainHandler.post(callback::onComplete);
            return;
        }
        DATABASE_EXECUTOR.execute(() -> {
            item.purchasedAt = normalizePurchaseTimestamp(item.purchasedAt);
            FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                    .updateRecovered(historyId, item.name, item.category,
                            item.quantity, item.storeName,
                            item.actualCost > 0D ? item.actualCost : item.estimatedCost,
                            item.purchasedAt);
            item.updatedAt = System.currentTimeMillis();
            markCurrentEditor(item);
            String cycle = GroceryRecurrenceEngine.originalCycle(item);
            recoveredHistoryPreferences().edit()
                    .putString("cycle_" + historyId,
                            GroceryRecurrenceEngine.normalizeCycle(cycle))
                    .apply();
            refreshRecurringAnchorFromHistory(item.name);
            mainHandler.post(() -> {
                callback.onComplete();
                syncItem(item);
            });
        });
    }

    private android.content.SharedPreferences recoveredHistoryPreferences() {
        return appContext.getSharedPreferences(
                "grocery_recovered_history_v1", Context.MODE_PRIVATE);
    }

    @NonNull
    private static String deletedMasterKey(@NonNull String itemName) {
        return "deleted_master_"
                + itemName.trim().toLowerCase(Locale.ENGLISH);
    }

    private void removeRemoteItem(@NonNull GroceryItem item) {
        if (item.cloudId == null || item.cloudId.isEmpty()) return;
        String familyId = !activeFamilyId.isEmpty()
                ? activeFamilyId
                : item.familyId == null ? "" : item.familyId.trim();
        if (!familyId.isEmpty()) {
            firebaseRoot.child("sharedShopping").child(familyId)
                    .child("items").child(item.cloudId).removeValue();
            return;
        }
        accountRepository.loadSession(
                new FamilyAccountRepository.ResultCallback<
                        FamilyAccountRepository.SessionState>() {
                    @Override
                    public void onSuccess(
                            @Nullable FamilyAccountRepository.SessionState state) {
                        if (state == null || !state.isActive()
                                || state.familyId == null) return;
                        firebaseRoot.child("sharedShopping")
                                .child(state.familyId).child("items")
                                .child(item.cloudId).removeValue();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        // Local delete remains complete; Firebase can be retried
                        // after the family session becomes available again.
                    }
                });
    }

    private static long recoveredHistoryId(@NonNull GroceryItem item) {
        return item.historyOnly && item.id < 0L ? -item.id : 0L;
    }

    @NonNull
    private static String legacyRecoveredCycle(@NonNull String itemName) {
        String key = itemName.trim().toLowerCase(Locale.ENGLISH);
        if (key.contains("cipcal")) return GroceryItem.LIST_MONTHLY;
        if (key.contains("mastro") || key.contains("pastro")
                || key.contains("horlick") || key.contains("harpic")
                || key.contains("हार्लिक") || key.contains("हार्पिक")) {
            return GroceryItem.LIST_TWO_MONTH;
        }
        if (key.contains("सरसों") || key.contains("sarson")) {
            return GroceryItem.LIST_THREE_MONTH;
        }
        return GroceryItem.LIST_DAILY;
    }

    private static boolean matchesHistoryQuery(
            @NonNull GroceryItem item, @NonNull String query) {
        String wanted = query.toLowerCase(Locale.ENGLISH);
        return item.name.toLowerCase(Locale.ENGLISH).contains(wanted)
                || item.category.toLowerCase(Locale.ENGLISH).contains(wanted)
                || item.quantity.toLowerCase(Locale.ENGLISH).contains(wanted)
                || item.storeName.toLowerCase(Locale.ENGLISH).contains(wanted);
    }

    @NonNull
    private static String purchaseHistoryKey(
            @NonNull String name, long purchasedAt) {
        return name.trim().toLowerCase(Locale.ENGLISH) + "|" + purchasedAt;
    }

    /**
     * Repairs rows created by older/editable list-type flows. Recurrence origin is
     * recovered from immutable purchased occurrences and persisted without a
     * schema migration, so both Overlay and Grocery filters see the same rows.
     */
    private void repairRecurringIdentity(@NonNull List<GroceryItem> all) {
        Map<String, String> originByName = new HashMap<>();
        Map<String, Long> originTimeByName = new HashMap<>();
        for (GroceryItem item : all) {
            String origin = GroceryRecurrenceEngine.originFromMetadata(item.lastResetMonth);
            if (!item.isPurchased || !GroceryRecurrenceEngine.isRecurringType(origin)) continue;
            String key = item.name.trim().toLowerCase(Locale.ENGLISH);
            long time = item.purchasedAt > 0L ? item.purchasedAt : item.updatedAt;
            if (time >= originTimeByName.getOrDefault(key, Long.MIN_VALUE)) {
                originByName.put(key, origin);
                originTimeByName.put(key, time);
            }
            if (!origin.equals(item.listType)) {
                item.listType = origin;
                groceryItemDao.update(item);
            }
        }
        for (GroceryItem item : all) {
            if (item.isPurchased) continue;
            String key = item.name.trim().toLowerCase(Locale.ENGLISH);
            String recovered = originByName.get(key);
            if (!GroceryRecurrenceEngine.isRecurringType(recovered)) continue;
            String currentOrigin = GroceryRecurrenceEngine.originalCycle(item);
            if (!GroceryRecurrenceEngine.isRecurringType(currentOrigin)
                    || !recovered.equals(currentOrigin)
                    || !recovered.equals(item.listType)) {
                item.listType = recovered;
                item.lastResetMonth = GroceryRecurrenceEngine.occurrenceMetadata(recovered);
                groceryItemDao.update(item);
            }
        }
    }

    public static void annotateRecurrence(@NonNull List<GroceryItem> visible,
                                           @NonNull List<GroceryItem> all,
                                           long now) {
        Map<String, GroceryItem> masters = new HashMap<>();
        Set<String> activeLegacyOccurrences = new HashSet<>();
        for (GroceryItem candidate : all) {
            // A completed history row can share the same name/listType, but it
            // must never replace the one active pending recurring master.
            if (!candidate.isPurchased
                    && GroceryRecurrenceEngine.isRecurringType(
                            GroceryRecurrenceEngine.originalCycle(candidate))) {
                masters.put(candidate.name.trim().toLowerCase(Locale.ENGLISH), candidate);
            }
        }
        for (GroceryItem candidate : all) {
            if (!candidate.isPurchased && GroceryItem.LIST_DAILY.equals(candidate.listType)
                    && candidate.cloudId.startsWith("recurrence-")) {
                String key = candidate.name.trim().toLowerCase(Locale.ENGLISH);
                if (masters.containsKey(key)) activeLegacyOccurrences.add(key);
            }
        }
        for (GroceryItem item : visible) {
            // These are @Ignore runtime fields. Recompute them from persisted
            // state on every load so Fragment and Overlay receive identical data.
            item.originalRecurringType = "";
            item.effectiveListType = "";
            item.recurrenceShadowed = false;
            String key = item.name.trim().toLowerCase(Locale.ENGLISH);
            GroceryItem master = masters.get(key);
            if (master != null && item.id != master.id
                    && GroceryItem.LIST_DAILY.equals(item.listType)) {
                item.originalRecurringType = master.listType;
            }
            if (master != null && item.id == master.id && activeLegacyOccurrences.contains(key)) {
                item.recurrenceShadowed = true;
            }
            item.effectiveListType = GroceryRecurrenceEngine.effectiveCycle(item, now);
        }
    }

    private void resetMonthlyMastersIfNeeded() {
        // Recurrence is purchase-date anchored and projected by GroceryRecurrenceEngine.
        // The former calendar-month reset erased Last Purchase and is intentionally retired.
    }

    private void undoRecurringMasterPurchase(@NonNull GroceryItem master,
                                             @NonNull ActionCallback callback) {
        String occurrenceCloudId = master.lastPurchaseOccurrenceCloudId;
        long completedAt = master.purchasedAt;
        DATABASE_EXECUTOR.execute(() -> {
            GroceryItem occurrence = groceryItemDao.getByCloudId(occurrenceCloudId);
            FamilyHubDatabase.getInstance(appContext).groceryPurchaseDao()
                    .deletePurchase(master.id, completedAt);
            if (occurrence != null) {
                occurrence.isPurchased = false;
                linkFinance(occurrence);
                groceryItemDao.delete(occurrence);
                if (!activeFamilyId.isEmpty()) {
                    firebaseRoot.child("sharedShopping").child(activeFamilyId)
                            .child("items").child(occurrenceCloudId).removeValue();
                }
            }
            long previous = master.previousRecurrenceAnchorAt;
            master.createdAt = previous > 0L ? previous : master.createdAt;
            master.purchasedAt = previous > 0L ? previous : 0L;
            if (master.purchaseCount > 0) master.purchaseCount--;
            master.updatedAt = System.currentTimeMillis();
            master.lastPurchaseOccurrenceCloudId = "";
            markCurrentEditor(master);
            groceryItemDao.update(master);
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                syncItem(master);
            });
        });
    }

    private void reconcileFinanceLinksInternal() {
        for (GroceryItem item : groceryItemDao.getAll()) {
            long previousFinanceEntryId = item.financeEntryId;
            linkFinance(item);
            if (previousFinanceEntryId != item.financeEntryId) {
                groceryItemDao.update(item);
            }
        }
    }

    @NonNull
    private String financeFamilyId(@NonNull GroceryItem item) {
        if (!activeFamilyId.isEmpty()) return activeFamilyId;
        return item.familyId == null ? "" : item.familyId.trim();
    }

    private void linkFinance(@NonNull GroceryItem item) {
        if (!item.isPurchased) {
            if (item.financeEntryId > 0L) {
                FinanceEntry linked = financeEntryDao.getById(item.financeEntryId);
                financeEntryDao.deleteById(item.financeEntryId);
                removeSharedFinance(linked);
                item.financeEntryId = 0L;
            }
            return;
        }
        double amount = item.actualCost > 0D
                ? item.actualCost : item.estimatedCost;
        if (amount <= 0D) {
            if (item.financeEntryId > 0L) {
                FinanceEntry linked = financeEntryDao.getById(item.financeEntryId);
                financeEntryDao.deleteById(item.financeEntryId);
                removeSharedFinance(linked);
                item.financeEntryId = 0L;
            }
            return;
        }
        if (item.financeEntryId > 0L) {
            FinanceEntry existing = financeEntryDao.getById(item.financeEntryId);
            if (existing != null) {
                existing.amount = amount;
                existing.note = financeNote(item);
                existing.transactionDate = purchaseDate(item);
                existing.updatedByName = financeActorName(item);
                existing.updatedAt = System.currentTimeMillis();
                financeEntryDao.update(existing);
                publishLinkedFinance(existing);
                return;
            }
            item.financeEntryId = 0L;
        }
        String linkedCloudId = linkedFinanceCloudId(item);
        if (!linkedCloudId.isEmpty()) {
            FinanceEntry existing = financeEntryDao.getByCloudId(linkedCloudId);
            if (existing != null) {
                item.financeEntryId = existing.id;
                existing.amount = amount;
                existing.note = financeNote(item);
                existing.transactionDate = purchaseDate(item);
                existing.updatedByName = financeActorName(item);
                existing.updatedAt = System.currentTimeMillis();
                financeEntryDao.update(existing);
                publishLinkedFinance(existing);
                return;
            }
        }
        FinanceEntry entry = new FinanceEntry();
        entry.entryType = FinanceEntry.TYPE_EXPENSE;
        entry.amount = amount;
        entry.category = "Grocery";
        entry.note = financeNote(item);
        entry.transactionDate = purchaseDate(item);
        entry.createdAt = System.currentTimeMillis();
        entry.updatedAt = entry.createdAt;
        if (!linkedCloudId.isEmpty()) {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            entry.isShared = true;
            entry.cloudId = linkedCloudId;
            entry.familyId = financeFamilyId(item);
            // Keep the signed-in writer UID for Firebase rules, but display the
            // member who actually completed/updated the Grocery item.
            entry.updatedByUid = user == null ? "" : user.getUid();
            entry.updatedByName = financeActorName(item);
        }
        item.financeEntryId = financeEntryDao.insert(entry);
        publishLinkedFinance(entry);
    }

    @NonNull
    private static String financeActorName(@NonNull GroceryItem item) {
        String purchaser = item.purchasedByName == null
                ? "" : item.purchasedByName.trim();
        if (!purchaser.isEmpty()) {
            return purchaser;
        }
        String editor = item.updatedByName == null
                ? "" : item.updatedByName.trim();
        return editor.isEmpty() ? "Family member" : editor;
    }

    @NonNull
    private String linkedFinanceCloudId(@NonNull GroceryItem item) {
        if (financeFamilyId(item).isEmpty() || item.cloudId.isEmpty()) return "";
        return "grocery_" + item.cloudId;
    }

    private void publishLinkedFinance(@NonNull FinanceEntry entry) {
        if (!entry.isShared || entry.familyId.isEmpty() || entry.cloudId.isEmpty()) return;
        Map<String, Object> values = new HashMap<>();
        values.put("cloudId", entry.cloudId); values.put("familyId", entry.familyId);
        values.put("entryType", entry.entryType); values.put("amount", entry.amount);
        values.put("category", entry.category); values.put("note", entry.note);
        values.put("transactionDate", entry.transactionDate);
        values.put("accountName", entry.accountName); values.put("paymentMethod", entry.paymentMethod);
        values.put("recurring", false); values.put("shared", true);
        values.put("createdAt", entry.createdAt); values.put("updatedAt", entry.updatedAt);
        values.put("serverUpdatedAt", ServerValue.TIMESTAMP);
        values.put("updatedByUid", entry.updatedByUid); values.put("updatedByName", entry.updatedByName);
        firebaseRoot.child("sharedModules").child(entry.familyId)
                .child("finance").child(entry.cloudId).setValue(values);
    }

    private void removeSharedFinance(@Nullable FinanceEntry entry) {
        if (entry == null || entry.familyId.isEmpty() || entry.cloudId.isEmpty()) return;
        firebaseRoot.child("sharedModules").child(entry.familyId)
                .child("finance").child(entry.cloudId).removeValue();
    }

    @NonNull
    private static String financeNote(@NonNull GroceryItem item) {
        return "[Grocery] " + item.name + (item.quantity.isEmpty()
                ? "" : " • " + item.quantity);
    }

    @NonNull
    private static String purchaseDate(@NonNull GroceryItem item) {
        long timestamp = item.purchasedAt > 0L
                ? item.purchasedAt : System.currentTimeMillis();
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(timestamp));
    }

    private static long normalizePurchaseTimestamp(long requested) {
        long now = System.currentTimeMillis();
        return requested > 0L && requested <= now ? requested : now;
    }

    @NonNull
    private static String currentMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    /** Firebase rules require every mutation to identify the signed-in editor. */
    private static void markCurrentEditor(@NonNull GroceryItem item) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        item.updatedByUid = user == null ? "" : user.getUid();
        item.updatedByName = displayName(user);
    }

    @NonNull
    private static String displayName(@Nullable FirebaseUser user) {
        if (user == null || user.getDisplayName() == null
                || user.getDisplayName().trim().isEmpty()) {
            return "Family member";
        }
        return user.getDisplayName().trim();
    }
}
