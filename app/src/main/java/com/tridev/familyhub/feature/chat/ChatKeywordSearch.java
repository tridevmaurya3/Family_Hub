package com.tridev.familyhub.feature.chat;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatKeywordSearch {
    private final FamilyChatStore store;
    private final Map<FamilyChatStore.ThreadState, Map<String, FamilyChatStore.Message>> history =
            new LinkedHashMap<>();
    private int generation;
    boolean loading;
    boolean incomplete;

    ChatKeywordSearch(FamilyChatStore store) {
        this.store = store;
    }

    void clear() {
        generation++;
        loading = false;
        incomplete = false;
        history.clear();
    }

    void load(List<FamilyChatStore.ThreadState> threads, Runnable changed) {
        loading = true;
        loadNext(new ArrayDeque<>(threads), generation, changed);
    }

    private void loadNext(ArrayDeque<FamilyChatStore.ThreadState> queue, int request, Runnable changed) {
        if (request != generation) return;
        FamilyChatStore.ThreadState thread = queue.poll();
        if (thread == null) {
            loading = false;
            changed.run();
            return;
        }
        loadPage(thread, null, queue, request, changed);
    }

    private void loadPage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message before,
                          ArrayDeque<FamilyChatStore.ThreadState> queue, int request, Runnable changed) {
        store.history(thread, before, 0L, Long.MAX_VALUE, page -> {
            if (request != generation) return;
            if (page == null) {
                incomplete = true;
            } else {
                Map<String, FamilyChatStore.Message> messages = history.computeIfAbsent(thread,
                        ignored -> new LinkedHashMap<>());
                for (FamilyChatStore.Message message : page) messages.put(message.snapshot.getKey(), message);
                if (page.size() == 100) {
                    loadPage(thread, page.get(page.size() - 1), queue, request, changed);
                    return;
                }
            }
            changed.run();
            loadNext(queue, request, changed);
        });
    }

    List<FamilyChatStore.Message> matches(FamilyChatStore.ThreadState thread, Pattern keyword,
                                           Predicate<FamilyChatStore.Message> hidden) {
        Map<String, FamilyChatStore.Message> messages = new LinkedHashMap<>();
        Map<String, FamilyChatStore.Message> older = history.get(thread);
        if (older != null) messages.putAll(older);
        for (FamilyChatStore.Message message : thread.messages) messages.put(message.snapshot.getKey(), message);
        List<FamilyChatStore.Message> result = new ArrayList<>();
        for (FamilyChatStore.Message message : messages.values()) {
            if (!message.deleted() && !hidden.test(message) && keyword.matcher(message.body).find()) result.add(message);
        }
        result.sort(java.util.Comparator.comparingLong((FamilyChatStore.Message message) -> message.at).reversed()
                .thenComparing(message -> message.snapshot.getKey(), java.util.Comparator.reverseOrder()));
        return result;
    }

    static Pattern keyword(String query) {
        return Pattern.compile(query.trim(), Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static CharSequence highlight(String body, Pattern keyword) {
        SpannableString text = new SpannableString(body);
        Matcher match = keyword.matcher(body);
        while (match.find()) text.setSpan(new BackgroundColorSpan(0xffffe88d), match.start(), match.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
