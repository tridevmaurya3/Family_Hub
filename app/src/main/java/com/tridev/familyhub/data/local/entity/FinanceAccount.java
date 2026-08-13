package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "finance_accounts", indices = {@Index(value = {"name"}, unique = true)})
public class FinanceAccount {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String name = "";
    @NonNull public String accountType = "CASH";
    public double openingBalance;
    public boolean archived;
    public long updatedAt;
    public double currentBalance;
}
