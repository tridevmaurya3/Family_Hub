package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Metadata for a document selected through Android's secure document picker. */
@Entity(tableName = "documents", indices = {@Index("category"), @Index("expiryAt")})
public class DocumentEntry {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String title = "";
    @NonNull public String category = "Other";
    @NonNull public String contentUri = "";
    @NonNull public String mimeType = "";
    public long expiryAt;
    public long createdAt;
}
