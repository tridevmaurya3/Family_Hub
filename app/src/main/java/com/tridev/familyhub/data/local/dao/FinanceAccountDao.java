package com.tridev.familyhub.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.tridev.familyhub.data.local.entity.FinanceAccount;
import java.util.List;

@Dao
public interface FinanceAccountDao {
    @Query("SELECT a.*, a.openingBalance + COALESCE(SUM(CASE WHEN e.entryType = 'INCOME' THEN e.amount ELSE -e.amount END), 0) AS currentBalance "
            + "FROM finance_accounts a LEFT JOIN finance_entries e ON e.accountName = a.name AND e.recurrenceStatus != 'UPCOMING' "
            + "WHERE a.archived = 0 GROUP BY a.id ORDER BY a.name COLLATE NOCASE")
    List<FinanceAccount> getActiveWithBalances();

    @Query("SELECT * FROM finance_accounts WHERE name = :name LIMIT 1")
    FinanceAccount getByName(String name);

    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(FinanceAccount account);
    @Update int update(FinanceAccount account);
}
