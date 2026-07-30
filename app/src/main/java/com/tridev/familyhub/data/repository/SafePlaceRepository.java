package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.SafePlaceDao;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SafePlaceRepository {
    public interface ListCallback {
        void onLoaded(@NonNull List<SafePlace> places);
    }
    public interface SaveCallback {
        void onSaved(long id);
        void onDuplicate();
        void onLimitReached();
        void onError();
    }
    public interface ActionCallback {
        void onComplete();
        void onError();
    }

    private final SafePlaceDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SafePlaceRepository(@NonNull Context context) {
        dao = FamilyHubDatabase.getInstance(context).safePlaceDao();
    }

    public void loadAll(@NonNull ListCallback callback) {
        if (closed.get()) return;
        try {
            executor.execute(() -> {
                List<SafePlace> places = dao.getAll();
                main.post(() -> {
                    if (!closed.get()) callback.onLoaded(places);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Repository was closed between the guard and task submission.
        }
    }

    public void save(@NonNull SafePlace place, @NonNull SaveCallback callback) {
        if (closed.get()) return;
        try {
            executor.execute(() -> {
            if (dao.duplicateCount(place.name, place.latitude,
                    place.longitude, place.id) > 0) {
                main.post(() -> {
                    if (!closed.get()) callback.onDuplicate();
                });
                return;
            }
            if (place.alertsEnabled
                    && dao.enabledCountExcluding(place.id) >= 100) {
                main.post(() -> {
                    if (!closed.get()) callback.onLimitReached();
                });
                return;
            }
            try {
                long now = System.currentTimeMillis();
                place.updatedAt = now;
                long id;
                if (place.id == 0L) {
                    place.createdAt = now;
                    id = dao.insert(place);
                } else {
                    dao.update(place);
                    id = place.id;
                }
                long result = id;
                main.post(() -> {
                    if (!closed.get()) callback.onSaved(result);
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (!closed.get()) callback.onError();
                });
            }
            });
        } catch (RejectedExecutionException ignored) {
            // Repository was closed between the guard and task submission.
        }
    }

    public void delete(
            @NonNull SafePlace place,
            @NonNull ActionCallback callback
    ) {
        if (closed.get()) return;
        try {
            executor.execute(() -> {
                try {
                    dao.delete(place);
                    main.post(() -> {
                        if (!closed.get()) callback.onComplete();
                    });
                } catch (RuntimeException error) {
                    main.post(() -> {
                        if (!closed.get()) callback.onError();
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Repository was closed between the guard and submission.
        }
    }

    public void setAlertsEnabled(
            long id,
            boolean enabled,
            @NonNull ActionCallback callback
    ) {
        if (closed.get()) return;
        try {
            executor.execute(() -> {
                try {
                    int changed = dao.updateAlertsEnabled(
                            id,
                            enabled,
                            System.currentTimeMillis()
                    );
                    main.post(() -> {
                        if (closed.get()) return;
                        if (changed == 1) callback.onComplete();
                        else callback.onError();
                    });
                } catch (RuntimeException error) {
                    main.post(() -> {
                        if (!closed.get()) callback.onError();
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Repository was closed between guard and submission.
        }
    }

    public void close() {
        closed.set(true);
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
