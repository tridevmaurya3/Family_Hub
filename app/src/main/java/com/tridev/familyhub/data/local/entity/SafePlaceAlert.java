package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Privacy-safe local history entry; exact coordinates are never stored. */
@Entity(tableName = "safe_place_alerts", indices = {
        @Index("occurredAt"),
        @Index("isRead"),
        @Index(
                value = {"placeId", "transitionType", "deduplicationBucket"},
                unique = true
        )
})
public class SafePlaceAlert {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String placeId = "";
    @NonNull public String transitionType = "";
    public long occurredAt;
    public long deduplicationBucket;
    public boolean isRead;
}
