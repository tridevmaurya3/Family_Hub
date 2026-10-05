package com.tridev.familyhub.feature.notes;
import org.junit.Test;
import static org.junit.Assert.*;
public class NotesChecklistTest {
    @Test public void oldPlainListsRemainUncheckedAndReadable() {
        assertEquals(2, NotesChecklist.parse("milk\r\n\nfruit").size());
        assertEquals(0, NotesChecklist.completed("milk\nfruit"));
        assertEquals("[ ] milk\n[x] fruit", NotesChecklist.toggle("milk\nfruit", 1, true));
    }
    @Test public void completionCanBeReversedWithoutLosingText() {
        String content = NotesChecklist.toggle("[X] first\n[ ] second", 1, true);
        assertEquals(2, NotesChecklist.completed(content));
        assertEquals("[ ] first\n[x] second", NotesChecklist.toggle(content, 0, false));
        assertEquals("first", NotesChecklist.parse(content).get(0).text);
    }
    @Test public void wholeNoteCompletionUpdatesEveryRow() {
        assertEquals("[x] one\n[x] two", NotesChecklist.setAll("one\ntwo", true));
        assertEquals("[ ] one\n[ ] two", NotesChecklist.setAll("[x] one\n[x] two", false));
        assertEquals("one", NotesChecklist.toggle("one", 99, true));
    }
}
