package com.tridev.familyhub.feature.tasks;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FamilyTaskSubtasksTest {
    @Test public void plainNotesRemainUnchanged() {
        String notes = "Call family\nKeep this note.";
        assertEquals(notes, FamilyTaskSubtasks.decode(notes).notes);
        assertTrue(FamilyTaskSubtasks.decode(notes).items.isEmpty());
        assertEquals(notes, FamilyTaskSubtasks.encode(notes, Collections.emptyList()));
    }
    @Test public void notesAndUnicodeSubtasksRoundTrip() {
        String notes = "Shopping details\nKeep receipt";
        java.util.List<String> items = Arrays.asList("दूध लाना", "Collect medicine");
        FamilyTaskSubtasks.Content result = FamilyTaskSubtasks.decode(FamilyTaskSubtasks.encode(notes, items));
        assertEquals(notes, result.notes);
        assertEquals(items, result.items);
    }
    @Test public void subtasksWithoutNotesRoundTrip() {
        FamilyTaskSubtasks.Content result = FamilyTaskSubtasks.decode(
                FamilyTaskSubtasks.encode("", Arrays.asList("One", "Two")));
        assertEquals("", result.notes);
        assertEquals(Arrays.asList("One", "Two"), result.items);
    }
    @Test public void ordinaryHeadingIsNotParsedAsSubtasks() {
        String notes = "Subtasks:\nCall family\nOther notes";
        assertEquals(notes, FamilyTaskSubtasks.decode(notes).notes);
        assertTrue(FamilyTaskSubtasks.decode(notes).items.isEmpty());
    }
    @Test public void switchingToSingleRetainsOnlyUserNotes() {
        FamilyTaskSubtasks.Content result = FamilyTaskSubtasks.decode(
                FamilyTaskSubtasks.encode("Keep notes", Arrays.asList("One")));
        assertEquals("Keep notes", FamilyTaskSubtasks.encode(result.notes, Collections.emptyList()));
    }
}
