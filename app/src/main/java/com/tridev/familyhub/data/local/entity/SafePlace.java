package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "safe_places", indices = {
        @Index(value = {"name", "latitude", "longitude"}, unique = true),
        @Index("alertsEnabled")
})
public class SafePlace {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String name = "";
    @NonNull public String placeType = "CUSTOM";
    public double latitude;
    public double longitude;
    public float radiusMeters;
    @NonNull public String memberUid = "";
    public boolean alertsEnabled = true;
    public long createdAt;
    public long updatedAt;
}
