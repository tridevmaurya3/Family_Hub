package com.tridev.familyhub.feature.notes;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class NotesSmartFilterTest {
    private NoteEntry note(String title, String status, boolean archived) {
        NoteEntry note = new NoteEntry(); note.title = title; note.collaborationStatus = status;
        note.isArchived = archived; note.category = "Family"; return note;
    }
    @Test public void statusFiltersKeepArchivedAndCompletedSeparate() {
        NoteEntry pending = note("One", "PENDING", false), done = note("Two", "COMPLETED", false),
                archived = note("Three", "PENDING", true);
        java.util.List<NoteEntry> notes = Arrays.asList(pending, done, archived);
        assertEquals(Arrays.asList(pending), NotesSmartFilter.apply(notes, 1, 0, "", 0, ""));
        assertEquals(Arrays.asList(done), NotesSmartFilter.apply(notes, 2, 0, "", 0, ""));
        assertEquals(Arrays.asList(archived), NotesSmartFilter.apply(notes, 4, 0, "", 0, ""));
    }
    @Test public void typeCategorySearchAndSortCombineWithoutMutatingSource() {
        NoteEntry checklist = note("Shopping", "PENDING", false), text = note("Alpha", "DRAFT", false);
        checklist.noteType = NoteEntry.TYPE_CHECKLIST; checklist.content = "Buy milk"; checklist.isPinned = true;
        assertEquals(Arrays.asList(checklist), NotesSmartFilter.apply(Arrays.asList(text, checklist),
                0, 2, "family", 1, "MILK"));
        assertEquals(Arrays.asList(checklist, text), NotesSmartFilter.apply(Arrays.asList(text, checklist), 0, 0, "", 0, ""));
        assertEquals(Arrays.asList(text, checklist), NotesSmartFilter.apply(Arrays.asList(checklist, text), 0, 0, "", 1, ""));
    }
    @Test public void reminderSortPlacesUnscheduledNotesLast() {
        NoteEntry soon = note("Soon", "PENDING", false), later = note("Later", "PENDING", false), noTime = note("None", "PENDING", false);
        soon.reminderAt = 100; later.reminderAt = 200;
        assertEquals(Arrays.asList(soon, later, noTime), NotesSmartFilter.apply(Arrays.asList(noTime, later, soon), 0, 0, "", 2, ""));
    }
}
