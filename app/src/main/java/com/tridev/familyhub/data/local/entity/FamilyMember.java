package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local-only family profile. Cloud sync can be added later without changing this feature's UI. */
@Entity(tableName = "family_members", indices = {@Index(value = {"name"})})
public class FamilyMember {

    public static final String ROLE_GUARDIAN = "GUARDIAN";
    public static final String ROLE_ADULT = "ADULT";
    public static final String ROLE_CHILD = "CHILD";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    @NonNull
    public String relation = "";

    @NonNull
    public String phone = "";

    @NonNull
    public String email = "";

    /** Stored as yyyy-MM-dd to keep the database locale-independent. */
    @NonNull
    public String dateOfBirth = "";

    @NonNull
    public String note = "";

    /** Persisted content URI selected through Android's document picker. */
    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String profilePhotoUri = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String gender = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String bloodGroup = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String address = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String emergencyContactName = "";

    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String emergencyContactPhone = "";

    @NonNull
    @ColumnInfo(defaultValue = "'ADULT'")
    public String familyRole = ROLE_ADULT;

    @ColumnInfo(defaultValue = "0")
    public boolean isGuardian;

    public long createdAt;
}
