package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PlannerItemDao;
import com.tridev.familyhub.data.local.entity.PlannerItem;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for offline family events and tasks. */
public class PlannerRepository {

    public interface ItemsCallback {
        void onItemsLoaded(@NonNull List<PlannerItem> items);
    }

    public interface CountCallback {
        void onCountLoaded(int count);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final PlannerItemDao plannerItemDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PlannerRepository(@NonNull Context context) {
        plannerItemDao = FamilyHubDatabase
                .getInstance(context)
                .plannerItemDao();
    }

    public void loadAll(
            @NonNull String query,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<PlannerItem> items = trimmedQuery.isEmpty()
                    ? plannerItemDao.getAll()
                    : plannerItemDao.search(trimmedQuery);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadInRange(
            long rangeStart,
            long rangeEnd,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<PlannerItem> items =
                    plannerItemDao.getInRange(rangeStart, rangeEnd);
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadUpcoming(
            int limit,
            @NonNull ItemsCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<PlannerItem> items = plannerItemDao.getUpcoming(
                    System.currentTimeMillis(),
                    limit
            );
            mainHandler.post(() -> callback.onItemsLoaded(items));
        });
    }

    public void loadUpcomingCount(@NonNull CountCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            int count = plannerItemDao.countUpcoming(
                    System.currentTimeMillis()
            );
            mainHandler.post(() -> callback.onCountLoaded(count));
        });
    }

    public void save(
            @NonNull PlannerItem item,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            long now = System.currentTimeMillis();
            if (item.createdAt == 0L) {
                item.createdAt = now;
            }
            item.updatedAt = now;
            if (item.id == 0L) {
                item.id = plannerItemDao.insert(item);
            } else {
                plannerItemDao.update(item);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void setCompleted(
            @NonNull PlannerItem item,
            boolean completed,
            @NonNull ActionCallback callback
    ) {
        item.isCompleted = completed;
        save(item, callback);
    }

    public void delete(
            @NonNull PlannerItem item,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            plannerItemDao.delete(item);
            mainHandler.post(callback::onComplete);
        });
    }
}
