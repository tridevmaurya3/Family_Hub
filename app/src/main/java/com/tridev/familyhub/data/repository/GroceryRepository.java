package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.GroceryItemDao;
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for the offline family shopping list. */
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public GroceryRepository(@NonNull Context context) {
        groceryItemDao = FamilyHubDatabase
                .getInstance(context)
                .groceryItemDao();
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
        DATABASE_EXECUTOR.execute(() -> {
            if (item.createdAt == 0L) {
                item.createdAt = System.currentTimeMillis();
            }
            if (item.id == 0L) {
                item.id = groceryItemDao.insert(item);
            } else {
                groceryItemDao.update(item);
            }
            mainHandler.post(callback::onComplete);
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
        DATABASE_EXECUTOR.execute(() -> {
            groceryItemDao.delete(item);
            mainHandler.post(callback::onComplete);
        });
    }

    public void clearPurchased(@NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            groceryItemDao.deletePurchased();
            mainHandler.post(callback::onComplete);
        });
    }
}
