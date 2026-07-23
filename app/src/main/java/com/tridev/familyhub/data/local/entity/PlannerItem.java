package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A private family event or task stored in the local planner. */
@Entity(
        tableName = "planner_items",
        foreignKeys = @ForeignKey(
                entity = FamilyMember.class,
                parentColumns = "id",
                childColumns = "assignedMemberId",
                onDelete = ForeignKey.SET_NULL,
                onUpdate = ForeignKey.CASCADE
        ),
        indices = {
                @Index("assignedMemberId"),
                @Index("itemType"),
                @Index("startAt"),
                @Index("isCompleted")
        }
)
public class PlannerItem {

    public static final String TYPE_EVENT = "EVENT";
    public static final String TYPE_TASK = "TASK";

    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";

    public static final String REPEAT_NONE = "NONE";
    public static final String REPEAT_DAILY = "DAILY";
    public static final String REPEAT_WEEKLY = "WEEKLY";
    public static final String REPEAT_MONTHLY = "MONTHLY";
    public static final String REPEAT_YEARLY = "YEARLY";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String itemType = TYPE_EVENT;

    @NonNull
    public String title = "";

    @NonNull
    public String notes = "";

    /** Start/due time in epoch milliseconds. */
    public long startAt;

    /** Optional event end time. Zero means no separate end time. */
    public long endAt;

    public boolean isAllDay;

    @NonNull
    public String location = "";

    /** Null means the item belongs to the whole family. */
    @Nullable
    public Long assignedMemberId;

    @NonNull
    public String priority = PRIORITY_NORMAL;

    @NonNull
    public String repeatType = REPEAT_NONE;

    public boolean isCompleted;

    public boolean isReminderEnabled;

    /** Minutes before startAt; used when planner notifications are added. */
    public int reminderMinutesBefore;

    public long createdAt;
    public long updatedAt;
}
