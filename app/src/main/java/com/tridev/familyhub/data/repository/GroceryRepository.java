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
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetProvider;
import com.tridev.familyhub.feature.grocery.GroceryNotificationHelper;

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

/** Offline-first Room cache backed by the family's private Firebase list. */
public class GroceryRepository {

    public interface ItemsCallback {
        void onItemsLoaded(@NonNull List<GroceryItem> items);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
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
    @NonNull private String activeFamilyId = "";

    public GroceryRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        groceryItemDao = FamilyHubDatabase.getInstance(appContext)
                .groceryItemDao();
        financeEntryDao = FamilyHubDatabase.getInstance(appContext)
                .financeEntryDao();
        accountRepository = new FamilyAccountRepository();
        firebaseRoot = FirebaseDatabase.getInstance().getReference();
    }

    /** Starts one family-scoped realtime listener; safe to call repeatedly. */
    public void startRealtimeSync(@NonNull Runnable onChanged) {
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
        DATABASE_EXECUTOR.execute(() -> {
            resetMonthlyMastersIfNeeded();
            String trimmedQuery = query.trim();
            List<GroceryItem> items = trimmedQuery.isEmpty()
                    ? groceryItemDao.getAll()
                    : groceryItemDao.search(trimmedQuery);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void save(
            @NonNull GroceryItem item,
            @NonNull ActionCallback callback
    ) {
        long now = System.currentTimeMillis();
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

    public void setPurchased(
            @NonNull GroceryItem item,
            boolean purchased,
            @NonNull ActionCallback callback
    ) {
        item.isPurchased = purchased;
        item.purchasedAt = purchased ? System.currentTimeMillis() : 0L;
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
        save(item, callback);
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

    public void delete(
            @NonNull GroceryItem item,
            @NonNull ActionCallback callback
    ) {
        String cloudId = item.cloudId;
        DATABASE_EXECUTOR.execute(() -> {
            groceryItemDao.delete(item);
            GroceryWidgetProvider.refreshAll(appContext);
            mainHandler.post(() -> {
                callback.onComplete();
                if (!activeFamilyId.isEmpty() && !cloudId.isEmpty()) {
                    firebaseRoot.child("sharedShopping")
                            .child(activeFamilyId).child("items")
                            .child(cloudId).removeValue();
                }
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
                groceryItemDao.delete(item);
                if (!activeFamilyId.isEmpty() && !item.cloudId.isEmpty()) {
                    firebaseRoot.child("sharedShopping")
                            .child(activeFamilyId).child("items")
                            .child(item.cloudId).removeValue();
                }
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
                    for (GroceryItem local : groceryItemDao.getAllSynced()) {
                        if (familyId.equals(local.familyId)
                                && !remoteIds.contains(local.cloudId)
                                && System.currentTimeMillis() - local.updatedAt
                                > 30_000L) {
                            groceryItemDao.delete(local);
                        }
                    }
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
    }

    private void uploadLocalOnlyItems(@NonNull String familyId) {
        DATABASE_EXECUTOR.execute(() -> {
            for (GroceryItem item : groceryItemDao.getAll()) {
                if (!item.familyId.isEmpty() && !familyId.equals(item.familyId)) {
                    continue;
                }
                if (familyId.equals(item.familyId) && !item.cloudId.isEmpty()) {
                    continue;
                }
                if (item.cloudId.isEmpty()) {
                    item.cloudId = UUID.randomUUID().toString();
                }
                item.familyId = familyId;
                if (item.updatedAt == 0L) {
                    item.updatedAt = Math.max(item.createdAt,
                            System.currentTimeMillis());
                }
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (item.updatedByUid.isEmpty() && user != null) {
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

    private void resetMonthlyMastersIfNeeded() {
        String month = currentMonth();
        for (GroceryItem item : groceryItemDao.getAll()) {
            if (!item.isMonthlyMaster || month.equals(item.lastResetMonth)) {
                continue;
            }
            item.lastResetMonth = month;
            item.isPurchased = false;
            item.purchasedAt = 0L;
            item.purchasedByName = "";
            item.actualCost = 0D;
            // Preserve the previous month's Finance entry as purchase history.
            item.financeEntryId = 0L;
            item.buyingStatus = GroceryItem.STATUS_PENDING;
            item.updatedAt = System.currentTimeMillis();
            groceryItemDao.update(item);
            if (!activeFamilyId.isEmpty() && !item.cloudId.isEmpty()) {
                firebaseRoot.child("sharedShopping").child(activeFamilyId)
                        .child("items").child(item.cloudId)
                        .updateChildren(toCloudValues(item));
            }
        }
    }

    private void linkFinance(@NonNull GroceryItem item) {
        if (!item.isPurchased) {
            if (item.financeEntryId > 0L) {
                financeEntryDao.deleteById(item.financeEntryId);
                item.financeEntryId = 0L;
            }
            return;
        }
        double amount = item.actualCost > 0D
                ? item.actualCost : item.estimatedCost;
        if (amount <= 0D) {
            return;
        }
        if (item.financeEntryId > 0L) {
            FinanceEntry existing = financeEntryDao.getById(item.financeEntryId);
            if (existing != null) {
                existing.amount = amount;
                existing.note = item.name + (item.quantity.isEmpty()
                        ? "" : " • " + item.quantity);
                financeEntryDao.update(existing);
                return;
            }
            item.financeEntryId = 0L;
        }
        FinanceEntry entry = new FinanceEntry();
        entry.entryType = FinanceEntry.TYPE_EXPENSE;
        entry.amount = amount;
        entry.category = "Grocery";
        entry.note = item.name + (item.quantity.isEmpty()
                ? "" : " • " + item.quantity);
        entry.transactionDate = new SimpleDateFormat(
                "yyyy-MM-dd", Locale.US).format(new Date());
        entry.createdAt = System.currentTimeMillis();
        item.financeEntryId = financeEntryDao.insert(entry);
    }

    @NonNull
    private static String currentMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
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
