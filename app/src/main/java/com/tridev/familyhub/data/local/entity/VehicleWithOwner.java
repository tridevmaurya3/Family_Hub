package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Embedded;

/** Vehicle profile plus the display name of its linked family owner. */
public class VehicleWithOwner {

    @Embedded
    @NonNull
    public Vehicle vehicle = new Vehicle();

    @NonNull
    public String ownerName = "";
}
