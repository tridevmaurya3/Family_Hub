package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Embedded;

/** Property profile plus the display name of its linked family owner. */
public class PropertyWithOwner {

    @Embedded
    @NonNull
    public PropertyEntry property = new PropertyEntry();

    @NonNull
    public String ownerName = "";
}
