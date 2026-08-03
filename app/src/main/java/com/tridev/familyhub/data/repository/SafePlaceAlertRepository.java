package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.SafePlaceAlertDao;
import com.tridev.familyhub.data.local.dao.SafePlaceDao;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.location.FamilyDeviceSafetyAlertStateStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background-only access layer for local Family Safety alert history. */
public final class SafePlaceAlertRepository {

    public interface HistoryCallback {
        void onLoaded(
                @NonNull List<SafePlaceAlert> alerts,
                int unreadCount,
                @NonNull Map<String, String> placeNames
        );

        void onError();
    }

    public interface ActionCallback {
        void onComplete();

        void onError();
    }

    private final SafePlaceAlertDao alertDao;
    private final SafePlaceDao safePlaceDao;
    private final FamilyDeviceSafetyAlertStateStore deviceAlertStateStore;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SafePlaceAlertRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        alertDao = database.safePlaceAlertDao();
        safePlaceDao = database.safePlaceDao();
        deviceAlertStateStore = new FamilyDeviceSafetyAlertStateStore(context);
    }

    public void loadHistory(@NonNull HistoryCallback callback) {
        execute(() -> {
            List<SafePlaceAlert> alerts = alertDao.getAll();
            int unread = alertDao.unreadCount();
            Map<String, String> placeNames = new HashMap<>();
            for (SafePlace place : safePlaceDao.getAll()) {
                placeNames.put(String.valueOf(place.id), place.name);
            }
            placeNames.putAll(deviceAlertStateStore.knownMemberNames());
            main.post(() -> {
                if (!closed.get()) {
                    callback.onLoaded(alerts, unread, placeNames);
                }
            });
        }, new ActionCallback() {
            @Override
            public void onComplete() {
                // Loading dispatches through onLoaded.
            }

            @Override
            public void onError() {
                if (!closed.get()) {
                    callback.onError();
                }
            }
        });
    }

    public void markRead(
            long alertId,
            @NonNull ActionCallback callback
    ) {
        execute(() -> dispatchResult(
                alertDao.markRead(alertId) == 1,
                callback
        ), callback);
    }

    public void markAllRead(@NonNull ActionCallback callback) {
        execute(() -> {
            alertDao.markAllRead();
            dispatchResult(true, callback);
        }, callback);
    }

    private void execute(
            @NonNull Runnable task,
            ActionCallback errorCallback
    ) {
        if (closed.get()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException error) {
                    if (errorCallback != null) {
                        main.post(() -> {
                            if (!closed.get()) {
                                errorCallback.onError();
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Repository closed between the guard and submission.
        }
    }

    private void dispatchResult(
            boolean success,
            @NonNull ActionCallback callback
    ) {
        main.post(() -> {
            if (closed.get()) {
                return;
            }
            if (success) {
                callback.onComplete();
            } else {
                callback.onError();
            }
        });
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
