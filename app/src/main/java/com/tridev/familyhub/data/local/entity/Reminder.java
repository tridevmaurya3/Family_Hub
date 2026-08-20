package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

/** A local reminder with an optional daily repeat schedule. */
@Entity(tableName = "reminders", indices = {@Index(value = {"reminderAt"}),
        @Index(value = {"cloudId"}), @Index(value = {"familyId"}),
        @Index(value = {"assignedMemberId"})})
public class Reminder {

    public static final String REPEAT_ONCE = "ONCE";
    public static final String REPEAT_DAILY = "DAILY";
    public static final String REPEAT_WEEKLY = "WEEKLY";
    public static final String REPEAT_MONTHLY = "MONTHLY";
    public static final String REPEAT_YEARLY = "YEARLY";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String title = "";

    @NonNull
    public String note = "";

    public long reminderAt;

    @NonNull
    public String repeatType = REPEAT_ONCE;

    @NonNull
    @ColumnInfo(defaultValue = "'MEDIUM'")
    public String priority = "MEDIUM";

    @NonNull
    @ColumnInfo(defaultValue = "'GENERAL'")
    public String category = "GENERAL";

    @ColumnInfo(defaultValue = "0")
    public int preAlertMinutes;

    @ColumnInfo(defaultValue = "0") public long seenAt;
    @ColumnInfo(defaultValue = "0") public long acceptedAt;
    @ColumnInfo(defaultValue = "0") public long startedAt;
    @ColumnInfo(defaultValue = "0") public long completedAt;
    @NonNull @ColumnInfo(defaultValue = "''") public String completedByName = "";

    public boolean isEnabled = true;

    public long createdAt;
    @NonNull public String cloudId = "";
    @NonNull public String familyId = "";
    public long assignedMemberId;
    @NonNull public String assignedMemberName = "";
    @NonNull public String collaborationStatus = "PENDING";
    public boolean isShared;
    public long updatedAt;
    @NonNull public String updatedByUid = "";
}
