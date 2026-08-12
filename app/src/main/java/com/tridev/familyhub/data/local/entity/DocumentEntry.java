package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Metadata for a document selected through Android's secure document picker. */
@Entity(tableName = "documents", indices = {
        @Index("category"), @Index("expiryAt"), @Index("memberName"),
        @Index("documentNumber"), @Index("deletedAt"), @Index("fingerprint")
})
public class DocumentEntry {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String title = "";
    @NonNull public String category = "Other";
    @NonNull public String contentUri = "";
    @NonNull public String mimeType = "";
    @NonNull public String documentNumber = "";
    @NonNull public String issuer = "";
    @NonNull public String memberName = "";
    @NonNull public String tags = "";
    @NonNull public String notes = "";
    @NonNull public String searchableText = "";
    @NonNull public String fingerprint = "";
    @NonNull public String linkedModule = "";
    public boolean emergency;
    public long issuedAt;
    public long expiryAt;
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long previousVersionId;
}
