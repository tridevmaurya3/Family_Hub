package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A private income or expense entry stored only in the Family Hub database. */
@Entity(
        tableName = "finance_entries",
        indices = {@Index(value = {"transactionDate"}), @Index(value = {"category"})}
)
public class FinanceEntry {

    public static final String TYPE_EXPENSE = "EXPENSE";
    public static final String TYPE_INCOME = "INCOME";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String entryType = TYPE_EXPENSE;

    public double amount;

    @NonNull
    public String category = "";

    @NonNull
    public String note = "";

    /** Stored as yyyy-MM-dd for reliable filtering and future reports. */
    @NonNull
    public String transactionDate = "";

    public long createdAt;
}
