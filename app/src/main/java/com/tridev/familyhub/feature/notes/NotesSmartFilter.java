package com.tridev.familyhub.feature.notes;

import com.tridev.familyhub.data.local.entity.NoteEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NotesSmartFilter {
    private NotesSmartFilter() { }
    public static boolean completed(NoteEntry note) {
        return "COMPLETED".equalsIgnoreCase(note.collaborationStatus.trim());
    }
    public static List<NoteEntry> apply(List<NoteEntry> source, int status, int type,
                                        String category, int sort, String query) {
        List<NoteEntry> result = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (NoteEntry note : source) {
            if (note.isArchived != (status == 4)) continue;
            if (status == 1 && completed(note) || status == 2 && !completed(note)
                    || status == 3 && !note.isPinned) continue;
            if (type == 1 && !NoteEntry.TYPE_TEXT.equals(note.noteType)
                    || type == 2 && !NoteEntry.TYPE_CHECKLIST.equals(note.noteType)) continue;
            if (!category.isEmpty() && !category.equalsIgnoreCase(note.category)) continue;
            String text = (note.title + " " + note.content + " " + note.category
                    + " " + note.assignedMemberName).toLowerCase(Locale.ROOT);
            if (!text.contains(needle)) continue;
            result.add(note);
        }
        Comparator<NoteEntry> comparator;
        if (sort == 1) comparator = Comparator.comparing(n -> n.title.toLowerCase(Locale.ROOT));
        else if (sort == 2) comparator = Comparator.comparingLong(n -> n.reminderAt > 0
                ? n.reminderAt : Long.MAX_VALUE);
        else comparator = Comparator.<NoteEntry, Boolean>comparing(n -> !n.isPinned)
                .thenComparing(Comparator.comparingLong((NoteEntry n) -> n.updatedAt).reversed());
        result.sort(comparator);
        return result;
    }
}
