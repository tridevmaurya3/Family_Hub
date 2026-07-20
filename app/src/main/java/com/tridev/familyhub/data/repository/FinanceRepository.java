package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FinanceEntryDao;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.FinanceSummary;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Data boundary for the offline finance feature. */
public class FinanceRepository {

    public interface EntriesCallback {
        void onEntriesLoaded(List<FinanceEntry> entries);
    }

    public interface SummaryCallback {
        void onSummaryLoaded(FinanceSummary summary);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final FinanceEntryDao financeEntryDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FinanceRepository(Context context) {
        financeEntryDao = FamilyHubDatabase.getInstance(context).financeEntryDao();
    }

    public void loadEntries(@NonNull String searchQuery, @NonNull EntriesCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FinanceEntry> entries = searchQuery.trim().isEmpty()
                    ? financeEntryDao.getAll()
                    : financeEntryDao.search(searchQuery.trim());
            mainHandler.post(() -> callback.onEntriesLoaded(entries));
        });
    }

    public void loadCurrentMonthSummary(@NonNull SummaryCallback callback) {
        String monthPrefix = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        DATABASE_EXECUTOR.execute(() -> {
            FinanceSummary summary = financeEntryDao.getMonthSummary(monthPrefix);
            mainHandler.post(() -> callback.onSummaryLoaded(summary));
        });
    }

    public void save(FinanceEntry entry, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            if (entry.id == 0) {
                entry.createdAt = System.currentTimeMillis();
                financeEntryDao.insert(entry);
            } else {
                financeEntryDao.update(entry);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(FinanceEntry entry, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            financeEntryDao.delete(entry);
            mainHandler.post(callback::onComplete);
        });
    }
}
