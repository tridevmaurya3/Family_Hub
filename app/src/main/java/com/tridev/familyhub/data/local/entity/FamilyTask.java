package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One canonical, family-scoped To-Do record. */
@Entity(tableName = "family_tasks", indices = {
        @Index(value = "cloudId", unique = true),
        @Index("familyId"), @Index("dueAt"), @Index("status"),
        @Index("assignedMemberId"), @Index("updatedAt")
})
public class FamilyTask {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";
    public static final String REPEAT_NONE = "NONE";
    public static final String REPEAT_DAILY = "DAILY";
    public static final String REPEAT_WEEKLY = "WEEKLY";
    public static final String REPEAT_MONTHLY = "MONTHLY";

    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String cloudId = "";
    @NonNull public String familyId = "";
    @NonNull public String title = "";
    @NonNull public String notes = "";
    @NonNull public String status = STATUS_PENDING;
    @NonNull public String priority = PRIORITY_NORMAL;
    @NonNull public String repeatType = REPEAT_NONE;
    @NonNull public String assignedMemberId = "";
    @NonNull public String assignedMemberName = "";
    public long dueAt;
    public boolean reminderEnabled;
    public int reminderMinutesBefore = 30;
    public long createdAt;
    public long updatedAt;
    public long completedAt;
    @NonNull public String createdByUid = "";
    @NonNull public String updatedByUid = "";
    @NonNull public String completedByName = "";
    @NonNull public String sourceType = "FAMILY_TASK";
    @NonNull public String sourceRecordId = "";
    public boolean shared = true;
}
