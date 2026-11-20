package com.tridev.familyhub.feature.chat;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.cards.ModuleOverviewView;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.profile.FamilyAvatarStore;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class FamilyChatFragment extends Fragment {
    private static final int INK = 0xff222629;
    private static final int BLUE = 0xff146da9;
    private static final int GREEN = 0xff16765f;
    private static final int MUTED = 0xff59646d;
    private static final int EDGE = 0xffd1dade;
    private static final int WHITE = 0xffffffff;
    private final FamilyChatStore store = FamilyChatStore.get();
    private final ChatVoiceNotes voiceNotes = new ChatVoiceNotes(this, store);
    private final ChatAttachments attachments = new ChatAttachments(this, store, voiceNotes);
    private final ChatSmartActions smart = new ChatSmartActions(this, store, attachments);
    private final java.util.Map<String, String> mentions = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> unreadOnOpen = new java.util.HashSet<>();
    private String replyKey;
    private String replyPreview;
    private LinearLayout replyBanner;
    private TextView replyText;
    private MaterialButton toolsButton;
    private MaterialButton pinBanner;
    private com.tridev.familyhub.data.repository.FamilyTaskRepository taskRepository;
    private final java.util.Map<String, com.tridev.familyhub.data.local.entity.FamilyTask> linkedTasks =
            new java.util.HashMap<>();
    private final ChatGroupScreens groupScreens = new ChatGroupScreens(this, store, this::open);
    private final Runnable observer = this::render;
    private final FamilyAvatarStore.Listener avatarObserver = this::render;
    private LinearLayout headerAvatar;
    private final List<View> bubbles = new ArrayList<>();
    private LinearLayout screen, rows, bottom;
    private ModuleOverviewView header;
    private ScrollView scroll;
    private EditText search, draft;
    private MaterialButton send, newGroup;
    private OnBackPressedCallback backCallback;
    private String peer;
    private String lastMessageKey;
    private boolean choosingLiveRecipient;

    public static FamilyChatFragment forLiveShare() {
        FamilyChatFragment result = new FamilyChatFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("share_live", true);
        result.setArguments(arguments);
        return result;
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) choosingLiveRecipient = state.getBoolean("share_live");
        else choosingLiveRecipient = getArguments() != null && getArguments().getBoolean("share_live");
        if (state != null) {
            peer = state.getString("chat_peer");
            attachments.restoreState(state);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("chat_peer", peer);
        out.putString("chat_reply_key", replyKey);
        out.putString("chat_reply_preview", replyPreview);
        out.putStringArrayList("chat_mentions", new ArrayList<>(mentions.keySet()));
        out.putBoolean("share_live", choosingLiveRecipient);
        attachments.saveState(out);
        if (draft != null) out.putString("chat_draft", draft.getText().toString());
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                       @Nullable Bundle state) {
        screen = column();
        screen.setPadding(dp(10), dp(8), dp(10), dp(6));
        header = (ModuleOverviewView) inflater.inflate(R.layout.view_family_chat_header, screen, false);
        newGroup = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        newGroup.setTextSize(12);
        newGroup.setMaxLines(2);
        newGroup.setMinWidth(0);
        newGroup.setText("New group");
        newGroup.setAllCaps(false);
        newGroup.setOnClickListener(v -> {
            if (choosingLiveRecipient) {
                cancelLiveRecipient();
            } else {
                groupScreens.showCreate();
            }
        });
        header.addHeaderAction(newGroup);
        headerAvatar = new LinearLayout(requireContext());
        header.addHeaderAction(headerAvatar);
        toolsButton = flatButton("⋮");
        toolsButton.setContentDescription("Chat tools");
        toolsButton.setMinWidth(0);
        toolsButton.setMinimumWidth(0);
        toolsButton.setPadding(0, 0, 0, 0);
        toolsButton.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(48)));
        toolsButton.setOnClickListener(view -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) smart.threadActions(thread);
        });
        header.addHeaderAction(toolsButton);
        screen.addView(header);

        search = new EditText(requireContext());
        search.setSingleLine();
        search.setTextSize(15);
        search.setHint("Search chats");
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(card(WHITE, EDGE, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = dp(8);
        screen.addView(search, searchParams);
        search.addTextChangedListener(watcher(this::render));
        pinBanner = flatButton("Pinned messages");
        pinBanner.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pinBanner.setMaxLines(1);
        pinBanner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pinBanner.setOnClickListener(view -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) smart.pinned(thread);
        });
        screen.addView(pinBanner, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(requireContext());
        rows = column();
        scroll.addView(rows);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (peer != null && b - t < oldB - oldT
                    && rows.getHeight() - scroll.getScrollY() <= oldB - oldT + dp(12)) {
                scroll.post(() -> { if (screen != null && peer != null) scroll.scrollTo(0, rows.getHeight()); });
            }
        });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> markVisibleRead());

        bottom = new LinearLayout(requireContext());
        bottom.setPadding(dp(6), dp(3), dp(6), dp(3));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(card(WHITE, EDGE, 15));
        draft = new EditText(requireContext());
        draft.setHint("Write a message");
        draft.setTextSize(15);
        draft.setMaxLines(4);
        draft.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draft.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        draft.setBackground(null);
        draft.setPadding(dp(6), dp(8), dp(6), dp(8));
        MaterialButton attach = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        attach.setIconResource(R.drawable.ic_chat_attachment);
        attach.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        attach.setIconPadding(0);
        attach.setIconSize(dp(22));
        attach.setContentDescription("Add attachment");
        attach.setPadding(0, 0, 0, 0);
        attach.setOnClickListener(v -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) attachments.show(thread);
        });
        bottom.addView(attach, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bottom.addView(draft, new LinearLayout.LayoutParams(0, -2, 1));
        send = new MaterialButton(requireContext());
        send.setText("Send");
        send.setAllCaps(false);
        send.setContentDescription("Send message");
        bottom.addView(send);
        replyBanner = new LinearLayout(requireContext());
        replyBanner.setGravity(Gravity.CENTER_VERTICAL);
        replyBanner.setPadding(dp(10), dp(2), dp(4), dp(2));
        replyBanner.setBackground(card(0xffe5f2fa, EDGE, 12));
        replyText = text("", 13, BLUE, true);
        replyText.setMaxLines(2);
        replyBanner.addView(replyText, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton cancelReply = flatButton("×");
        cancelReply.setContentDescription("Cancel reply");
        cancelReply.setOnClickListener(view -> clearReply());
        replyBanner.addView(cancelReply, new LinearLayout.LayoutParams(dp(48), dp(48)));
        screen.addView(replyBanner);
        screen.addView(bottom);
        if (state != null) {
            draft.setText(state.getString("chat_draft", ""));
            replyKey = state.getString("chat_reply_key");
            replyPreview = state.getString("chat_reply_preview");
            ArrayList<String> ids = state.getStringArrayList("chat_mentions");
            if (ids != null) for (String id : ids) mentions.put(id, null);
        }
        updateReplyBanner();
        screen.setFocusableInTouchMode(true);
        screen.requestFocus();

        send.setOnClickListener(v -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            String message = draft.getText().toString().trim();
            if (thread == null || thread.error || message.isEmpty()) return;
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            String savedReply = replyKey;
            String savedPreview = replyPreview;
            java.util.Map<String, String> savedMentions = new java.util.LinkedHashMap<>(mentions);
            if (replyKey != null) metadata.put("replyTo", replyKey);
            java.util.Map<String, Boolean> targets = new java.util.HashMap<>();
            for (String id : store.participants(thread)) {
                if (!mentions.containsKey(id) || id.equals(store.accountId())) continue;
                String name = mentions.get(id);
                if (message.contains("@" + (name == null ? store.name(id) : name))) targets.put(id, true);
            }
            if (!targets.isEmpty()) metadata.put("mentions", targets);
            draft.setText("");
            mentions.clear();
            clearReply();
            store.send(thread, message, metadata, null, () -> {
                if (screen == null) return;
                if (thread.peer.equals(peer) && draft.getText().length() == 0) {
                    draft.setText(message);
                    replyKey = savedReply;
                    replyPreview = savedPreview;
                    mentions.putAll(savedMentions);
                    updateReplyBanner();
                }
                Toast.makeText(requireContext(), "Unable to send message", Toast.LENGTH_SHORT).show();
            });
        });
        draft.addTextChangedListener(watcher(this::updateSend));
        return screen;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        backCallback = new OnBackPressedCallback(peer != null || choosingLiveRecipient) {
            @Override public void handleOnBackPressed() {
                if (choosingLiveRecipient) cancelLiveRecipient();
                else open(null);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
        view.getViewTreeObserver().addOnWindowFocusChangeListener(
                focused -> { if (focused) markVisibleRead(); });
        render();
    }

    @Override public void onViewStateRestored(@Nullable Bundle state) {
        super.onViewStateRestored(state);
        render();
    }

    @Override public void onStart() {
        super.onStart();
        store.observe(observer);
        taskRepository = new com.tridev.familyhub.data.repository.FamilyTaskRepository(requireContext());
        taskRepository.startRealtimeSync(new com.tridev.familyhub.data.repository.FamilyTaskRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull com.tridev.familyhub.data.local.entity.FamilyTask task) { loadLinkedTasks(); }
            @Override public void onRemoved(long id) { loadLinkedTasks(); }
        });
        loadLinkedTasks();
        avatars().addListener(avatarObserver);
        render();
    }

    @Override public void onResume() {
        super.onResume();
        if (scroll != null) scroll.post(this::markVisibleRead);
    }

    @Override public void onStop() {
        store.removeObserver(observer);
        if (taskRepository != null) taskRepository.stopRealtimeSync();
        taskRepository = null;
        linkedTasks.clear();
        avatars().removeListener(avatarObserver);
        attachments.stopPreviews();
        voiceNotes.stop();
        super.onStop();
    }

    @Override public void onDestroyView() {
        groupScreens.dismiss();
        screen = null;
        bubbles.clear();
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        attachments.close();
        voiceNotes.close();
        groupScreens.close();
        super.onDestroy();
    }

    boolean isStartedForVoice() {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    public boolean isConversationOpen() {
        return peer != null;
    }

    private MaterialButton flatButton(String title) {
        MaterialButton button = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        return button;
    }

    void replyTo(FamilyChatStore.Message message) {
        replyKey = message.snapshot.getKey();
        replyPreview = store.name(message.sender) + ": " + message.body;
        updateReplyBanner();
        focusDraft();
    }

    void mention(String uid) {
        String name = store.name(uid);
        mentions.put(uid, name);
        int cursor = Math.max(0, draft.getSelectionStart());
        draft.getText().insert(cursor, "@" + name + " ");
        focusDraft();
    }

    private void focusDraft() {
        draft.requestFocus();
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.ViewCompat.getWindowInsetsController(draft);
        if (controller != null) controller.show(androidx.core.view.WindowInsetsCompat.Type.ime());
    }

    private void clearReply() {
        replyKey = null;
        replyPreview = null;
        updateReplyBanner();
    }

    private void updateReplyBanner() {
        if (replyBanner == null) return;
        replyBanner.setVisibility(replyKey != null && peer != null ? View.VISIBLE : View.GONE);
        replyText.setText("Replying to " + replyPreview);
    }

    boolean reveal(String key) {
        View bubble = rows.findViewWithTag(key);
        if (bubble == null) return false;
        scroll.smoothScrollTo(0, bubble.getTop());
        bubble.announceForAccessibility("Original message");
        return true;
    }

    boolean wasUnread(FamilyChatStore.Message message) {
        return unreadOnOpen.contains(message.snapshot.getKey()) || store.unread(message);
    }

    private void open(@Nullable String destination) {
        if (destination == null) {
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.ViewCompat.getWindowInsetsController(screen);
            if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime());
            draft.clearFocus();
        }
        peer = destination;
        unreadOnOpen.clear();
        FamilyChatStore.ThreadState opening = store.thread(peer);
        if (opening != null) for (FamilyChatStore.Message message : opening.messages) {
            if (store.unread(message)) unreadOnOpen.add(message.snapshot.getKey());
        }
        mentions.clear();
        clearReply();
        if (requireActivity() instanceof com.tridev.familyhub.feature.main.MainActivity) {
            ((com.tridev.familyhub.feature.main.MainActivity) requireActivity()).refreshChatInsets();
        }
        lastMessageKey = null;
        draft.setText("");
        render();
    }

    private void updateSend() {
        if (send == null || draft == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        send.setEnabled(thread != null && !thread.error && !draft.getText().toString().trim().isEmpty());
    }

    private void cancelLiveRecipient() {
        choosingLiveRecipient = false;
        render();
    }

    private void render() {
        if (screen == null) return;
        boolean inThread = peer != null;
        if (backCallback != null) backCallback.setEnabled(inThread || choosingLiveRecipient);
        FamilyChatStore.ThreadState thread = store.thread(peer);
        if (inThread || choosingLiveRecipient) {
            header.setNavigationAction(R.drawable.ic_arrow_back, R.string.back, v -> {
                if (inThread) open(null);
                else cancelLiveRecipient();
            });
        } else {
            header.setNavigationAction(R.drawable.ic_menu_hamburger, R.string.feature_menu_title, v -> {
                if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).showFeatureMenu();
            });
        }
        newGroup.setText(choosingLiveRecipient ? "Cancel" : "New group");
        newGroup.setVisibility(!inThread && (choosingLiveRecipient || !store.groupCandidates().isEmpty())
                ? View.VISIBLE : View.GONE);
        newGroup.setEnabled(choosingLiveRecipient || store.online());
        boolean chatReady = thread != null && !thread.error;
        toolsButton.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        pinBanner.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        pinBanner.setText("Pinned messages");
        if (chatReady) for (int i = thread.messages.size() - 1; i >= 0; i--) {
            FamilyChatStore.Message pinned = thread.messages.get(i);
            if (!pinned.pinned() || pinned.deleted()) continue;
            pinBanner.setText("Pinned · " + pinned.body);
            break;
        }
        headerAvatar.removeAllViews();
        if (thread != null) headerAvatar.addView(threadAvatar(thread, 40));
        header.showDashboardActions(newGroup.getVisibility() != View.VISIBLE && thread == null);
        search.setVisibility(inThread ? View.GONE : View.VISIBLE);
        bottom.setVisibility(inThread && thread != null && !thread.error ? View.VISIBLE : View.GONE);
        header.setTitle(choosingLiveRecipient ? "Share to chat" : thread == null ? "Family Chat" : store.title(thread));
        String currentStatus = store.status();
        header.setDetail(!currentStatus.isEmpty() ? currentStatus
                : thread != null && thread.isGroup() ? store.groupMembers(thread).size() + " members · Group info"
                : store.online() ? "Live · Family sync" : "Offline · messages send when reconnected");
        updateSend();
        int oldY = scroll.getScrollY();
        rows.removeAllViews();
        bubbles.clear();
        if (!inThread) {
            renderList();
            scroll.post(() -> { if (screen != null && peer == null) scroll.scrollTo(0, oldY); });
        } else if (thread != null && !thread.error) {
            renderThread(thread, oldY);
        } else {
            addLine(text(thread != null ? "Chat is unavailable" : "Connecting to conversation…", 14, MUTED, false));
        }
    }

    private void renderList() {
        String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<FamilyChatStore.ThreadState> direct = new ArrayList<>();
        List<FamilyChatStore.ThreadState> groups = new ArrayList<>();
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (!store.title(thread).toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (FamilyChatStore.GROUP.equals(thread.peer)) {
                addLine(text("Group", 14, BLUE, true));
                addConversation(thread);
            } else if (thread.isGroup()) groups.add(thread);
            else direct.add(thread);
        }
        groups.sort((first, second) -> Long.compare(lastTime(second), lastTime(first)));
        if (!groups.isEmpty()) addLine(text("Private groups", 14, BLUE, true));
        for (FamilyChatStore.ThreadState thread : groups) addConversation(thread);
        direct.sort((first, second) -> Long.compare(lastTime(second), lastTime(first)));
        boolean showOwn = !choosingLiveRecipient && store.accountId() != null && store.status().isEmpty()
                && "you".contains(filter);
        if (!direct.isEmpty() || showOwn) addLine(text("Direct messages", 14, BLUE, true));
        for (FamilyChatStore.ThreadState thread : direct) addConversation(thread);
        if (showOwn) addOwnProfile();
        if (rows.getChildCount() == 0 && store.status().isEmpty()) {
            addLine(text("No matching chats", 14, MUTED, false));
        }
    }

    private FamilyAvatarStore avatars() { return FamilyAvatarStore.get(requireContext()); }

    private View threadAvatar(FamilyChatStore.ThreadState thread, int size) {
        Runnable openInfo = thread.isGroup() ? () -> groupScreens.showGroup(thread)
                : () -> groupScreens.showMember(thread.peer, groupScreens::dismiss);
        return groupScreens.avatar(store.title(thread), thread.isGroup() ? thread.photo : avatars().photo(thread.peer),
                size, choosingLiveRecipient ? null : openInfo);
    }

    private LinearLayout avatarRow(View avatar, LinearLayout content) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(card(WHITE, EDGE, 16));
        row.addView(avatar);
        content.setPadding(dp(12), 0, 0, 0);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void addOwnProfile() {
        String uid = store.accountId();
        Runnable show = () -> groupScreens.showMember(uid, groupScreens::dismiss);
        LinearLayout content = column();
        content.addView(text("You", 16, INK, true));
        content.addView(text("Your profile", 13, MUTED, false));
        LinearLayout row = avatarRow(groupScreens.avatar("You", avatars().ownPhoto(), 44, show), content);
        row.setOnClickListener(v -> show.run());
        addLine(row);
    }

    private void addConversation(FamilyChatStore.ThreadState thread) {
        LinearLayout card = column();
        TextView name = text(store.title(thread), 16, INK, true);
        card.addView(name);
        if (thread.messages.isEmpty()) {
            card.addView(text(thread.error ? "Chat is unavailable" : "Start a conversation", 13, MUTED, false));
        } else {
            FamilyChatStore.Message latest = thread.messages.get(thread.messages.size() - 1);
            TextView body = text((store.own(latest) ? "You: " : store.name(latest.sender) + ": ")
                    + latest.body, 14, MUTED, false);
            body.setMaxLines(1);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(body);
            int unread = 0;
            for (FamilyChatStore.Message message : thread.messages) if (store.unread(message)) unread++;
            String direction = store.own(latest)
                    ? store.sending(latest) ? "Sending · " : "Sent · "
                    : "Received · ";
            String detail = direction + dateTime(store.listTime(latest)) + (unread == 0 ? "" : "  ·  " + unread + " new");
            card.addView(text(detail, 12, unread == 0 ? MUTED : BLUE, unread != 0));
        }
        LinearLayout row = avatarRow(threadAvatar(thread, 44), card);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            if (choosingLiveRecipient) {
                if (!store.canUse(thread)) return;
                attachments.confirmLiveShare(thread, () -> {
                    choosingLiveRecipient = false;
                    open(thread.peer);
                });
            } else {
                open(thread.peer);
            }
        });
        addLine(row);
    }

    private void loadLinkedTasks() {
        if (taskRepository == null) return;
        com.tridev.familyhub.data.repository.FamilyTaskRepository source = taskRepository;
        String uid = store.accountId();
        source.loadAll("", tasks -> {
            if (taskRepository != source || screen == null || !java.util.Objects.equals(uid, store.accountId())) return;
            linkedTasks.clear();
            for (com.tridev.familyhub.data.local.entity.FamilyTask task : tasks) {
                if (!"CHAT_MESSAGE".equals(task.sourceType) || task.sourceRecordId.isEmpty()) continue;
                com.tridev.familyhub.data.local.entity.FamilyTask known = linkedTasks.get(task.sourceRecordId);
                if (known == null || task.createdAt > known.createdAt) linkedTasks.put(task.sourceRecordId, task);
            }
            render();
        });
    }

    static String taskKey(FamilyChatStore.Message message) {
        return java.util.UUID.nameUUIDFromBytes(message.snapshot.getRef().toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    com.tridev.familyhub.data.local.entity.FamilyTask linkedTask(FamilyChatStore.Message message) {
        return linkedTasks.get(taskKey(message));
    }

    void openLinkedTask(com.tridev.familyhub.data.local.entity.FamilyTask task) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openFeature(
                    com.tridev.familyhub.feature.tasks.FamilyTasksFragment.forLinkedTask(task.cloudId));
        }
    }

    private void renderThread(FamilyChatStore.ThreadState thread, int oldY) {
        String latestKey = thread.messages.isEmpty() ? null
                : thread.messages.get(thread.messages.size() - 1).snapshot.getKey();
        boolean goToBottom = !java.util.Objects.equals(lastMessageKey, latestKey);
        lastMessageKey = latestKey;
        if (thread.messages.isEmpty()) addLine(text("No messages yet", 14, MUTED, false));
        ChatAttachmentCards cards = new ChatAttachmentCards(requireContext(), attachments);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int compactWidth = Math.min(dp(250), Math.round(screenWidth * 0.44f));
        int compactMediaWidth = Math.max(1, compactWidth - dp(20));
        java.util.Map<String, FamilyChatStore.Message> known = new java.util.HashMap<>();
        for (FamilyChatStore.Message item : thread.messages) known.put(item.snapshot.getKey(), item);
        for (FamilyChatStore.Message message : thread.messages) {
            boolean own = store.own(message);
            LinearLayout bubble = column();
            bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
            bubble.setBackground(card(own ? 0xffe5f2fa : WHITE, EDGE, 15));
            bubble.setTag(message.snapshot.getKey());
            bubble.setOnLongClickListener(view -> {
                smart.messageActions(thread, message);
                return true;
            });
            if (!own && thread.isGroup()) {
                bubble.addView(text(store.name(message.sender), 13, GREEN, true));
            }
            String target = message.snapshot.child("replyTo").getValue(String.class);
            if (target != null) {
                FamilyChatStore.Message original = known.get(target);
                String quote = original == null ? "View original message" : store.name(original.sender) + ": " + original.body;
                TextView quoted = text("↪ " + quote, 13, BLUE, true);
                quoted.setMaxLines(2);
                quoted.setEllipsize(android.text.TextUtils.TruncateAt.END);
                quoted.setOnClickListener(view -> smart.openReply(thread, target));
                bubble.addView(quoted);
            }
            View attachment = cards.create(thread, message, compactMediaWidth);
            if (attachment != null) {
                bubble.addView(attachment, new LinearLayout.LayoutParams(-1, -2));
            } else {
                TextView messageText = text(message.body, 15, INK, false);
                messageText.setTextIsSelectable(true);
                bubble.addView(messageText);
            }
            if (!message.deleted() && store.accountId() != null && message.snapshot.child("mentions").child(store.accountId()).exists()) {
                bubble.addView(text("Mentioned you", 12, BLUE, true));
            }
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (com.google.firebase.database.DataSnapshot reaction : message.snapshot.child("reactions").getChildren()) {
                String emoji = reaction.getValue(String.class);
                if (emoji != null) counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
            }
            if (!message.deleted() && !counts.isEmpty()) {
                List<String> summary = new ArrayList<>();
                counts.forEach((emoji, count) -> summary.add(emoji + " " + count));
                bubble.addView(text(String.join("  ", summary), 13, MUTED, false));
            }
            com.tridev.familyhub.data.local.entity.FamilyTask task = linkedTask(message);
            if (task != null && !message.deleted()) {
                boolean done = com.tridev.familyhub.data.local.entity.FamilyTask.STATUS_COMPLETED.equals(task.status);
                String status = done ? "Done" : task.dueAt > 0 && task.dueAt < System.currentTimeMillis()
                        ? "Overdue" : "Pending";
                MaterialButton taskLink = flatButton("To-Do · " + status);
                taskLink.setOnClickListener(view -> openLinkedTask(task));
                bubble.addView(taskLink);
            }
            LinearLayout metadata = new LinearLayout(requireContext());
            metadata.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            String badge = message.edited() ? "Edited · " : "";
            if (message.pinned() && !message.deleted()) badge = "Pinned · " + badge;
            metadata.addView(text(badge + dateTime(message.at), 11, MUTED, false));
            TextView menu = text("  ⋮", 17, BLUE, true);
            menu.setContentDescription("Message actions");
            menu.setPadding(dp(4), dp(8), dp(4), dp(8));
            menu.setOnClickListener(view -> smart.messageActions(thread, message));
            metadata.addView(menu);
            if (own) {
                boolean seen = store.acknowledged(thread, message, true);
                boolean delivered = seen || store.acknowledged(thread, message, false);
                boolean sending = store.sending(message) && !delivered;
                TextView tick = text(sending ? "  ◷" : delivered ? "  ✓✓" : "  ✓",
                        13, seen ? GREEN : MUTED, true);
                tick.setContentDescription(seen ? "Seen" : delivered ? "Delivered" : sending ? "Sending" : "Sent");
                metadata.addView(tick);
            }
            bubble.addView(metadata);
            int bubbleWidth = attachment == null ? -2 : compactWidth;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(bubbleWidth, -2);
            params.gravity = own ? Gravity.END : Gravity.START;
            params.topMargin = dp(7);
            if (own) params.setMarginStart(dp(24));
            else params.setMarginEnd(dp(24));
            rows.addView(bubble, params);
            bubbles.add(bubble);
        }
        scroll.post(() -> {
            if (screen == null || !thread.peer.equals(peer)) return;
            if (goToBottom) scroll.scrollTo(0, rows.getHeight());
            else scroll.scrollTo(0, oldY);
            markVisibleRead();
        });
    }

    private void markVisibleRead() {
        if (screen == null || !isResumed() || peer == null || !screen.hasWindowFocus()) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        if (thread == null || bubbles.size() != thread.messages.size()) return;
        android.graphics.Rect viewport = new android.graphics.Rect();
        if (!scroll.getGlobalVisibleRect(viewport)) return;
        for (int i = 0; i < bubbles.size(); i++) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            if (bubbles.get(i).getGlobalVisibleRect(bounds) && android.graphics.Rect.intersects(viewport, bounds)) {
                store.receipt(thread, thread.messages.get(i), true);
            }
        }
    }

    private static long lastTime(FamilyChatStore.ThreadState thread) {
        return thread.messages.isEmpty() ? 0 : thread.messages.get(thread.messages.size() - 1).at;
    }

    private String dateTime(long millis) {
        Date date = new Date(millis);
        return DateFormat.getDateFormat(requireContext()).format(date) + " "
                + DateFormat.getTimeFormat(requireContext()).format(date);
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(requireContext());
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private void addLine(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        rows.addView(view, params);
    }

    private GradientDrawable card(int fill, int edge, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), edge);
        return drawable;
    }

    private TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
