package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.FinanceSummary;

import java.util.List;

@Dao
public interface FinanceEntryDao {

    @Query("SELECT * FROM finance_entries ORDER BY transactionDate DESC, id DESC")
    List<FinanceEntry> getAll();

    @Query("SELECT * FROM finance_entries "
            + "WHERE category COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR note COLLATE NOCASE LIKE '%' || :query || '%' "
            + "OR entryType COLLATE NOCASE LIKE '%' || :query || '%' "
            + "ORDER BY transactionDate DESC, id DESC")
    List<FinanceEntry> search(String query);

    @Query("SELECT "
            + "COALESCE(SUM(CASE WHEN entryType = 'INCOME' THEN amount ELSE 0 END), 0) AS income, "
            + "COALESCE(SUM(CASE WHEN entryType = 'EXPENSE' THEN amount ELSE 0 END), 0) AS expense "
            + "FROM finance_entries WHERE transactionDate LIKE :monthPrefix || '%' "
            + "AND recurrenceStatus != 'UPCOMING'")
    FinanceSummary getMonthSummary(String monthPrefix);

    @Query("SELECT * FROM finance_entries WHERE id = :id LIMIT 1")
    FinanceEntry getById(long id);

    @Query("SELECT * FROM finance_entries WHERE cloudId = :cloudId LIMIT 1")
    FinanceEntry getByCloudId(String cloudId);

    @Query("SELECT * FROM finance_entries WHERE isShared = 1 AND familyId = :familyId")
    List<FinanceEntry> getSharedForFamily(String familyId);

    @Query("SELECT * FROM finance_entries WHERE isRecurring = 1 "
            + "ORDER BY transactionDate ASC, createdAt ASC")
    List<FinanceEntry> getRecurringEntries();

    @Query("SELECT COUNT(*) FROM finance_entries "
            + "WHERE recurrenceSeriesId = :seriesId AND recurrenceMonth = :month")
    int countRecurringOccurrence(String seriesId, String month);

    @Query("DELETE FROM finance_entries WHERE id = :id")
    int deleteById(long id);

    @Query("DELETE FROM finance_entries WHERE cloudId = :cloudId")
    int deleteByCloudId(String cloudId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(FinanceEntry entry);

    @Update
    int update(FinanceEntry entry);

    @Delete
    int delete(FinanceEntry entry);
}
