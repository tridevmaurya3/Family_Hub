package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PasswordEntryDao;
import com.tridev.familyhub.data.local.entity.PasswordEntry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps encrypted Password Vault database work away from the UI thread. */
public class PasswordVaultRepository {

    public interface EntriesCallback {
        void onEntriesLoaded(@NonNull List<PasswordEntry> entries);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final PasswordEntryDao passwordEntryDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PasswordVaultRepository(@NonNull Context context) {
        passwordEntryDao = FamilyHubDatabase
                .getInstance(context)
                .passwordEntryDao();
    }

    public void loadEntries(
            @NonNull String query,
            @NonNull EntriesCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<PasswordEntry> entries = trimmedQuery.isEmpty()
                    ? passwordEntryDao.getAll()
                    : passwordEntryDao.search(trimmedQuery);
            mainHandler.post(() -> callback.onEntriesLoaded(entries));
        });
    }

    public void save(
            @NonNull PasswordEntry entry,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (entry.createdAt == 0L) {
                entry.createdAt = System.currentTimeMillis();
            }

            if (entry.id == 0L) {
                entry.id = passwordEntryDao.insert(entry);
            } else {
                passwordEntryDao.update(entry);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(
            @NonNull PasswordEntry entry,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            passwordEntryDao.delete(entry);
            mainHandler.post(callback::onComplete);
        });
    }
}
