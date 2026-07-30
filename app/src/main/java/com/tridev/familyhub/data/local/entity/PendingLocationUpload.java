package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Encrypted, device-local location update waiting for Firebase connectivity.
 *
 * Exact coordinates and member identifiers exist only inside encryptedPayload.
 */
@Entity(
        tableName = "pending_location_uploads",
        indices = {
                @Index(value = {"createdAt"}),
                @Index(value = {"nextAttemptAt"})
        }
)
public class PendingLocationUpload {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String encryptedPayload = "";

    public long createdAt;

    public int attemptCount;

    public long nextAttemptAt;
}
