package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local-only family profile. Cloud sync can be added later without changing this feature's UI. */
@Entity(tableName = "family_members", indices = {@Index(value = {"name"})})
public class FamilyMember {

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

    public long createdAt;
}
