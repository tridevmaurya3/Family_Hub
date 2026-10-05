package com.tridev.familyhub.data.model;

import com.tridev.familyhub.data.local.entity.NoteEntry;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class NotesDashboardSummaryTest {
    private NoteEntry note(String status, boolean archived, boolean pinned) {
        NoteEntry note = new NoteEntry();
        note.collaborationStatus = status;
        note.isArchived = archived;
        note.isPinned = pinned;
        return note;
    }

    @Test public void archivedAndCompletedNotesAreExcludedFromPending() {
        DashboardStats stats = new DashboardStats();
        NotesDashboardSummary.from(Arrays.asList(
                note("PENDING", false, true), note("IN_PROGRESS", false, false),
                note("DRAFT", false, false), note("ACCEPTED", false, false),
                note(" completed ", false, true), note("PENDING", true, true)
        )).applyTo(stats);
        assertEquals(4, stats.getPendingNotes());
        assertEquals(5, stats.getActiveNotes());
        assertEquals(2, stats.getPinnedNotes());
    }

    @Test public void checklistCountsAsOneNoteAndRefreshClearsStaleCounts() {
        NoteEntry checklist = note("PENDING", false, false);
        checklist.noteType = NoteEntry.TYPE_CHECKLIST;
        checklist.content = "one\ntwo\nthree";
        DashboardStats stats = new DashboardStats();
        NotesDashboardSummary.from(Collections.singletonList(checklist)).applyTo(stats);
        assertEquals(1, stats.getPendingNotes());
        checklist.collaborationStatus = "COMPLETED";
        NotesDashboardSummary.from(Collections.singletonList(checklist)).applyTo(stats);
        assertEquals(0, stats.getPendingNotes());
        NotesDashboardSummary.from(Collections.emptyList()).applyTo(stats);
        assertEquals(0, stats.getActiveNotes());
    }
}
