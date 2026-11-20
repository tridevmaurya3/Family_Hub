package com.tridev.familyhub.feature.chat;

import android.app.DatePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.reminders.RemindersFragment;
import com.tridev.familyhub.feature.tasks.FamilyTasksFragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class ChatSmartActions {
    private static final String[] REACTIONS = {"👍", "❤️", "😂", "🙏"};
    private static final String[] TYPES = {"All", "Text", "Photo", "File", "Location", "Poll", "Event", "Contact", "Voice note"};
    private final FamilyChatFragment fragment;
    private final FamilyChatStore store;
    private final ChatAttachments attachments;

    ChatSmartActions(FamilyChatFragment fragment, FamilyChatStore store, ChatAttachments attachments) {
        this.fragment = fragment;
        this.store = store;
        this.attachments = attachments;
    }

    void messageActions(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (message.deleted()) return;
        addAction(labels, actions, "Reply", () -> fragment.replyTo(message));
        addAction(labels, actions, "React", () -> reactions(thread, message));
        addAction(labels, actions, message.pinned() ? "Unpin" : "Pin for this chat", () ->
                store.pin(thread, message, ok -> toast(ok ? (message.pinned() ? "Message unpinned" : "Message pinned")
                        : "Unable to change pin")));
        String key = message.snapshot.getKey();
        boolean saved = savedMessages(thread).contains(key);
        addAction(labels, actions, saved ? "Remove from saved" : "Save for me", () -> {
            java.util.Set<String> keys = savedMessages(thread);
            if (!keys.remove(key)) keys.add(key);
            savedPreferences().edit().putStringSet(savedKey(thread), keys).apply();
            toast(saved ? "Removed from saved messages" : "Saved on this device");
        });
        if ("text".equals(message.kind())) {
            if (store.own(message)) {
                addAction(labels, actions, "Edit message", () -> editMessage(thread, message));
                addAction(labels, actions, "Delete message", () -> new MaterialAlertDialogBuilder(context())
                        .setTitle("Delete this message?")
                        .setMessage("The text will be removed for everyone in this chat.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, which) -> store.editText(thread, message, "", true,
                                ok -> { if (!ok) toast("Unable to delete message"); })).show());
            }
            addAction(labels, actions, "Create reminder", () -> openFeature(RemindersFragment.forChatMessage(message.body)));
            com.tridev.familyhub.data.local.entity.FamilyTask task = fragment.linkedTask(message);
            if (task == null) addAction(labels, actions, "Add to Family To-Do", () ->
                    openFeature(FamilyTasksFragment.forChatMessage(message.body, FamilyChatFragment.taskKey(message))));
            else addAction(labels, actions, "Open linked To-Do", () -> fragment.openLinkedTask(task));
        }
        if ("poll".equals(message.kind()) && !message.pollClosed() && store.own(message)) {
            addAction(labels, actions, "Pending votes", () -> pendingVotes(thread, message));
        }
        choose("Message actions", labels, actions);
    }

    void threadActions(FamilyChatStore.ThreadState thread) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        addAction(labels, actions, "Search messages", () -> search(thread, false));
        addAction(labels, actions, "Pinned messages", () -> pinned(thread));
        addAction(labels, actions, "Notifications", () -> notifications(thread));
        addAction(labels, actions, "Schedule a message", () -> editSchedule(thread, null));
        addAction(labels, actions, "Scheduled messages", () -> showSchedule(thread));
        addAction(labels, actions, "Saved messages", () -> showSaved(thread));
        addAction(labels, actions, "Chat media", () -> search(thread, true));
        addAction(labels, actions, "Catch up on unread", () -> catchUp(thread));
        addAction(labels, actions, "@Mention a member", () -> mentions(thread));
        choose("Chat tools", labels, actions);
    }

    private void notifications(FamilyChatStore.ThreadState thread) {
        String uid = store.accountId();
        Context context = context();
        int selected = ChatNotifications.mode(context, uid, thread);
        new MaterialAlertDialogBuilder(context).setTitle("Chat notifications")
                .setSingleChoiceItems(new String[]{"All new messages", "Mentions and replies", "Muted"}, selected,
                        (dialog, choice) -> {
                            if (!ready(thread) || !java.util.Objects.equals(uid, store.accountId())) return;
                            ChatNotifications.setMode(context, uid, thread, choice);
                            dialog.dismiss();
                            if (choice != ChatNotifications.MUTED) fragment.requestChatNotificationPermission();
                            toast(choice == ChatNotifications.MUTED ? "Chat muted" : "Preference saved on this device");
                        })
                .setNegativeButton("Done", null).show();
    }

    private void showSchedule(FamilyChatStore.ThreadState thread) {
        if (!ready(thread)) return;
        List<ChatScheduledMessages.Entry> entries = ChatScheduledMessages.list(context(), store.accountId(), thread.ref.toString());
        LinearLayout content = panel();
        if (entries.isEmpty()) content.addView(label("No scheduled messages on this device."));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context()).setTitle("Scheduled messages")
                .setView(scroll(content)).setNegativeButton("Done", null).show();
        for (ChatScheduledMessages.Entry entry : entries) {
            MaterialButton item = button(dateTime(entry.due) + (entry.sending ? " · Sending" : entry.due < System.currentTimeMillis() ? " · Due" : "")
                    + "\n" + entry.body);
            item.setMaxLines(3);
            item.setEnabled(!entry.sending);
            item.setOnClickListener(view -> {
                dialog.dismiss();
                choose("Scheduled message", java.util.Arrays.asList("Edit", "Cancel scheduled send"),
                        java.util.Arrays.asList(() -> editSchedule(thread, entry), () ->
                                new MaterialAlertDialogBuilder(context()).setTitle("Cancel this scheduled message?")
                                        .setNegativeButton("Keep", null).setPositiveButton("Cancel send", (d, w) -> {
                                            if (!ChatScheduledMessages.cancel(context(), entry)) toast("Message is no longer pending");
                                            showSchedule(thread);
                                        }).show()));
            });
            content.addView(item);
        }
    }

    private void editSchedule(FamilyChatStore.ThreadState thread, ChatScheduledMessages.Entry entry) {
        if (!ready(thread)) return;
        LinearLayout content = panel();
        EditText body = new EditText(context());
        body.setHint("Write a message");
        body.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        if (entry != null) body.setText(entry.body);
        content.addView(body);
        long[] due = {entry == null ? 0L : entry.due};
        MaterialButton select = button(due[0] > 0 ? dateTime(due[0]) : "Choose date and time");
        select.setOnClickListener(view -> chooseDateTime(due[0], time -> {
            due[0] = time;
            select.setText(dateTime(time));
        }));
        content.addView(select);
        content.addView(label("This device sends when it is online and signed in. Android may delay delivery."));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context()).setTitle(entry == null ? "Schedule message" : "Edit scheduled message")
                .setView(content).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ready(thread)) return;
            String text = body.getText().toString().trim();
            if (text.isEmpty()) { body.setError("Enter a message"); return; }
            if (due[0] <= System.currentTimeMillis()) { toast("Choose a future time"); return; }
            if (ChatScheduledMessages.put(context(), store.accountId(), thread.ref.toString(), text, due[0], entry)) {
                dialog.dismiss();
                toast("Message scheduled on this device");
            } else toast("Unable to save scheduled message");
        });
    }

    private void chooseDateTime(long initial, java.util.function.LongConsumer selected) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(Math.max(initial, System.currentTimeMillis() + 60000L));
        DatePickerDialog picker = new DatePickerDialog(context(), (date, year, month, number) ->
                new android.app.TimePickerDialog(context(), (clock, hour, minute) -> {
                    day.clear();
                    day.set(year, month, number, hour, minute);
                    selected.accept(day.getTimeInMillis());
                }, day.get(Calendar.HOUR_OF_DAY), day.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(context())).show(),
                day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(System.currentTimeMillis());
        picker.show();
    }

    private String dateTime(long time) {
        Date date = new Date(time);
        return DateFormat.getDateFormat(context()).format(date) + " " + DateFormat.getTimeFormat(context()).format(date);
    }

    private android.content.SharedPreferences savedPreferences() {
        return context().getSharedPreferences("family_chat_saved", Context.MODE_PRIVATE);
    }

    private String savedKey(FamilyChatStore.ThreadState thread) {
        return store.accountId() + "|" + thread.ref.toString();
    }

    private java.util.Set<String> savedMessages(FamilyChatStore.ThreadState thread) {
        return new java.util.LinkedHashSet<>(savedPreferences().getStringSet(savedKey(thread),
                java.util.Collections.emptySet()));
    }

    private void showSaved(FamilyChatStore.ThreadState thread) {
        java.util.Set<String> keys = savedMessages(thread);
        LinearLayout content = panel();
        TextView loading = label(keys.isEmpty() ? "No messages saved on this device." : "Loading saved messages…");
        content.addView(loading);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context()).setTitle("Saved messages")
                .setView(scroll(content)).setPositiveButton("Done", null).show();
        int[] remaining = {keys.size()};
        for (String key : keys) store.loadMessage(thread, key, message -> {
            if (!dialog.isShowing() || !ready(thread)) return;
            if (message != null && !message.deleted()) {
                content.addView(resultRow(message, () -> {
                    dialog.dismiss();
                    openMessage(thread, message);
                }));
            }
            if (--remaining[0] == 0) {
                if (content.getChildCount() > 1) content.removeView(loading);
                else loading.setText("Saved messages are no longer available.");
            }
        });
    }

    private void editMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        EditText input = new EditText(context());
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        input.setText(message.body);
        input.setSelection(input.length());
        LinearLayout content = panel();
        content.addView(input);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context()).setTitle("Edit message")
                .setView(content).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Enter a message"); return; }
            if (value.equals(message.body)) { dialog.dismiss(); return; }
            view.setEnabled(false);
            store.editText(thread, message, value, false, ok -> {
                if (!dialog.isShowing()) return;
                if (ok) dialog.dismiss();
                else { view.setEnabled(true); toast("Unable to edit message"); }
            });
        });
    }

    private void mentions(FamilyChatStore.ThreadState thread) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (String uid : store.participants(thread)) {
            if (uid == null || uid.equals(store.accountId())) continue;
            addAction(names, actions, store.name(uid), () -> fragment.mention(uid));
        }
        if (names.isEmpty()) toast("No members available to mention");
        else choose("Mention a member", names, actions);
    }

    private void reactions(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        new MaterialAlertDialogBuilder(context()).setTitle("React to message")
                .setItems(REACTIONS, (dialog, which) -> store.react(thread, message, REACTIONS[which],
                        ok -> { if (!ok) toast("Unable to update reaction"); }))
                .setNegativeButton("Cancel", null).show();
    }

    void openMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!ready(thread) || fragment.reveal(message.snapshot.getKey())) return;
        new MaterialAlertDialogBuilder(context()).setTitle(store.name(message.sender))
                .setMessage(time(message) + "\n\n" + message.body)
                .setPositiveButton("Close", null)
                .setNeutralButton("text".equals(message.kind()) ? "Actions" : "Open attachment", (dialog, which) -> {
                    if ("text".equals(message.kind())) messageActions(thread, message);
                    else attachments.open(thread, message);
                }).show();
    }

    void openReply(FamilyChatStore.ThreadState thread, String key) {
        store.loadMessage(thread, key, message -> {
            if (!ready(thread)) return;
            if (message == null) toast("Original message unavailable");
            else openMessage(thread, message);
        });
    }

    void pinned(FamilyChatStore.ThreadState thread) {
        store.pinned(thread, messages -> {
            if (!ready(thread)) return;
            if (messages == null) {
                toast("Unable to load pinned messages");
                return;
            }
            LinearLayout content = panel();
            for (FamilyChatStore.Message message : messages) {
                content.addView(resultRow(message, () -> openMessage(thread, message)));
            }
            if (messages.isEmpty()) content.addView(label("No pinned messages. Open a message's actions to pin it."));
            new MaterialAlertDialogBuilder(context()).setTitle("Pinned messages")
                    .setView(scroll(content)).setPositiveButton("Done", null).show();
        });
    }

    private void search(FamilyChatStore.ThreadState thread, boolean media) {
        LinearLayout form = panel();
        EditText input = new EditText(context());
        input.setSingleLine(true);
        input.setHint(media ? "Filter attachments by name" : "Search words or names");
        form.addView(input);
        int[] type = {0};
        MaterialButton chooseType = button(media ? "All attachments" : "All message types");
        chooseType.setOnClickListener(view -> new MaterialAlertDialogBuilder(context())
                .setTitle("Message type").setItems(TYPES, (dialog, which) -> {
                    type[0] = which;
                    chooseType.setText(TYPES[which]);
                }).show());
        form.addView(chooseType);
        long[] range = {0L, Long.MAX_VALUE};
        MaterialButton chooseDate = button("Any date");
        chooseDate.setOnClickListener(view -> {
            Calendar day = Calendar.getInstance();
            new DatePickerDialog(context(), (picker, year, month, date) -> {
                day.clear();
                day.set(year, month, date);
                range[0] = day.getTimeInMillis();
                chooseDate.setText(DateFormat.getMediumDateFormat(context()).format(new Date(range[0])));
                day.add(Calendar.DAY_OF_YEAR, 1);
                range[1] = day.getTimeInMillis() - 1;
            }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH)).show();
        });
        form.addView(chooseDate);
        String title = media ? "Chat media" : "Search messages";
        new MaterialAlertDialogBuilder(context()).setTitle(title).setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(media ? "Browse" : "Search", (dialog, which) ->
                        showResults(thread, title, input.getText().toString(), type[0], media, range[0], range[1]))
                .show();
    }

    private void showResults(FamilyChatStore.ThreadState thread, String title, String input, int type,
                             boolean media, long from, long to) {
        final String query = input.trim().toLowerCase(Locale.ROOT);
        LinearLayout content = panel();
        LinearLayout matches = panel();
        content.addView(matches);
        MaterialButton more = button("Load older messages");
        content.addView(more);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context()).setTitle(title)
                .setView(scroll(content)).setNegativeButton("Done", null).show();
        FamilyChatStore.Message[] cursor = {null};
        boolean[] first = {true};
        Runnable load = () -> {
            more.setEnabled(false);
            store.history(thread, cursor[0], from, to, page -> {
                if (!dialog.isShowing() || !ready(thread)) return;
                if (page == null) {
                    more.setText("Retry loading messages");
                    more.setEnabled(true);
                    return;
                }
                if (!page.isEmpty()) cursor[0] = page.get(page.size() - 1);
                for (FamilyChatStore.Message message : page) {
                    if (!matchesType(message, type, media)) continue;
                    String searchable = (message.body + " " + store.name(message.sender)).toLowerCase(Locale.ROOT);
                    if (!searchable.contains(query)) continue;
                    matches.addView(resultRow(message, () -> {
                        dialog.dismiss();
                        openMessage(thread, message);
                    }));
                }
                if (first[0] && matches.getChildCount() == 0) matches.addView(label("No matches in these messages."));
                else if (matches.getChildCount() > 1 && matches.getChildAt(0).getTag() == null) matches.removeViewAt(0);
                first[0] = false;
                more.setEnabled(page.size() == 100);
                more.setText(page.size() == 100 ? "Load older messages" : "End of results");
            });
        };
        more.setOnClickListener(view -> load.run());
        load.run();
    }

    private static boolean matchesType(FamilyChatStore.Message message, int type, boolean media) {
        String kind = message.kind();
        if (media && "text".equals(kind)) return false;
        if (type == 0) return true;
        if (type == 4) return "location".equals(kind) || "live".equals(kind);
        return TYPES[type].toLowerCase(Locale.ROOT).equals(kind)
                || (type == 8 && "voice".equals(kind));
    }

    private void pendingVotes(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        List<String> pending = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String uid : store.participants(thread)) {
            if (uid == null || uid.equals(store.accountId()) || message.snapshot.child("votes").child(uid).exists()) continue;
            if (store.thread(uid) != null) {
                pending.add(uid);
                names.add(store.name(uid));
            }
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context())
                .setTitle("Pending votes · " + pending.size())
                .setMessage(names.isEmpty() ? "Everyone else has voted." : String.join("\n", names))
                .setNegativeButton("Done", null);
        if (!pending.isEmpty()) builder.setPositiveButton("Send private reminders", (dialog, which) ->
                store.loadMessage(thread, message.snapshot.getKey(), current -> {
                    if (!ready(thread)) return;
                    if (current == null || current.pollClosed()) {
                        toast("Poll closed or current votes unavailable");
                        return;
                    }
                    List<String> participants = store.participants(thread);
                    int sent = 0;
                    for (String uid : pending) {
                        if (!participants.contains(uid) || current.snapshot.child("votes").child(uid).exists()) continue;
                        FamilyChatStore.ThreadState direct = store.thread(uid);
                        if (direct != null && store.canUse(direct)) {
                            store.send(direct, "Poll reminder in " + store.title(thread) + ": " + current.body,
                                    () -> toast("Unable to remind " + store.name(uid)));
                            sent++;
                        }
                    }
                    if (sent == 0) toast("No selected members still need a reminder");
                }));
        builder.show();
    }

    private void catchUp(FamilyChatStore.ThreadState thread) {
        LinearLayout content = panel();
        List<FamilyChatStore.Message> unread = new ArrayList<>();
        for (int i = thread.messages.size() - 1; i >= 0; i--) {
            FamilyChatStore.Message message = thread.messages.get(i);
            if (fragment.wasUnread(message)) unread.add(message);
        }
        if (unread.isEmpty()) {
            content.addView(label("No unread messages in the recent chat history."));
        } else {
            content.addView(label(unread.size() + " unread in recent chat history · excerpts"));
            List<FamilyChatStore.Message> featured = new ArrayList<>();
            addHighlights(thread, content, unread, featured, "Dates and deadlines",
                    "(?:tomorrow|today|deadline|due|tonight|\\d{1,2}:\\d{2}|आज|कल|बजे|तक)");
            addHighlights(thread, content, unread, featured, "Requests and tasks",
                    "(?:please|remind|bring|need to|can you|ला देना|कर देना|याद दिला|कृपया|ले आना)");
            addHighlights(thread, content, unread, featured, "Decisions and confirmations",
                    "(?:confirmed|approved|decided|agreed|booked|तय हुआ|पक्का|सहमति|तय है)");
            int others = 0;
            for (FamilyChatStore.Message message : unread) {
                if (featured.contains(message)) continue;
                if (others++ >= 8) break;
                if (others == 1) content.addView(label("Other recent messages"));
                content.addView(resultRow(message, () -> openMessage(thread, message)));
            }
        }
        new MaterialAlertDialogBuilder(context()).setTitle("Unread catch-up")
                .setView(scroll(content)).setPositiveButton("Done", null).show();
    }

    private void addHighlights(FamilyChatStore.ThreadState thread, LinearLayout content,
                               List<FamilyChatStore.Message> unread, List<FamilyChatStore.Message> featured,
                               String heading, String pattern) {
        java.util.regex.Pattern cue = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        int count = 0;
        for (FamilyChatStore.Message message : unread) {
            if (featured.contains(message) || !cue.matcher(message.body).find()) continue;
            if (count++ == 0) content.addView(label(heading));
            featured.add(message);
            content.addView(resultRow(message, () -> openMessage(thread, message)));
            if (count == 3) break;
        }
    }

    private MaterialButton resultRow(FamilyChatStore.Message message, Runnable action) {
        String body = message.body.length() > 135 ? message.body.substring(0, 132) + "…" : message.body;
        MaterialButton row = button(store.name(message.sender) + " · " + time(message) + "\n" + body);
        row.setMaxLines(4);
        row.setTag(message.snapshot.getKey());
        row.setOnClickListener(view -> action.run());
        return row;
    }

    private String time(FamilyChatStore.Message message) {
        Date date = new Date(message.at);
        return DateFormat.getDateFormat(context()).format(date) + " " + DateFormat.getTimeFormat(context()).format(date);
    }

    private void openFeature(androidx.fragment.app.Fragment feature) {
        if (fragment.getActivity() instanceof MainActivity) ((MainActivity) fragment.getActivity()).openFeature(feature);
    }

    private static void addAction(List<String> labels, List<Runnable> actions, String label, Runnable action) {
        labels.add(label);
        actions.add(action);
    }

    private void choose(String title, List<String> labels, List<Runnable> actions) {
        new MaterialAlertDialogBuilder(context()).setTitle(title).setItems(labels.toArray(new String[0]),
                (dialog, which) -> actions.get(which).run()).setNegativeButton("Close", null).show();
    }

    private LinearLayout panel() {
        LinearLayout content = new LinearLayout(context());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(context().getResources().getDisplayMetrics().density * 12);
        content.setPadding(padding, padding, padding, padding);
        return content;
    }

    private ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(context());
        scroll.addView(content);
        return scroll;
    }

    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(context(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private TextView label(String text) {
        TextView label = new TextView(context());
        label.setText(text);
        return label;
    }

    private Context context() { return fragment.requireContext(); }
    private boolean ready(FamilyChatStore.ThreadState thread) {
        return fragment.isAdded() && fragment.getView() != null && store.canUse(thread);
    }
    private void toast(String text) {
        if (fragment.isAdded()) Toast.makeText(context(), text, Toast.LENGTH_SHORT).show();
    }
}
