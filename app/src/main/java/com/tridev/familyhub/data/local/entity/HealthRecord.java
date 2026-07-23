package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A private health observation linked to one local family member. */
@Entity(
        tableName = "health_records",
        foreignKeys = @ForeignKey(
                entity = FamilyMember.class,
                parentColumns = "id",
                childColumns = "familyMemberId",
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("familyMemberId"),
                @Index("recordType"),
                @Index("recordedAt")
        }
)
public class HealthRecord {

    public static final String TYPE_MEDICINE = "MEDICINE";
    public static final String TYPE_CONDITION = "CONDITION";
    public static final String TYPE_ALLERGY = "ALLERGY";
    public static final String TYPE_MEASUREMENT = "MEASUREMENT";
    public static final String TYPE_APPOINTMENT = "APPOINTMENT";
    public static final String TYPE_VACCINATION = "VACCINATION";
    public static final String TYPE_OTHER = "OTHER";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long familyMemberId;

    @NonNull
    public String recordType = TYPE_OTHER;

    @NonNull
    public String title = "";

    /** Examples: 120/80 mmHg, 72 kg, 1 tablet daily. */
    @NonNull
    public String value = "";

    @NonNull
    public String notes = "";

    /** Epoch timestamp selected for the observation or appointment. */
    public long recordedAt;

    public long createdAt;
}
