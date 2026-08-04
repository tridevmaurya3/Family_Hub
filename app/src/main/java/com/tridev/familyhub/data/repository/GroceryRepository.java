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
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        save(item, callback);
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
                            if (local != null) {
                                remote.id = local.id;
                            }
                            upsertLocal(remote);
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
        values.put("priority", item.priority);
        values.put("purchased", item.isPurchased);
        values.put("notes", item.notes);
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
        item.category = stringValue(snapshot.child("category")ÛÍt¶‰Ëkºwµçb‚“°¢Ğ ¢&—fFRfö–B&–æE7FGW46&G2‚’°¢f–ææ6U7FGW46&BÒ&–æF–æræ6&Df–ææ6U7FGW3°¢†VÇF…7FGW46&BÒ&–æF–æræ6&D†VÇF…7FGW3°¢fÖ–Ç•7FGW46&BÒ&–æF–æræ6&DfÖ–Ç•7FGW3°¢Fö7VÖVçE7FGW46&BÒ&–æF–æræ6&DFö7VÖVçE7FGW3°¢Ğ ¢&—fFRfö–B6WGW6V&6„&"‚’°¢6V&6„&$ÖöFVÂ6V&6„&$ÖöFVÂÒæWr6V&6„&$ÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç6V&6…ö†–çEöF6†&ö&B’À¢""À¢fÇ6RÀ¢fÇ6P¢“°¢&–æF–æræF6†&ö&E6V&6„&"ç6WDÖöFVÂ‡6V&6„&$ÖöFVÂ“°¢&–æF–æræF6†&ö&E6V&6„&"ç6WDöå6V&6„7F–öäÆ—7FVæW"€¢F†—3£¦†æFÆTF6†&ö&E6V&6€¢“°¢Ğ ¢&—fFRfö–B†æFÆTF6†&ö&E6V&6‚„æöäçVÆÂ7G&–ærVW'’’°¢7G&–æræ÷&ÖÆ—¦VEVW'’ÒVW'’çG&–Ò‚’çFôÆ÷vW$66R„Æö6ÆRå$ôõB“°¢–b†æ÷&ÖÆ—¦VEVW'’æ—4V×G’‚’’°¢6æ6¶&"æÖ¶R€¢&–æF–ærævWE&ö÷B‚’À¢"ç7G&–æræF6†&ö&E÷6V&6…öV×G’À¢6æ6¶&"äÄTäuD…õ4„õ%@¢’ç6†÷r‚“°¢&WGW&ã°¢Ğ ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'6÷2"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷Vä7F—f—G’„fÖ–Ç•6÷47F—f—G’æ6Æ72“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'6fWG’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÆW'B"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷Vä7F—f—G’„fÖ–Ç•6fWG”6VçFW$7F—f—G’æ6Æ72“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&¦÷W&æW’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'&÷WFR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&†—7F÷'’"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷Vä7F—f—G’„fÖ–Ç”¦÷W&æW”7F—f—G’æ6Æ72“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'&W÷'B"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&–ç6–v‡B"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷Vä7F—f—G’„fÖ–Ç”Æö6F–öå&W÷'G47F—f—G’æ6Æ72“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'&÷WF–æR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&WFöÖF–öâ"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷Vä7F—f—G’„fÖ–Ç”WFöÖF–öä7F—f—G’æ6Æ72“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&fÖ–Ç’Æ—fR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Æö6F–öâ"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Æ—fR"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfÖ–Ç”Æ—fR‚“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&fÖ–Ç’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÖVÖ&W""’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VåF"…"æ–BææeöfÖ–Ç’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'&VÖ–æFW""¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'66†VGVÆR"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VåF"…"æ–Bææe÷&VÖ–æFW'2“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&f–ææ6R"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&W‡Vç6R"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&–æ6öÖR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÖöæW’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&&Ææ6R"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VåF"…"æ–Bææeöf–ææ6R“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Fö7VÖVçB"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'Fb"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&6W'F–f–6FR"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWrFö7VÖVçG4g&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'77v÷&B"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&7&VFVçF–Â"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Æöv–â"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWr77v÷&EfVÇDg&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&†VÇF‚"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÖVF–6–æR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÆÆW&w’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ö–çFÖVçB"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWr†VÇF„g&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'fV†–6ÆR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&6""¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&&–¶R"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&–ç7W&æ6R"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'V2"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWrfV†–6ÆTg&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'&÷W'G’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&†÷W6R"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÆæB"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&fÆB"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'6†÷"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWr&÷W'G”g&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&w&ö6W'’"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'6†÷–ær"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Ö&¶WB"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&Æ—7B"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWrw&ö6W'”g&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&æ÷FR"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&6†V6¶Æ—7B"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&ÖVÖò"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWræ÷FW4g&vÖVçB‚’“°¢&WGW&ã°¢Ğ¢–b†æ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'ÆææW""¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&6ÆVæF""¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚&WfVçB"¢ÇÂæ÷&ÖÆ—¦VEVW'’æ6öçF–ç2‚'F6²"’’°¢&–æF–æræF6†&ö&E6V&6„&"æ6ÆV%6V&6„fö7W2‚“°¢÷VäfVGW&R†æWrÆææW$g&vÖVçB‚’“°¢&WGW&ã°¢Ğ ¢6æ6¶&"æÖ¶R€¢&–æF–ærævWE&ö÷B‚’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6V&6…öæõ÷&W7VÇBÂVW'’’À¢6æ6¶&"äÄTäuD…ôÄôäp¢’ç6†÷r‚“°¢Ğ ¢&—fFRfö–B6WGW†W&ô6&B‚’°¢&–æF–æræF6†&ö&D†W&ô6&Bç6WDÖöFVÂ†æWr†W&ô6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræfÖ–Ç•÷7FGW2’À¢vWE7G&–ær…"ç7G&–æræfÖ–Ç•÷7FGW5öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•ö‡V%öÖ&²À¢vWE7G&–ær…"ç7G&–æræfÖ–Ç•öÆ—fR¢’“°¢&–æF–æræF6†&ö&D†W&ô6&Bç6WDöä7F–öä6Æ–6´Æ—7FVæW"€¢F†—3£¦÷VäfÖ–Ç”Æ—fP¢“°¢Ğ ¢&—fFRfö–B6WGW7FGW46&G2‚’°¢f–ææ6U7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öf–ææ6R’À¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖBƒ’À¢vWE7G&–ær…"ç7G&–ærç7FGW5ö&Ææ6Uöf–Æ&ÆR’À¢"æG&v&ÆRæ–5÷vÆÆW@¢’“°¢†VÇF…7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5ö†VÇF‚’À¢vWE7G&–ær…"ç7G&–ærç7FGW5öæõö†VÇF…öFF’À¢vWE7G&–ær…"ç7G&–ærç7FGW5ö†VÇF…÷WFFR’À¢"æG&v&ÆRæ–5ö†VÇF€¢’“°¢fÖ–Ç•7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öfÖ–Ç’’À¢vWE7G&–ær…"ç7G&–ærç7FGW5÷¦W&õöÖVÖ&W'2’À¢vWE7G&–ær…"ç7G&–ærç7FGW5öfÖ–Ç•÷&VG’’À¢"æG&v&ÆRæ–5öfÖ–Ç¢’“°¢Fö7VÖVçE7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öFö7VÖVçG2’À¢vWE7G&–ær…"ç7G&–ærç7FGW5÷¦W&õöf–ÆW2’À¢vWE7G&–ær…"ç7G&–ærç7FGW5öFö7VÖVçG5÷&VG’’À¢"æG&v&ÆRæ–5öFö7VÖVç@¢’“° ¢f–ææ6U7FGW46&Bç6WDöä6Æ–6´Æ—7FVæW"‡bÓâ÷VåF"…"æ–Bææeöf–ææ6R’“°¢fÖ–Ç•7FGW46&Bç6WDöä6Æ–6´Æ—7FVæW"‡bÓâ÷VåF"…"æ–BææeöfÖ–Ç’’“°¢†VÇF…7FGW46&Bç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VäfVGW&R†æWr†VÇF„g&vÖVçB‚’’“°¢Fö7VÖVçE7FGW46&Bç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VäfVGW&R†æWrFö7VÖVçG4g&vÖVçB‚’’“°¢Ğ ¢ò¢¢W6W26—‚Væ—VRF6†&ö&B6†÷'F7WG2v—F†÷WB&WVF–ær†W&ò÷7FGW26&G2â¢ğ¢&—fFRfö–B6WGW7F–öä6&G2‚’°¢&–æF–æræ7F–öåÆææW"ç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VäfVGW&R†æWrÆææW$g&vÖVçB‚’’“°¢&–æF–æræ7F–öäw&ö6W'’ç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VäfVGW&R†æWrw&ö6W'”g&vÖVçB‚’’“°¢&–æF–æræ7F–öäFö7VÖVçG2ç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷Vä7F—f—G’„fÖ–Ç•6fWG”6VçFW$7F—f—G’æ6Æ72’“°¢&–æF–æræ7F–öåfV†–6ÆW2ç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷Vä7F—f—G’„fÖ–Ç”¦÷W&æW”7F—f—G’æ6Æ72’“°¢&–æF–æræ7F–öäæ÷FW2ç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷Vä7F—f—G’„fÖ–Ç”Æö6F–öå&W÷'G47F—f—G’æ6Æ72’“°¢&–æF–æræ7F–öäfÖ–Ç”Æ—fRç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷Vä7F—f—G’„fÖ–Ç”WFöÖF–öä7F—f—G’æ6Æ72’“° ¢&–æF–æræ7F–öäFö7VÖVçG2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG’’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG•÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG•öFWF–Â’À¢"æG&v&ÆRæ–5÷6fU÷Æ6U÷6†–VÆBÀ¢"æ6öÆ÷"æf…÷&–Ö'’À¢"æ6öÆ÷"æf…÷&–Ö'•ö6öçF–æW ¢’“°¢&–æF–æræ7F–öåfV†–6ÆW2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW’’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW•÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW•öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öÖ÷&÷WFRÀ¢"æ6öÆ÷"æf…÷6V6öæF'’À¢"æ6öÆ÷"æf…÷6V6öæF'•ö6öçF–æW ¢’“°¢&–æF–æræ7F–öäæ÷FW2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G2’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G5÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G5öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öÖ÷&÷WFRÀ¢"æ6öÆ÷"æf…ö–æfòÀ¢"æ6öÆ÷"æf…ö–æfõö6öçF–æW ¢’“°¢&–æF–æræ7F–öäfÖ–Ç”Æ—fRç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW2’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW5÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW5öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öWFöÖF–öâÀ¢"æ6öÆ÷"æf…÷v&æ–ærÀ¢"æ6öÆ÷"æf…÷v&æ–æuö6öçF–æW ¢’“°¢Ğ ¢&—fFRfö–B6WGWæ÷F–f–6F–öä7F–öâ‚’°¢&–æF–ærææ÷F–f–6F–öä'WGFöâç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VåF"…"æ–Bææe÷&VÖ–æFW'2’“°¢Ğ ¢&—fFRfö–B6WGWF6†&ö&D†–v†Æ–v‡G2‚’°¢&–æF–æræF6†&ö&D&—'F†F”6&Bç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VåF"…"æ–BææeöfÖ–Ç’’“°¢&–æF–æræF6†&ö&D&–ÆÄ6&Bç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VåF"…"æ–Bææe÷&VÖ–æFW'2’“°¢&–æF–æræF6†&ö&Ef–WtÆÂç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ÷VåF"…"æ–Bææe÷&VÖ–æFW'2’“°¢&–æF–æræF6†&ö&E&WG'’ç6WDöä6Æ–6´Æ—7FVæW"‡bÓâÆöDF6†&ö&DFF‚’“°¢&–æF–æræF6†&ö&E6÷4'WGFöâç6WDöä6Æ–6´Æ—7FVæW"€¢bÓâ6†÷tVÖW&vVæ7”F–ÆW$6öæf—&ÖF–öâ‚’“°¢Ğ ¢&—fFRfö–B6†÷tVÖW&vVæ7”F–ÆW$6öæf—&ÖF–öâ‚’°¢æWrÖFW&–ÄÆW'DF–Æöt'V–ÆFW"‡&WV—&T6öçFW‡B‚’¢ç6WEF—FÆR…"ç7G&–æræF6†&ö&E÷6÷5ö6öæf—&Õ÷F—FÆR¢ç6WDÖW76vR…"ç7G&–æræF6†&ö&E÷6÷5ö6öæf—&ÕöÖW76vR¢ç6WDæVvF—fT'WGFöâ…"ç7G&–æræ7F–öåö6æ6VÂÂçVÆÂ¢ç6WE÷6—F—fT'WGFöâ€¢"ç7G&–æræF6†&ö&E÷6÷5ö7F–öâÀ¢†F–ÆörÂv†–6‚’Óâ÷VäVÖW&vVæ7”F–ÆW"‚¢¢ç6†÷r‚“°¢Ğ ¢&—fFRfö–B÷VäVÖW&vVæ7”F–ÆW"‚’°¢–çFVçBF–Ä–çFVçBÒæWr–çFVçB€¢–çFVçBä5D”ôåôD”ÂÀ¢W&’ç'6R‚'FVÃ£""¢“°¢G'’°¢7F'D7F—f—G’†F–Ä–çFVçB“°¢Ò6F6‚„7F—f—G”æ÷Df÷VæDW†6WF–öâW'&÷"’°¢6æ6¶&"æÖ¶R€¢&–æF–ærævWE&ö÷B‚’À¢"ç7G&–æræF6†&ö&E÷6÷5÷Væf–Æ&ÆRÀ¢6æ6¶&"äÄTäuD…ôÄôäp¢’ç6†÷r‚“°¢Ğ¢Ğ ¢&—fFRfö–B6†÷tfVGW&TÖVçR‚’°¢–b‡&WV—&T7F—f—G’‚’–ç7Fæ6VöbÖ–ä7F—f—G’’°¢‚„Ö–ä7F—f—G’’&WV—&T7F—f—G’‚’’ç6†÷tfVGW&TÖVçR‚“°¢Ğ¢Ğ ¢&—fFRfö–B÷Vå&öf–ÆR‚’°¢–b‡&WV—&T7F—f—G’‚’–ç7Fæ6VöbÖ–ä7F—f—G’’°¢‚„Ö–ä7F—f—G’’&WV—&T7F—f—G’‚’’æ÷Vå&öf–ÆR‚“°¢Ğ¢Ğ ¢&—fFRfö–B÷VäfÖ–Ç”Æ—fR‚’°¢–b‡&WV—&T7F—f—G’‚’–ç7Fæ6VöbÖ–ä7F—f—G’’°¢‚„Ö–ä7F—f—G’’&WV—&T7F—f—G’‚’’æ÷VäfVGW&R€¢æWrfÖ–Ç”Æ—fTg&vÖVçB‚¢“°¢Ğ¢Ğ ¢&—fFRfö–B÷VäfVGW&R„æöäçVÆÂg&vÖVçBg&vÖVçB’°¢–b‡&WV—&T7F—f—G’‚’–ç7Fæ6VöbÖ–ä7F—f—G’’°¢‚„Ö–ä7F—f—G’’&WV—&T7F—f—G’‚’’æ÷VäfVGW&R†g&vÖVçB“°¢Ğ¢Ğ ¢&—fFRfö–B÷Vä7F—f—G’„æöäçVÆÂ6Æ73Ãóâ7F—f—G”6Æ72’°¢7F'D7F—f—G’†æWr–çFVçB‡&WV—&T6öçFW‡B‚’Â7F—f—G”6Æ72’“°¢Ğ ¢÷fW'&–FP¢V&Æ–2fö–Böå&W7VÖR‚’°¢7WW"æöå&W7VÖR‚“°¢–b†F6†&ö&E&W÷6—F÷'’ÒçVÆÂ’°¢&VæFW$†VFW"‚“°¢ÆöDF6†&ö&DFF‚“°¢Ğ¢Ğ ¢&—fFRfö–BÆöDF6†&ö&DFF‚’°¢–b†&–æF–ærÓÒçVÆÂ’°¢&WGW&ã°¢Ğ¢&–æF–æræF6†&ö&DÆöF–ærç6WEf—6–&–Æ—G’…f–Wråd•4”$ÄR“°¢&–æF–æræF6†&ö&DW'&÷$6&Bç6WEf—6–&–Æ—G’…f–WrätôäR“° ¢F6†&ö&E&W÷6—F÷'’æÆöDF6†&ö&DFF€¢FFÓâ°¢–b†&–æF–ærÓÒçVÆÂ’°¢&WGW&ã°¢Ğ¢&–æF–æræF6†&ö&DÆöF–ærç6WEf—6–&–Æ—G’…f–WrätôäR“°¢&–æF–æræF6†&ö&DW'&÷$6&Bç6WEf—6–&–Æ—G’…f–WrätôäR“°¢&VæFW$f–ææ6R†FFævWE7FG2‚’“°¢&VæFW$6÷VçG2†FFævWE7FG2‚’“°¢&VæFW$7F–öä6&G2†FFævWE7FG2‚’“°¢&VæFW%&VÖ–æFW"†FF“°¢&VæFW$&—'F†F’†FF“°¢&VæFW$&–ÆÂ†FF“°¢&VæFW%&V6VçD7F—f—G’†FF“°¢ÒÀ¢W'&÷"Óâ°¢–b†&–æF–ærÓÒçVÆÂ’°¢&WGW&ã°¢Ğ¢&–æF–æræF6†&ö&DÆöF–ærç6WEf—6–&–Æ—G’…f–WrätôäR“°¢&–æF–æræF6†&ö&DW'&÷$6&Bç6WEf—6–&–Æ—G’…f–Wråd•4”$ÄR“°¢Ğ¢“°¢Ğ ¢&—fFRfö–B&VæFW$&—'F†F’„æöäçVÆÂF6†&ö&DFFFF’°¢fÖ–Ç”ÖVÖ&W"ÖVÖ&W"ÒFFævWDæW‡D&—'F†F”ÖVÖ&W"‚“°¢–b‚FFæ†5W6öÖ–æt&—'F†F’‚’ÇÂÖVÖ&W"ÓÒçVÆÂ’°¢&–æF–æræF6†&ö&D&—'F†F•F—FÆRç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõö&—'F†F•÷F—FÆR“°¢&–æF–æræF6†&ö&D&—'F†F”FWF–Âç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõö&—'F†F•öFWF–Â“°¢&WGW&ã°¢Ğ¢&–æF–æræF6†&ö&D&—'F†F•F—FÆRç6WEFW‡B†ÖVÖ&W"ææÖR“°¢&–æF–æræF6†&ö&D&—'F†F”FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&Eö&—'F†F•öFWF–ÂÀ¢ÖVÖ&W"ç&VÆF–öâÀ¢&VÖ–æFW$FFTf÷&ÖBæf÷&ÖB†æWrFFR†FFævWDæW‡D&—'F†F”B‚’’¢’“°¢Ğ ¢&—fFRfö–B&VæFW$&–ÆÂ„æöäçVÆÂF6†&ö&DFFFF’°¢&VÖ–æFW"&–ÆÂÒFFævWDæW‡D&–ÆÅ&VÖ–æFW"‚“°¢–b‚FFæ†5W6öÖ–æt&–ÆÂ‚’ÇÂ&–ÆÂÓÒçVÆÂ’°¢&–æF–æræF6†&ö&D&–ÆÅF—FÆRç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõö&–ÆÅ÷F—FÆR“°¢&–æF–æræF6†&ö&D&–ÆÄFWF–Âç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõö&–ÆÅöFWF–Â“°¢&WGW&ã°¢Ğ¢FFR&–ÆÄFFRÒæWrFFR†FFævWDæW‡D&–ÆÅG&–vvW$B‚’“°¢&–æF–æræF6†&ö&D&–ÆÅF—FÆRç6WEFW‡B†&–ÆÂçF—FÆR“°¢&–æF–æræF6†&ö&D&–ÆÄFWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&Eö&–ÆÅöFWF–ÂÀ¢&VÖ–æFW$FFTf÷&ÖBæf÷&ÖB†&–ÆÄFFR’À¢&VÖ–æFW%F–ÖTf÷&ÖBæf÷&ÖB†&–ÆÄFFR¢’“°¢Ğ ¢&—fFRfö–B&VæFW%&V6VçD7F—f—G’„æöäçVÆÂF6†&ö&DFFFF’°¢–b†FFæ†5W6öÖ–æu&VÖ–æFW"‚’bbFFævWDæW‡E&VÖ–æFW"‚’ÒçVÆÂ’°¢&–æF–æræF6†&ö&E&V6VçD7F—f—G”FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&Eö7F—f—G•÷&VÖ–æFW"À¢FFævWDæW‡E&VÖ–æFW"‚’çF—FÆP¢’“°¢ÒVÇ6R–b†FFævWDW‡Vç6R‚’âB’°¢&–æF–æræF6†&ö&E&V6VçD7F—f—G”FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&Eö7F—f—G•öW‡Vç6RÀ¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖB†FFævWDW‡Vç6R‚’¢’“°¢ÒVÇ6R–b†FFævWEF÷FÄÖVÖ&W'2‚’â’°¢&–æF–æræF6†&ö&E&V6VçD7F—f—G”FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&Eö7F—f—G•öfÖ–Ç’À¢FFævWEF÷FÄÖVÖ&W'2‚¢’“°¢ÒVÇ6R°¢&–æF–æræF6†&ö&E&V6VçD7F—f—G”FWF–Âç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõ÷&V6VçEö7F—f—G’“°¢Ğ¢Ğ ¢&—fFRfö–B&VæFW$7F–öä6&G2„æöäçVÆÂF6†&ö&E7FG27FG2’°¢&–æF–æræ7F–öåÆææW"ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræ7F–öå÷ÆææW%÷F—FÆR’À¢vWE7G&–ær…"ç7G&–æræ7F–öåö÷Vå÷fÇVRÂ7FG2ævWEÆææW$÷Vâ‚’’À¢vWE7G&–ær…"ç7G&–æræ7F–öåö6ö×ÆWFVE÷fÇVRÀ¢7FG2ævWEÆææW$6ö×ÆWFVB‚’’À¢"æG&v&ÆRæ–5÷ÆææW"À¢"æ6öÆ÷"æf…öÖöGVÆU÷&VÖ–æFW'2À¢"æ6öÆ÷"æf…öÖöGVÆU÷&VÖ–æFW'5ö6öçF–æW ¢’“° ¢&–æF–æræ7F–öäw&ö6W'’ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræ7F–öåöw&ö6W'•÷F—FÆR’À¢vWE7G&–ær…"ç7G&–æræ7F–öå÷VæF–æu÷fÇVRÀ¢7FG2ævWDw&ö6W'•VæF–ær‚’’À¢vWE7G&–ær…"ç7G&–æræ7F–öå÷W&6†6VE÷fÇVRÀ¢7FG2ævWDw&ö6W'•W&6†6VB‚’’À¢"æG&v&ÆRæ–5öw&ö6W'’À¢"æ6öÆ÷"æf…öÖöGVÆUöf–ææ6RÀ¢"æ6öÆ÷"æf…öÖöGVÆUöf–ææ6Uö6öçF–æW ¢’“° ¢&–æF–æræ7F–öäFö7VÖVçG2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG’’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG•÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷6fWG•öFWF–Â’À¢"æG&v&ÆRæ–5÷6fU÷Æ6U÷6†–VÆBÀ¢"æ6öÆ÷"æf…÷&–Ö'’À¢"æ6öÆ÷"æf…÷&–Ö'•ö6öçF–æW ¢’“° ¢&–æF–æræ7F–öåfV†–6ÆW2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW’’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW•÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WEö¦÷W&æW•öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öÖ÷&÷WFRÀ¢"æ6öÆ÷"æf…÷6V6öæF'’À¢"æ6öÆ÷"æf…÷6V6öæF'•ö6öçF–æW ¢’“° ¢&–æF–æræ7F–öäæ÷FW2ç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G2’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G5÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&W÷'G5öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öÖ÷&÷WFRÀ¢"æ6öÆ÷"æf…ö–æfòÀ¢"æ6öÆ÷"æf…ö–æfõö6öçF–æW ¢’“° ¢&–æF–æræ7F–öäfÖ–Ç”Æ—fRç6WDÖöFVÂ†æWr7F–öä6&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW2’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW5÷fÇVR’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&E÷6†÷'F7WE÷&÷WF–æW5öFWF–Â’À¢"æG&v&ÆRæ–5öfÖ–Ç•öWFöÖF–öâÀ¢"æ6öÆ÷"æf…÷v&æ–ærÀ¢"æ6öÆ÷"æf…÷v&æ–æuö6öçF–æW ¢’“°¢Ğ ¢&—fFRfö–B&VæFW$f–ææ6R„æöäçVÆÂF6†&ö&E7FG27FG2’°¢&–æF–æræF6†&ö&DÖöçF†Ç”W‡Vç6UfÇVRç6WEFW‡B€¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖB‡7FG2ævWDW‡Vç6R‚’’“°¢6öÒçG&–FWbæfÖ–Ç–‡V"æ6÷&RçV’å6VÖçF–5fÇVU7G–ÆW"æÇ’€¢&–æF–æræF6†&ö&DÖöçF†Ç”W‡Vç6UfÇVRÀ¢×7FG2ævWDW‡Vç6R‚¢“°¢7G&–ærFWF–ÂÒvWE7G&–ær€¢"ç7G&–æræF6†&ö&Eöf–ææ6UöFWF–ÂÀ¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖB‡7FG2ævWD–æ6öÖR‚’’À¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖB‡7FG2ævWD&Ææ6R‚’¢“°¢&–æF–æræF6†&ö&DÖöçF†Ç”W‡Vç6TFWF–Âç6WEFW‡B†FWF–Â“°¢f–ææ6U7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öf–ææ6R’À¢7W'&Væ7”f÷&ÖGFW"æf÷&ÖB‡7FG2ævWD&Ææ6R‚’’À¢FWF–ÂÀ¢"æG&v&ÆRæ–5÷vÆÆW@¢’“°¢f–ææ6U7FGW46&Bç6WEfÇVT6öÆ÷$'•6–vâ‡7FG2ævWD&Ææ6R‚’“°¢Ğ ¢&—fFRfö–B&VæFW$6÷VçG2„æöäçVÆÂF6†&ö&E7FG27FG2’°¢–çBÖVÖ&W'2Ò7FG2ævWEF÷FÄÖVÖ&W'2‚“°¢–çBFö7VÖVçG2Ò7FG2ævWDFö7VÖVçG2‚“°¢–çB†VÇF…&V6÷&G2Ò7FG2ævWD†VÇF„ÆW'G2‚“° ¢fÖ–Ç•7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öfÖ–Ç’’À¢vWE&W6÷W&6W2‚’ævWEVçF—G•7G&–ær€¢"çÇW&Ç2æF6†&ö&EöfÖ–Ç•öÖVÖ&W%ö6÷VçBÀ¢ÖVÖ&W'2À¢ÖVÖ&W'0¢’À¢vWE7G&–ær…"ç7G&–ærç7FGW5öfÖ–Ç•÷&VG’’À¢"æG&v&ÆRæ–5öfÖ–Ç¢’“°¢Fö7VÖVçE7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5öFö7VÖVçG2’À¢vWE&W6÷W&6W2‚’ævWEVçF—G•7G&–ær€¢"çÇW&Ç2æF6†&ö&EöFö7VÖVçEö6÷VçBÀ¢Fö7VÖVçG2À¢Fö7VÖVçG0¢’À¢vWE7G&–ær…"ç7G&–æræF6†&ö&EöFö7VÖVçG5öÆö6ÅöFWF–Â’À¢"æG&v&ÆRæ–5öFö7VÖVç@¢’“°¢†VÇF…7FGW46&Bç6WDÖöFVÂ†æWr7FGW46&DÖöFVÂ€¢vWE7G&–ær…"ç7G&–ærç7FGW5ö†VÇF‚’À¢vWE&W6÷W&6W2‚’ævWEVçF—G•7G&–ær€¢"çÇW&Ç2æ†VÇF…÷&V6÷&Eö6÷VçBÀ¢†VÇF…&V6÷&G2À¢†VÇF…&V6÷&G0¢’À¢vWE7G&–ær…"ç7G&–æræ†VÇF…öF6†&ö&EöFWF–Â’À¢"æG&v&ÆRæ–5ö†VÇF€¢’“°¢Ğ ¢&—fFRfö–B&VæFW%&VÖ–æFW"„æöäçVÆÂF6†&ö&DFFFF’°¢&VÖ–æFW"æW‡E&VÖ–æFW"ÒFFævWDæW‡E&VÖ–æFW"‚“°¢–b‚FFæ†5W6öÖ–æu&VÖ–æFW"‚’ÇÂæW‡E&VÖ–æFW"ÓÒçVÆÂ’°¢&–æF–æræF6†&ö&EW6öÖ–æu&VÖ–æFW%F—FÆRç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõ÷W6öÖ–æu÷&VÖ–æFW%÷F—FÆR“°¢&–æF–æræF6†&ö&EW6öÖ–æu&VÖ–æFW$FWF–Âç6WEFW‡B€¢"ç7G&–æræF6†&ö&Eöæõ÷W6öÖ–æu÷&VÖ–æFW%öFWF–Â“°¢&WGW&ã°¢Ğ¢FFR&VÖ–æFW$FFRÒæWrFFR†FFævWDæW‡E&VÖ–æFW%G&–vvW$B‚’“°¢&–æF–æræF6†&ö&EW6öÖ–æu&VÖ–æFW%F—FÆRç6WEFW‡B†æW‡E&VÖ–æFW"çF—FÆR“°¢–b…&VÖ–æFW"å$UTEôD”Å’æWVÇ2†æW‡E&VÖ–æFW"ç&WVEG—R’’°¢&–æF–æræF6†&ö&EW6öÖ–æu&VÖ–æFW$FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–ærç&VÖ–æFW%öF–Ç•öBÀ¢&VÖ–æFW%F–ÖTf÷&ÖBæf÷&ÖB‡&VÖ–æFW$FFR¢’“°¢ÒVÇ6R°¢&–æF–æræF6†&ö&EW6öÖ–æu&VÖ–æFW$FWF–Âç6WEFW‡B†vWE7G&–ær€¢"ç7G&–æræF6†&ö&EöæW‡E÷&VÖ–æFW%öFWF–ÂÀ¢&VÖ–æFW$FFTf÷&ÖBæf÷&ÖB‡&VÖ–æFW$FFR’À¢&VÖ–æFW%F–ÖTf÷&ÖBæf÷&ÖB‡&VÖ–æFW$FFR¢’“°¢Ğ¢Ğ ¢&—fFRfö–B÷VåF"†–çBFW7F–æF–öä–B’°¢–b‡&WV—&T7F—f—G’‚’–ç7Fæ6VöbÖ–ä7F—f—G’’°¢‚„Ö–ä7F—f—G’’&WV—&T7F—f—G’‚’’æ÷VåF"†FW7F–æF–öä–B“°¢Ğ¢Ğ ¢&—fFR–çBG†–çBfÇVR’°¢&WGW&âÖF‚ç&÷VæB‡fÇVR¢vWE&W6÷W&6W2‚’ævWDF—7Æ”ÖWG&–72‚’æFVç6—G’“°¢Ğ ¢÷fW'&–FP¢V&Æ–2fö–BöäFW7G&÷•f–Wr‚’°¢–b†F6†&ö&E&W÷6—F÷'’ÒçVÆÂ’°¢F6†&ö&E&W÷6—F÷'’æ6Æ÷6R‚“°¢F6†&ö&E&W÷6—F÷'’ÒçVÆÃ°¢Ğ¢f–ææ6U7FGW46&BÒçVÆÃ°¢†VÇF…7FGW46&BÒçVÆÃ°¢fÖ–Ç•7FGW46&BÒçVÆÃ°¢Fö7VÖVçE7FGW46&BÒçVÆÃ°¢&öf–ÆTfF"ÒçVÆÃ°¢&–æF–ærÒçVÆÃ°¢7WW"æöäFW7G&÷•f–Wr‚“°¢Ğ§Ğ 