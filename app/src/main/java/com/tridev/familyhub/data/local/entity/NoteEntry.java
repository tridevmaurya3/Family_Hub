package com.tridev.familyhub.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A local family note or checklist. */
@Entity(
        tableName = "notes",
        indices = {
                @Index("category"),
                @Index("isPinned"),
                @Index("isArchived"),
                @Index("updatedAt")
        }
)
public class NoteEntry {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_CHECKLIST = "CHECKLIST";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String noteType = TYPE_TEXT;

    @NonNull
    public String title = "";

    /**
     * Plain note text or newline-separated checklist rows.
     * Encrypted secure notes will use a separate security-backed model later.
     */
    @NonNull
    public String content = "";

    @NonNull
    public String category = "";

    /** Design-system color key such as BLUE, GREEN, AMBER, PINK, or NEUTRAL. */
    @NonNull
    public String colorKey = "BLUE";

    public boolean isPinned;
    public boolean isArchived;

    public long createdAt;
    public long updatedAt;
}
