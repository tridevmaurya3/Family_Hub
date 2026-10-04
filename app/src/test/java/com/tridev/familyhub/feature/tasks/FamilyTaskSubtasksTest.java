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

    @Test public void checkboxStateSurvivesRoundTrip() {
        FamilyTaskSubtasks.Content content = FamilyTaskSubtasks.decode(
                FamilyTaskSubtasks.encode("Keep notes", Arrays.asList("दूध", "Medicine"),
                        Arrays.asList(true, false)));
        assertEquals("Keep notes", content.notes);
        assertEquals(Arrays.asList("दूध", "Medicine"), content.items);
        assertEquals(Arrays.asList(true, false), content.completed);
    }
    @Test public void oldBulletListsAreUncheckedAndCanBeToggled() {
        String original = "Keep notes\n\nSubtasks:\n• One\n• Two";
        FamilyTaskSubtasks.Content old = FamilyTaskSubtasks.decode(original);
        assertEquals(Arrays.asList(false, false), old.completed);
        FamilyTaskSubtasks.Content checked = FamilyTaskSubtasks.decode(
                FamilyTaskSubtasks.withCompleted(original, 1, true));
        assertEquals("Keep notes", checked.notes);
        assertEquals(Arrays.asList("One", "Two"), checked.items);
        assertEquals(Arrays.asList(false, true), checked.completed);
        FamilyTaskSubtasks.Content reopened = FamilyTaskSubtasks.decode(
                FamilyTaskSubtasks.withCompleted(
                        FamilyTaskSubtasks.encode(checked.notes, checked.items, checked.completed), 1, false));
        assertEquals(Arrays.asList(false, false), reopened.completed);
    }
    @Test public void invalidCheckboxIndexDoesNotChangeNotes() {
        String original = "Subtasks:\n• One";
        assertEquals(original, FamilyTaskSubtasks.withCompleted(original, 2, true));
    }
}
