package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local reminder with an optional daily repeat schedule. */
@Entity(tableName = "reminders", indices = {@Index(value = {"reminderAt"})})
public class Reminder {

    public static final String REPEAT_ONCE = "ONCE";
    public static final String REPEAT_DAILY = "DAILY";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String title = "";

    @NonNull
    public String note = "";

    public long reminderAt;

    @NonNull
    public String repeatType = REPEAT_ONCE;

    public boolean isEnabled = true;

    public long createdAt;
}
