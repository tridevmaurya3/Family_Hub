package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted credential record. Secret fields are never stored as plaintext. */
@Entity(tableName = "password_entries", indices = {@Index("title")})
public class PasswordEntry {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String title = "";
    @NonNull public String website = "";
    @NonNull public String usernameEncrypted = "";
    @NonNull public String passwordEncrypted = "";
    @NonNull public String notesEncrypted = "";
    public long createdAt;
}
