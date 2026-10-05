package com.tridev.familyhub.feature.notes;

import java.util.ArrayList;
import java.util.List;

/** Existing plain newline lists remain unchecked; checked rows use readable text markers. */
public final class NotesChecklist {
    public static final class Item {
        public final String text;
        public final boolean checked;
        Item(String text, boolean checked) { this.text = text; this.checked = checked; }
    }
    private NotesChecklist() { }
    public static List<Item> parse(String content) {
        List<Item> items = new ArrayList<>();
        for (String line : (content == null ? "" : content).split("\\r?\\n")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            boolean checked = text.regionMatches(true, 0, "[x]", 0, 3);
            if (checked || text.startsWith("[ ]")) text = text.substring(3).trim();
            if (!text.isEmpty()) items.add(new Item(text, checked));
        }
        return items;
    }
    public static String toggle(String content, int index, boolean checked) {
        List<Item> items = parse(content);
        if (index < 0 || index >= items.size()) return content;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) result.append('\n');
            Item item = items.get(i);
            result.append((i == index ? checked : item.checked) ? "[x] " : "[ ] ").append(item.text);
        }
        return result.toString();
    }
    public static String setAll(String content, boolean checked) {
        StringBuilder result = new StringBuilder();
        for (Item item : parse(content)) {
            if (result.length() > 0) result.append('\n');
            result.append(checked ? "[x] " : "[ ] ").append(item.text);
        }
        return result.toString();
    }
    public static int completed(String content) {
        int completed = 0;
        for (Item item : parse(content)) if (item.checked) completed++;
        return completed;
    }
}
