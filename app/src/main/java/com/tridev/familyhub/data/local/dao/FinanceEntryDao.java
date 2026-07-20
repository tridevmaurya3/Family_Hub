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
            + "FROM finance_entries WHERE transactionDate LIKE :monthPrefix || '%'")
    FinanceSummary getMonthSummary(String monthPrefix);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(FinanceEntry entry);

    @Update
    int update(FinanceEntry entry);

    @Delete
    int delete(FinanceEntry entry);
}
