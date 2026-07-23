package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Embedded;

/** Health record plus the display name of its linked family member. */
public class HealthRecordWithMember {

    @Embedded
    @NonNull
    public HealthRecord record = new HealthRecord();

    @NonNull
    public String memberName = "";
}
