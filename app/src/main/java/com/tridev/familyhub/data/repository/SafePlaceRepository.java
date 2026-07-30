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

public final class SafePlaceRepository {
    public interface ListCallback {
        void onLoaded(@NonNull List<SafePlace> places);
    }
    public interface SaveCallback {
        void onSaved(long id);
        void onDuplicate();
        void onError();
    }

    private final SafePlaceDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public SafePlaceRepository(@NonNull Context context) {
        dao = FamilyHubDatabase.getInstance(context).safePlaceDao();
    }

    public void loadAll(@NonNull ListCallback callback) {
        executor.execute(() -> {
            List<SafePlace> places = dao.getAll();
            main.post(() -> callback.onLoaded(places));
        });
    }

    public void save(@NonNull SafePlace place, @NonNull SaveCallback callback) {
        executor.execute(() -> {
            if (dao.duplicateCount(place.name, place.latitude,
                    place.longitude, place.id) > 0) {
                main.post(callback::onDuplicate);
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
                main.post(() -> callback.onSaved(result));
            } catch (RuntimeException error) {
                main.post(callback::onError);
            }
        });
    }

    public void close() {
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
