package com.tridev.familyhub.feature.tasks;

import java.util.ArrayList;
import java.util.List;

/** Plain-text subtask list stored in the existing, synced task notes field. */
public final class FamilyTaskSubtasks {
    private static final String HEADER = "Subtasks:\n";
    private static final String BULLET = "• ";

    public static final class Content {
        public final String notes;
        public final List<String> items;
        Content(String notes, List<String> items) {
            this.notes = notes;
            this.items = items;
        }
    }

    public static Content decode(String value) {
        String source = value == null ? "" : value;
        int marker = source.lastIndexOf("\n\n" + HEADER);
        int start = marker >= 0 ? marker + 2 : source.startsWith(HEADER) ? 0 : -1;
        if (start < 0) return new Content(source, new ArrayList<>());
        String[] lines = source.substring(start + HEADER.length()).split("\n", -1);
        List<String> items = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith(BULLET) || line.substring(BULLET.length()).trim().isEmpty()) {
                return new Content(source, new ArrayList<>());
            }
            items.add(line.substring(BULLET.length()));
        }
        return new Content(start == 0 ? "" : source.substring(0, marker), items);
    }

    public static String encode(String notes, List<String> items) {
        String plain = notes == null ? "" : notes;
        if (items.isEmpty()) return plain;
        StringBuilder result = new StringBuilder(plain);
        if (!plain.isEmpty()) result.append("\n\n");
        result.append(HEADER);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) result.append('\n');
            result.append(BULLET).append(items.get(i).replace('\n', ' ').replace('\r', ' ').trim());
        }
        return result.toString();
    }

    private FamilyTaskSubtasks() { }
}
