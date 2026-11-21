package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
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
        messageActions(thread, message, null);
    }

    void messageActions(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, android.view.View anchor) {
        if (!ready(thread) || message.deleted()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (message.deleted()) return;
        addAction(labels, actions, "Reply", () -> fragment.replyTo(message));
        addAction(labels, actions, "React", () -> reactions(thread, message));
        addAction(labels, actions, message.pinned() ? "Unpin" : "Pin for this chat", () ->
                store.pin(thread, message, ok -> toast(ok ? (message.pinned() ? "Message unpinned" : "Message pinned")
                        : "Unable to change pin")));
        addAction(labels, actions, "Save for me", () -> chooseSavedAction(thread, message));
        if (store.own(message)) addAction(labels, actions, "Message info", () -> messageInfo(thread, message));
        if ("text".equals(message.kind())) {
            addAction(labels, actions, "Copy text", () -> {
                android.content.ClipboardManager clipboard =
                        context().getSystemService(android.content.ClipboardManager.class);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chat message", message.body));
                if (android.os.Build.VERSION.SDK_INT < 33) toast("Message copied");
            });
            addAction(labels, actions, "Forward text", () -> forwardText(thread, message));
            if (store.own(message)) {
                addAction(labels, actions, "Edit message", () -> editMessage(thread, message));
                addAction(labels, actions, "Delete message", () -> new ChatFormDialog(context())
                        .setTitle("Delete this message?")
                        .setMessage("The text will be removed for everyone in this chat.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, which) -> store.editText(thread, message, "", true,
                                ok -> { if (!ok) toast("Unable to delete message"); })).show());
            }
            addAction(labels, actions, "Save to Family Notes", () ->
                    openFeature(com.tridev.familyhub.feature.notes.NotesFragment.forChatMessage(message.body)));
            addAction(labels, actions, "Add to Grocery List", () ->
                    openFeature(com.tridev.familyhub.feature.grocery.GroceryFragment.forChatMessage(
                            message.body.substring(0, Math.min(message.body.length(), 160)))));
            addAction(labels, actions, "Create reminder", () -> openFeature(RemindersFragment.forChatMessage(message.body)));
            com.tridev.familyhub.data.local.entity.FamilyTask task = fragment.linkedTask(message);
            if (task == null) addAction(labels, actions, "Add to Family To-Do", () ->
                    openFeature(FamilyTasksFragment.forChatMessage(message.body, FamilyChatFragment.taskKey(message))));
            else addAction(labels, actions, "Open linked To-Do", () -> fragment.openLinkedTask(task));
        }
        if ("poll".equals(message.kind()) && !message.pollClosed() && store.own(message)) {
            addAction(labels, actions, "Pending votes", () -> pendingVotes(thread, message));
        }
        chooseAnchored(anchor, "Message actions", labels, actions);
    }

    private void forwardText(FamilyChatStore.ThreadState source, FamilyChatStore.Message message) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (FamilyChatStore.ThreadState target : store.threads()) {
            if (target == source || !store.canUse(target)) continue;
            String title = store.title(target);
            addAction(labels, actions, title, () -> ChatSendButton.stylePositive(new ChatFormDialog(context())
                    .setTitle("Forward message?")
                    .setMessage("Send this text to " + title + "?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forward", (dialog, which) -> {
                        if (!ready(source) || !ready(target)) return;
                        store.loadMessage(source, message.snapshot.getKey(), current -> {
                            if (!ready(source) || !ready(target)) return;
                            if (current == null || current.deleted() || !"text".equals(current.kind())) {
                                toast("Message is no longer available");
                                return;
                            }
                            store.send(target, current.body, () -> toast("Unable to forward message"));
                        });
                    }).show()));
        }
        if (labels.isEmpty()) toast("No other chats available");
        else choose("Forward to", labels, actions);
    }

    private void messageInfo(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        store.loadMessage(thread, message.snapshot.getKey(), current -> {
            if (!ready(thread)) return;
            if (current == null || current.deleted()) {
                toast("Message is no longer available");
                return;
            }
            StringBuilder details = new StringBuilder(store.sending(current) ? "Sending…" : "Sent · " + dateTime(current.at));
            for (String recipient : store.participants(thread)) {
                if (recipient.equals(current.sender)) continue;
                com.google.firebase.database.DataSnapshot receipt =
                        current.snapshot.child("receipts").child(recipient);
                Long delivered = receipt.child("deliveredAt").getValue(Long.class);
                Long read = receipt.child("readAt").getValue(Long.class);
                details.append("\n\n").append(store.name(recipient));
                details.append("\n").append(delivered == null ? "Awaiting delivery" : "Delivered · " + dateTime(delivered));
                if (read != null) details.append("\nRead · ").append(dateTime(read));
            }
            new ChatFormDialog(context()).setTitle("Message info").setMessage(details)
                    .setPositiveButton("Done", null).show();
        });
    }

    void threadActions(FamilyChatStore.ThreadState thread, android.view.View anchor) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!thread.isGroup() && !thread.peer.equals(store.accountId())) {
            addAction(labels, actions, "Secure text chat", () -> ChatSecureText.open(fragment, store, thread));
        }
        addAction(labels, actions, "Search messages", () -> search(thread, false));
        addAction(labels, actions, "Pinned messages", () -> pinned(thread));
        addAction(labels, actions, "Post announcement", () -> newSharedMessage(thread, false));
        addAction(labels, actions, "Create checklist", () -> newSharedMessage(thread, true));
        addAction(labels, actions, "Share calendar event", () -> shareEvent(thread));
        addAction(labels, actions, "Notifications", () -> notifications(thread));
        addAction(labels, actions, "Schedule a message", () -> editSchedule(thread, null));
        addAction(labels, actions, "Scheduled messages", () -> showSchedule(thread));
        addAction(labels, actions, "Saved messages", () -> showSaved(thread));
        addAction(labels, actions, "Chat media", () -> search(thread, true));
        addAction(labels, actions, "Catch up on unread", () -> catchUp(thread));
        addAction(labels, actions, "@Mention a member", () -> mentions(thread));
        chooseAnchored(anchor, "Chat tools", labels, actions);
    }

    private void shareEvent(FamilyChatStore.ThreadState thread) {
        new com.tridev.familyhub.data.repository.PlannerRepository(context()).loadUpcoming(50, items -> {
            if (!ready(thread)) return;
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (com.tridev.familyhub.data.local.entity.PlannerItem item : items) {
                if (!com.tridev.familyhub.data.local.entity.PlannerItem.TYPE_EVENT.equals(item.itemType)
                        || item.startAt <= 0 || item.title.trim().isEmpty()) continue;
                String label = item.title + " · " + dateTime(item.startAt);
                addAction(labels, actions, label, () -> new ChatFormDialog(context())
                        .setTitle("Share event?")
                        .setMessage(label)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Share", (dialog, which) -> {
                            if (!ready(thread)) return;
                            java.util.Map<String, Object> details = new java.util.HashMap<>();
                            details.put("kind", "event");
                            details.put("startsAt", item.startAt);
                            store.send(thread, item.title, details, null, () -> toast("Unable to share event"));
                        }).show());
            }
            if (labels.isEmpty()) toast("No upcoming Family Calendar events");
            else choose("Share calendar event", labels, actions);
        });
    }

    private void newSharedMessage(FamilyChatStore.ThreadState thread, boolean checklist) {
        LinearLayout content = panel();
        EditText title = new EditText(context());
        title.setHint(checklist ? "Checklist title" : "Announcement");
        title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(checklist ? 160 : 2000)});
        content.addView(ChatFormDialog.outlined(title));
        EditText entries = new EditText(context());
        if (checklist) {
            entries.setHint("One item per line · up to 8 items");
            entries.setMinLines(3);
            entries.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            entries.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1300)});
            content.addView(ChatFormDialog.outlined(entries));
        }
        AlertDialog dialog = new ChatFormDialog(context())
                .setTitle(checklist ? "New checklist" : "New announcement")
                .setView(scroll(content)).setNegativeButton("Cancel", null).setPositiveButton("Post", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ready(thread)) return;
            String body = title.getText().toString().trim();
            if (body.isEmpty()) { title.setError("Enter a title or announcement"); return; }
            java.util.Map<String, Object> details = new java.util.HashMap<>();
            details.put("kind", checklist ? "checklist" : "announcement");
            if (checklist) {
                java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
                for (String line : entries.getText().toString().split("\r?\n")) {
                    String value = line.trim();
                    if (value.isEmpty()) continue;
                    if (rows.size() >= 8 || value.length() > 160) {
                        entries.setError("Use up to 8 items, at most 160 characters each");
                        return;
                    }
                    rows.put(Integer.toString(rows.size()), value);
                }
                if (rows.isEmpty()) { entries.setError("Enter at least one item"); return; }
                details.put("entries", rows);
            }
            dialog.dismiss();
            store.send(thread, body, details, null, () -> toast("Unable to post"));
        });
    }

    private void notifications(FamilyChatStore.ThreadState thread) {
        String uid = store.accountId();
        Context context = context();
        int selected = ChatNotifications.mode(context, uid, thread);
        new ChatFormDialog(context).setTitle("Chat notifications")
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
        AlertDialog dialog = new ChatFormDialog(context()).setTitle("Scheduled messages")
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
                                new ChatFormDialog(context()).setTitle("Cancel this scheduled message?")
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
        content.addView(ChatFormDialog.outlined(body));
        long[] due = {entry == null ? 0L : entry.due};
        MaterialButton select = button(due[0] > 0 ? dateTime(due[0]) : "Choose date and time");
        select.setOnClickListener(view -> chooseDateTime(due[0], time -> {
            due[0] = time;
            select.setText(dateTime(time));
        }));
        content.addView(select);
        content.addView(label("This device sends when it is online and signed in. Android may delay delivery."));
        AlertDialog dialog = new ChatFormDialog(context()).setTitle(entry == null ? "Schedule message" : "Edit scheduled message")
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
        ChatDateTimePicker.pickDateTime(fragment, Math.max(initial, System.currentTimeMillis() + 60000L),
                true, selected);
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

    private void chooseSavedAction(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        store.saved(thread, snapshot -> {
            if (!ready(thread)) return;
            if (snapshot == null) { toast("Unable to sync saved messages"); return; }
            String key = message.snapshot.getKey();
            boolean saved = syncedKeys(thread, snapshot).contains(key);
            String action = saved ? "Remove from saved" : "Save to my account";
            new ChatFormDialog(context()).setTitle("Saved messages")
                    .setMessage(saved ? "This message is saved to your account." : "Save privately across your devices.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(action, (dialog, which) -> store.saveForMe(thread, key, !saved,
                            ok -> toast(!ok ? "Unable to update saved messages"
                                    : saved ? "Removed from saved messages" : "Saved to your account"))).show();
        });
    }

    private java.util.Set<String> syncedKeys(FamilyChatStore.ThreadState thread, com.google.firebase.database.DataSnapshot snapshot) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
            if (Boolean.TRUE.equals(child.getValue(Boolean.class))) keys.add(child.getKey());
        }
        for (String local : savedMessages(thread)) {
            if (!local.matches("[A-Za-z0-9_-]{1,128}") || snapshot.hasChild(local)) continue;
            keys.add(local);
            store.importSaved(thread, local);
        }
        return keys;
    }

    private void showSaved(FamilyChatStore.ThreadState thread) {
        store.saved(thread, snapshot -> {
            if (!ready(thread)) return;
            if (snapshot == null) { toast("Unable to sync saved messages"); return; }
            showSavedResults(thread, syncedKeys(thread, snapshot));
        });
    }

    private void showSavedResults(FamilyChatStore.ThreadState thread, java.util.Set<String> keys) {
        LinearLayout content = panel();
        TextView loading = label(keys.isEmpty() ? "No messages saved in this chat." : "Loading saved messages…");
        content.addView(loading);
        AlertDialog dialog = new ChatFormDialog(context()).setTitle("Saved messages")
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
        input.setHint("Message");
        input.setText(message.body);
        input.setSelection(input.length());
        LinearLayout content = panel();
        content.addView(ChatFormDialog.outlined(input));
        AlertDialog dialog = new ChatFormDialog(context()).setTitle("Edit message")
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
        new ChatFormDialog(context()).setTitle("React to message")
                .setItems(REACTIONS, (dialog, which) -> store.react(thread, message, REACTIONS[which],
                        ok -> { if (!ok) toast("Unable to update reaction"); }))
                .setNegativeButton("Cancel", null).show();
    }

    void openMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!ready(thread) || fragment.reveal(message.snapshot.getKey())) return;
        new ChatFormDialog(context()).setTitle(store.name(message.sender))
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
            new ChatFormDialog(context()).setTitle("Pinned messages")
                    .setView(scroll(content)).setPositiveButton("Done", null).show();
        });
    }

    private void search(FamilyChatStore.ThreadState thread, boolean media) {
        LinearLayout form = panel();
        EditText input = new EditText(context());
        input.setSingleLine(true);
        input.setHint(media ? "Filter attachments by name" : "Search words or names");
        form.addView(ChatFormDialog.outlined(input));
        int[] type = {0};
        MaterialButton chooseType = button(media ? "All attachments" : "All message types");
        chooseType.setOnClickListener(view -> new ChatFormDialog(context())
                .setTitle("Message type").setItems(TYPES, (dialog, which) -> {
                    type[0] = which;
                    chooseType.setText(TYPES[which]);
                }).show());
        form.addView(chooseType);
        long[] range = {0L, Long.MAX_VALUE};
        MaterialButton chooseDate = button("Any date");
        chooseDate.setOnClickListener(view -> ChatDateTimePicker.pickDate(fragment,
                "Search by date", range[0] > 0 ? range[0] : System.currentTimeMillis(), false, date -> {
                    Calendar day = Calendar.getInstance();
                    day.setTimeInMillis(date);
                    range[0] = date;
                    chooseDate.setText(DateFormat.getMediumDateFormat(context()).format(new Date(date)));
                    day.add(Calendar.DAY_OF_YEAR, 1);
                    range[1] = day.getTimeInMillis() - 1;
                }));
        form.addView(chooseDate);
        String title = media ? "Chat media" : "Search messages";
        new ChatFormDialog(context()).setTitle(title).setView(form)
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
        AlertDialog dialog = new ChatFormDialog(context()).setTitle(title)
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
        ChatFormDialog builder = new ChatFormDialog(context());
        builder
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
        androidx.appcompat.app.AlertDialog reminders = builder.show();
        if (!pending.isEmpty()) ChatSendButton.stylePositive(reminders);
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
        new ChatFormDialog(context()).setTitle("Unread catch-up")
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

    private android.view.View menuAnchor;

    private void chooseAnchored(android.view.View anchor, String title, List<String> labels, List<Runnable> actions) {
        if (anchor == null || !anchor.isAttachedToWindow()) {
            anchor = fragment.getView();
            if (anchor == null || !anchor.isAttachedToWindow()) return;
        }
        menuAnchor = anchor;
        LinearLayout rows = panel();
        rows.setBackground(menuShape(0xfff4faf8, 0xffd4dee2, 14));
        ScrollView scroll = scroll(rows);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(context());
        popup.setContentView(scroll);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(menuDp(12));
        int width = 0;
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = actions.get(i);
            TextView row = label(labels.get(i));
            row.setTextSize(16);
            row.setTextColor(0xff1f2a31);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinHeight(menuDp(46));
            row.setPadding(menuDp(12), menuDp(6), menuDp(12), menuDp(6));
            row.setBackground(menuShape(0xfffcffff, 0xffd4dee2, 10));
            row.setOnClickListener(v -> {
                popup.dismiss();
                action.run();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = menuDp(4);
            rows.addView(row, params);
            width = Math.max(width, (int) Math.ceil(row.getPaint().measureText(labels.get(i))));
        }
        android.graphics.Rect visible = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        popup.setWidth(Math.min(visible.width() - menuDp(16), width + menuDp(54)));
        boolean centered = anchor == fragment.getView();
        int available = centered ? visible.height() - menuDp(32) : popup.getMaxAvailableHeight(anchor);
        popup.setHeight(Math.min(Math.max(menuDp(54), available), menuDp(24 + 50 * labels.size())));
        if (centered) popup.showAtLocation(anchor, android.view.Gravity.CENTER, 0, 0);
        else popup.showAsDropDown(anchor, 0, menuDp(4), android.view.Gravity.END);
    }

    private android.graphics.drawable.GradientDrawable menuShape(int fill, int edge, int radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(menuDp(1), edge);
        shape.setCornerRadius(menuDp(radius));
        return shape;
    }

    private int menuDp(int value) {
        return Math.round(value * context().getResources().getDisplayMetrics().density);
    }

    private void choose(String title, List<String> labels, List<Runnable> actions) {
        chooseAnchored(menuAnchor, title, labels, actions);
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
