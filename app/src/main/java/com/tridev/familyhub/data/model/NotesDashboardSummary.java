package com.tridev.familyhub.data.model;

import com.tridev.familyhub.data.local.entity.NoteEntry;
import java.util.List;

/** Counts active notes once each; completed and archived notes are not pending. */
public final class NotesDashboardSummary {
    private int active;
    private int pinned;
    private int pending;

    public static NotesDashboardSummary from(List<NoteEntry> notes) {
        NotesDashboardSummary summary = new NotesDashboardSummary();
        for (NoteEntry note : notes) {
            if (note.isArchived) continue;
            summary.active++;
            if (note.isPinned) summary.pinned++;
            if (!"COMPLETED".equalsIgnoreCase(note.collaborationStatus.trim())) summary.pending++;
        }
        return summary;
    }

    public void applyTo(DashboardStats stats) {
        stats.setActiveNotes(active);
        stats.setPinnedNotes(pinned);
        stats.setPendingNotes(pending);
    }
}
