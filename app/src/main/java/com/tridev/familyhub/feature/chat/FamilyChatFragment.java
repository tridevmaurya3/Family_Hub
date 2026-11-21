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
    private LinearLayout replyContent;
    private MaterialButton toolsButton;
    private MaterialButton pinBanner;
    private String pendingNotificationConversation;
    private final androidx.activity.result.ActivityResultLauncher<String> chatNotificationPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (!granted && isAdded()) Toast.makeText(requireContext(),
                                "Allow notifications in system settings to receive chat alerts", Toast.LENGTH_SHORT).show();
                    });
    private com.tridev.familyhub.data.repository.FamilyTaskRepository taskRepository;
    private final java.util.Map<String, com.tridev.familyhub.data.local.entity.FamilyTask> linkedTasks =
            new java.util.HashMap<>();
    private final ChatGroupScreens groupScreens = new ChatGroupScreens(this, store, this::open);
    private final Runnable observer = this::render;
    private final FamilyAvatarStore.Listener avatarObserver = this::render;
    private LinearLayout headerAvatar;
    private final List<View> bubbles = new ArrayList<>();
    private LinearLayout screen, rows, bottom;
    private android.widget.FrameLayout pageRoot;
    private View secondaryPage;
    private Runnable secondaryBack;
    private java.util.function.BooleanSupplier secondaryAllowed;
    private ModuleOverviewView header;
    private ScrollView scroll;
    private final ChatDictation draftVoice = new ChatDictation(this);
    private EditText search, draft;
    private LinearLayout chatFilters;
    private ChatFilter chatFilter = ChatFilter.ALL;

    private enum ChatFilter {
        ALL("All"), UNREAD("Unread"), GROUPS("Groups"), DIRECT("Direct");

        final String label;

        ChatFilter(String label) { this.label = label; }
    }
    private final ChatStatus chatStatus = new ChatStatus(this, store);
    private MaterialButton send, mic, newGroup, statusButton;
    private OnBackPressedCallback backCallback;
    private String peer;
    private String lastMessageKey;
    private android.widget.FrameLayout jumpToLatest;
    private TextView newMessageCount;
    private boolean scrollingUp;
    private final Runnable showJumpAfterScroll = () -> {
        scrollingUp = false;
        updateJumpToLatest();
    };
    private boolean choosingLiveRecipient;

    public static FamilyChatFragment forLiveShare() {
        FamilyChatFragment result = new FamilyChatFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("share_live", true);
        result.setArguments(arguments);
        return result;
    }

    public static FamilyChatFragment forNotification(String conversation) {
        FamilyChatFragment fragment = new FamilyChatFragment();
        Bundle args = new Bundle();
        args.putString("chat_notification_ref", conversation);
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Bundle args = state != null ? state : getArguments();
        pendingNotificationConversation = args == null ? null : args.getString("chat_notification_ref");
        if (state != null) choosingLiveRecipient = state.getBoolean("share_live");
        else choosingLiveRecipient = getArguments() != null && getArguments().getBoolean("share_live");
        if (state != null) {
            peer = state.getString("chat_peer");
            String selectedFilter = state.getString("chat_filter", ChatFilter.ALL.name());
            for (ChatFilter filter : ChatFilter.values()) {
                if (filter.name().equals(selectedFilter)) chatFilter = filter;
            }
            attachments.restoreState(state);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("chat_peer", peer);
        out.putString("chat_filter", chatFilter.name());
        out.putString("chat_notification_ref", pendingNotificationConversation);
        out.putString("chat_reply_key", replyKey);
        out.putString("chat_reply_preview", replyPreview);
        out.putStringArrayList("chat_mentions", new ArrayList<>(mentions.keySet()));
        out.putBoolean("share_live", choosingLiveRecipient);
        attachments.saveState(out);
        if (draft != null) out.putString("chat_draft", draft.getText().toString());
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                       @Nullable Bundle state) {
        pageRoot = new android.widget.FrameLayout(requireContext());
        screen = column();
        pageRoot.addView(screen, new android.widget.FrameLayout.LayoutParams(-1, -1));
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
        statusButton = flatButton("Status");
        statusButton.setOnClickListener(v -> chatStatus.open());
        header.addHeaderAction(statusButton);
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
            if (thread != null && store.canUse(thread)) smart.threadActions(thread, view);
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
        chatFilters = new LinearLayout(requireContext());
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(-1, -2);
        filterSize.topMargin = dp(5);
        screen.addView(chatFilters, filterSize);
        for (ChatFilter filter : ChatFilter.values()) {
            TextView chip = text(filter.label, 13, GREEN, false);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine();
            chip.setMinHeight(dp(44));
            chip.setFocusable(true);
            LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(0, -2, 1);
            if (filter != ChatFilter.ALL) chipSize.setMarginStart(dp(5));
            chatFilters.addView(chip, chipSize);
            chip.setOnClickListener(v -> {
                if (chatFilter == filter) return;
                chatFilter = filter;
                render();
                scroll.post(() -> {
                    if (screen != null && peer == null) scroll.scrollTo(0, 0);
                });
            });
        }
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
        android.widget.FrameLayout chatViewport = new android.widget.FrameLayout(requireContext());
        chatViewport.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        jumpToLatest = new android.widget.FrameLayout(requireContext());
        jumpToLatest.setVisibility(View.GONE);
        jumpToLatest.setFocusable(true);
        jumpToLatest.setOnClickListener(view -> scroll.scrollTo(0, rows.getHeight()));
        android.widget.ImageView arrows = new android.widget.ImageView(requireContext());
        arrows.setImageResource(R.drawable.ic_chat_jump_latest);
        arrows.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        jumpToLatest.addView(arrows, new android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        newMessageCount = text("", 11, WHITE, true);
        newMessageCount.setGravity(Gravity.CENTER);
        newMessageCount.setPadding(dp(4), 0, dp(4), 0);
        newMessageCount.setMinWidth(dp(18));
        newMessageCount.setBackground(card(GREEN, GREEN, 12));
        newMessageCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        jumpToLatest.addView(newMessageCount, new android.widget.FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.END));
        android.widget.FrameLayout.LayoutParams jumpPosition =
                new android.widget.FrameLayout.LayoutParams(dp(56), dp(48), Gravity.BOTTOM | Gravity.RIGHT);
        jumpPosition.rightMargin = dp(2);
        chatViewport.addView(jumpToLatest, jumpPosition);
        screen.addView(chatViewport, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (peer != null && b - t < oldB - oldT
                    && rows.getHeight() - scroll.getScrollY() <= oldB - oldT + dp(12)) {
                scroll.post(() -> { if (screen != null && peer != null) scroll.scrollTo(0, rows.getHeight()); });
            }
        });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            scrollingUp = y < oldY;
            scroll.removeCallbacks(showJumpAfterScroll);
            if (scrollingUp) scroll.postDelayed(showJumpAfterScroll, 250);
            updateJumpToLatest();
            markVisibleRead();
        });

        bottom = new LinearLayout(requireContext());
        bottom.setPadding(dp(6), dp(3), dp(6), dp(3));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(card(WHITE, EDGE, 15));
        draft = new EditText(requireContext());
        draft.setHint("Write a message");
        draft.setTextSize(17);
        draft.setMinHeight(dp(54));
        draft.setMaxLines(6);
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
        com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView dictationWave =
                new com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView(requireContext());
        dictationWave.setVisibility(View.GONE);
        android.widget.FrameLayout inputBox = new android.widget.FrameLayout(requireContext());
        inputBox.addView(draft, new android.widget.FrameLayout.LayoutParams(-1, -2));
        inputBox.addView(dictationWave, new android.widget.FrameLayout.LayoutParams(-1, dp(42), Gravity.CENTER_VERTICAL));
        draftVoice.bind(draft, dictationWave);
        bottom.addView(inputBox, new LinearLayout.LayoutParams(0, -2, 1));
        send = new MaterialButton(requireContext());
        send.setMinWidth(0);
        send.setMinimumWidth(0);
        send.setPadding(0, 0, 0, 0);
        ChatSendButton.style(send, "Send message");
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        send.setIconTint(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xff548570, 0xff087b4b}));
        mic = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        mic.setIconResource(R.drawable.ic_mic);
        mic.setIconSize(dp(25));
        mic.setStrokeWidth(0);
        mic.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        mic.setGravity(Gravity.CENTER);
        mic.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        mic.setIconPadding(0);
        mic.setIconTint(android.content.res.ColorStateList.valueOf(ChatVoiceControls.PURPLE));
        mic.setPadding(0, 0, 0, 0);
        mic.setContentDescription("Record voice note. Hold and slide down to lock");
        mic.setOnClickListener(v -> {
            draftVoice.stop();
            voiceNotes.record(store.thread(peer));
        });
        mic.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) draftVoice.stop();
            return voiceNotes.touch(v, event, store.thread(peer));
        });
        bottom.addView(mic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendSize.setMarginStart(dp(4));
        bottom.addView(send, sendSize);
        LinearLayout voiceComposer = column();
        voiceComposer.setVisibility(View.GONE);
        voiceNotes.bind(bottom, voiceComposer, send);
        android.widget.FrameLayout composerArea = new android.widget.FrameLayout(requireContext());
        composerArea.addView(bottom, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        composerArea.addView(voiceComposer, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        replyBanner = new LinearLayout(requireContext());
        replyBanner.setGravity(Gravity.CENTER_VERTICAL);
        replyBanner.setPadding(dp(10), dp(2), dp(4), dp(2));
        replyBanner.setBackground(card(0xffe5f2fa, EDGE, 12));
        replyContent = column();
        replyBanner.addView(replyContent, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton cancelReply = flatButton("×");
        cancelReply.setContentDescription("Cancel reply");
        cancelReply.setOnClickListener(view -> clearReply());
        replyBanner.addView(cancelReply, new LinearLayout.LayoutParams(dp(48), dp(48)));
        screen.addView(replyBanner);
        screen.addView(composerArea);
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
        return pageRoot;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        backCallback = new OnBackPressedCallback(peer != null || choosingLiveRecipient) {
            @Override public void handleOnBackPressed() {
                if (secondaryBack != null) secondaryBack.run();
                else if (choosingLiveRecipient) cancelLiveRecipient();
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
        chatStatus.stopVideo();
        voiceNotes.stop();
        draftVoice.stop();
        super.onStop();
    }

    @Override public void onDestroyView() {
        scroll.removeCallbacks(showJumpAfterScroll);
        jumpToLatest = null;
        scrollingUp = false;
        groupScreens.dismiss();
        chatStatus.dismiss();
        clearSecondaryPage();
        pageRoot = null;
        draftVoice.bind(null, null);
        voiceNotes.bind(null, null, null);
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

    void toggleVoiceTyping(FamilyChatStore.ThreadState thread) {
        if (isVoiceThreadOpen(thread) && store.canUse(thread)) draftVoice.toggle();
    }

    boolean isVoiceThreadOpen(FamilyChatStore.ThreadState thread) {
        return store.thread(peer) == thread;
    }

    boolean isStartedForVoice() {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    public boolean isConversationOpen() {
        return peer != null || secondaryPage != null;
    }

    private MaterialButton flatButton(String title) {
        MaterialButton button = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        return button;
    }

    String replyTarget() { return replyKey; }

    void completeVoiceReply(FamilyChatStore.ThreadState thread, String target) {
        if (target != null && isVoiceThreadOpen(thread) && target.equals(replyKey)) clearReply();
    }

    void replyTo(FamilyChatStore.Message message) {
        replyKey = message.snapshot.getKey();
        replyPreview = replyTitle(message) + ": " + replySummary(message);
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

    void replyToStatus(String author) {
        FamilyChatStore.ThreadState thread = store.thread(author);
        if (thread == null || !store.canUse(thread)) return;
        open(author);
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
        replyContent.removeAllViews();
        if (replyKey == null || peer == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        if (thread != null) for (FamilyChatStore.Message message : thread.messages) {
            if (!replyKey.equals(message.snapshot.getKey())) continue;
            replyContent.addView(replyQuote(thread, message, true));
            return;
        }
        replyContent.addView(text("Replying to " + replyPreview, 13, BLUE, true));
    }

    boolean reveal(String key) {
        View bubble = rows.findViewWithTag(key);
        if (bubble == null) return false;
        scroll.smoothScrollTo(0, ((View) bubble.getParent()).getTop());
        bubble.announceForAccessibility("Original message");
        return true;
    }

    boolean wasUnread(FamilyChatStore.Message message) {
        return unreadOnOpen.contains(message.snapshot.getKey()) || store.unread(message);
    }

    private void open(@Nullable String destination) {
        clearSecondaryPage();
        voiceNotes.stop();
        draftVoice.stop();
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
        scrollingUp = false;
        scroll.removeCallbacks(showJumpAfterScroll);
        draft.setText("");
        render();
    }

    private void updateSend() {
        if (send == null || draft == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        boolean hasText = !draft.getText().toString().trim().isEmpty();
        send.setEnabled(thread != null && !thread.error && hasText);
        mic.setEnabled(thread != null && store.canUse(thread));
    }

    private void cancelLiveRecipient() {
        choosingLiveRecipient = false;
        render();
    }

    private void render() {
        if (screen == null) return;
        if (pendingNotificationConversation != null) {
            for (FamilyChatStore.ThreadState candidate : store.threads()) {
                if (pendingNotificationConversation.equals(candidate.ref.toString()) && store.canUse(candidate)) {
                    pendingNotificationConversation = null;
                    open(candidate.peer);
                    return;
                }
            }
        }
        boolean inThread = peer != null;
        if (secondaryAllowed != null && !secondaryAllowed.getAsBoolean()) clearSecondaryPage();
        if (backCallback != null) backCallback.setEnabled(secondaryPage != null || inThread || choosingLiveRecipient);
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
        statusButton.setVisibility(!inThread && !choosingLiveRecipient && store.statusFamily() != null
                ? View.VISIBLE : View.GONE);
        boolean chatReady = thread != null && !thread.error;
        toolsButton.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        pinBanner.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        pinBanner.setText("Pinned messages");
        if (chatReady) for (int i = thread.messages.size() - 1; i >= 0; i--) {
            FamilyChatStore.Message pinned = thread.messages.get(i);
            if (!pinned.pinned() || pinned.deleted() || hiddenForMe(pinned)) continue;
            pinBanner.setText("Pinned · " + pinned.body);
            break;
        }
        headerAvatar.removeAllViews();
        if (thread != null) headerAvatar.addView(threadAvatar(thread, 40));
        header.showDashboardActions(newGroup.getVisibility() != View.VISIBLE && thread == null);
        search.setVisibility(inThread ? View.GONE : View.VISIBLE);
        chatFilters.setVisibility(inThread || choosingLiveRecipient ? View.GONE : View.VISIBLE);
        for (ChatFilter filter : ChatFilter.values()) {
            TextView chip = (TextView) chatFilters.getChildAt(filter.ordinal());
            boolean selected = filter == chatFilter;
            chip.setSelected(selected);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setBackground(card(selected ? 0xffd1eee1 : WHITE, selected ? 0xff98cbb7 : EDGE, 22));
        }
        if (!chatReady) voiceNotes.stop();
        voiceNotes.setInputVisible(chatReady);
        header.setTitle(choosingLiveRecipient ? "Share to chat" : thread == null ? "Family Chat" : store.title(thread));
        boolean memberDetails = chatReady && !thread.isGroup() && store.groupCandidates().containsKey(thread.peer);
        View.OnClickListener showDetails = memberDetails ? v -> showMemberDetails(thread) : null;
        header.setOnClickListener(showDetails);
        header.setClickable(memberDetails);
        header.setFocusable(memberDetails);
        headerAvatar.setOnClickListener(showDetails);
        headerAvatar.setClickable(memberDetails);
        headerAvatar.setFocusable(memberDetails);
        headerAvatar.setContentDescription(memberDetails ? "View member details" : null);
        String currentStatus = store.status();
        header.setDetail(memberDetails ? "Tap to view details ›" : !currentStatus.isEmpty() ? currentStatus
                : thread != null && thread.isGroup() ? store.groupMembers(thread).size() + " members · Group info"
                : store.online() ? "Live · Family sync" : "Offline · messages send when reconnected");
        updateSend();
        updateReplyBanner();
        int oldY = scroll.getScrollY();
        boolean atBottom = !scroll.canScrollVertically(1);
        rows.removeAllViews();
        bubbles.clear();
        if (!inThread) {
            renderList();
            scroll.post(() -> { if (screen != null && peer == null) scroll.scrollTo(0, oldY); });
        } else if (thread != null && !thread.error) {
            renderThread(thread, oldY, atBottom);
        } else {
            addLine(text(thread != null ? "Chat is unavailable" : "Connecting to conversation…", 14, MUTED, false));
        }
        updateJumpToLatest();
    }

    private void renderList() {
        String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ChatFilter category = choosingLiveRecipient ? ChatFilter.ALL : chatFilter;
        List<FamilyChatStore.ThreadState> direct = new ArrayList<>();
        List<FamilyChatStore.ThreadState> groups = new ArrayList<>();
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (!store.title(thread).toLowerCase(Locale.ROOT).contains(filter)) continue;
            boolean group = FamilyChatStore.GROUP.equals(thread.peer) || thread.isGroup();
            if ((category == ChatFilter.GROUPS && !group) || (category == ChatFilter.DIRECT && group)
                    || (category == ChatFilter.UNREAD && (thread.error || unreadCount(thread) == 0))) continue;
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
        boolean showOwn = !choosingLiveRecipient && (category == ChatFilter.ALL || category == ChatFilter.DIRECT)
                && store.accountId() != null && store.status().isEmpty()
                && "you".contains(filter);
        if (!direct.isEmpty() || showOwn) addLine(text("Direct messages", 14, BLUE, true));
        for (FamilyChatStore.ThreadState thread : direct) addConversation(thread);
        if (showOwn) addOwnProfile();
        if (rows.getChildCount() == 0 && store.status().isEmpty()) {
            addLine(text(category == ChatFilter.UNREAD && filter.isEmpty()
                    ? "No unread chats" : "No matching chats", 14, MUTED, false));
        }
    }

    void showSecondaryPage(String title, ScrollView scroll, Runnable back,
                           java.util.function.BooleanSupplier allowed) {
        if (pageRoot == null || !allowed.getAsBoolean()) return;
        clearSecondaryPage();
        LinearLayout page = column();
        page.setBackgroundResource(R.drawable.bg_page_three_tone);
        page.setPadding(dp(12), dp(8), dp(12), dp(6));
        ModuleOverviewView pageHeader = (ModuleOverviewView) getLayoutInflater()
                .inflate(R.layout.view_family_chat_header, page, false);
        pageHeader.setTitle(title);
        pageHeader.setDetail("Family Chat");
        pageHeader.showDashboardActions(false);
        pageHeader.setNavigationAction(R.drawable.ic_arrow_back, R.string.back, v -> back.run());
        page.addView(pageHeader);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page.setFocusableInTouchMode(true);
        screen.setVisibility(View.GONE);
        secondaryPage = page;
        secondaryBack = back;
        secondaryAllowed = allowed;
        pageRoot.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
        if (backCallback != null) backCallback.setEnabled(true);
    }

    boolean isSecondaryPage(View child) {
        for (View view = child; view != null; ) {
            if (view == secondaryPage) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    void closeSecondaryPage() {
        clearSecondaryPage();
        render();
    }

    private void clearSecondaryPage() {
        if (secondaryPage == null) return;
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.ViewCompat.getWindowInsetsController(secondaryPage);
        if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime());
        secondaryBack = null;
        secondaryAllowed = null;
        View previous = secondaryPage;
        secondaryPage = null;
        pageRoot.removeView(previous);
        screen.setVisibility(View.VISIBLE);
    }

    String ownStatusName() {
        String name = com.tridev.familyhub.feature.profile.AccountProfileSync.get(requireContext()).displayName();
        return name == null || name.trim().isEmpty() ? store.name(store.accountId()) : name;
    }

    View statusAvatar(String uid, String name, int size, boolean own) {
        return groupScreens.avatar(name, own ? avatars().ownPhoto() : avatars().photo(uid), size, null);
    }

    private void showMemberDetails(FamilyChatStore.ThreadState thread) {
        String uid = thread.peer;
        java.util.function.BooleanSupplier allowed = () -> store.canUse(thread)
                && store.groupCandidates().containsKey(uid);
        if (!allowed.getAsBoolean()) return;
        voiceNotes.stop();
        draftVoice.stop();
        ScrollView profile = new ScrollView(requireContext());
        LinearLayout content = column();
        content.setPadding(0, dp(12), 0, dp(12));
        profile.addView(content);
        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(12), dp(20), dp(12), dp(18));
        hero.setBackground(card(0xffe2f4ee, EDGE, 18));
        hero.addView(threadAvatar(thread, 80));
        TextView name = text(store.name(uid), 23, GREEN, true);
        name.setGravity(Gravity.CENTER);
        hero.addView(name);
        content.addView(hero);
        LinearLayout details = column();
        content.addView(details);
        MaterialButton familyLive = flatButton("View Family Live details ›");
        familyLive.setOnClickListener(v -> {
            if (allowed.getAsBoolean()) startActivity(
                    com.tridev.familyhub.feature.familylive.FamilyMemberDetailActivity.createIntent(requireContext(), uid));
        });
        content.addView(familyLive);
        showSecondaryPage("Member details", profile, this::closeSecondaryPage, allowed);
        new com.tridev.familyhub.data.repository.FamilyMemberRepository(requireContext())
                .loadMembers("", members -> {
                    if (!isSecondaryPage(profile) || !allowed.getAsBoolean()) return;
                    for (com.tridev.familyhub.data.local.entity.FamilyMember member : members) {
                        if (!uid.equals(member.cloudUid)) continue;
                        addMemberField(details, "Relationship", member.relation);
                        addMemberField(details, "Phone", member.phone);
                        addMemberField(details, "Email", member.email);
                        addMemberField(details, "Date of birth", member.dateOfBirth);
                        addMemberField(details, "Gender", member.gender);
                        addMemberField(details, "Blood group", member.bloodGroup);
                        addMemberField(details, "Address", member.address);
                        addMemberField(details, "Emergency contact", member.emergencyContactName);
                        addMemberField(details, "Emergency phone", member.emergencyContactPhone);
                        return;
                    }
                });
    }

    private void addMemberField(LinearLayout details, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        LinearLayout field = column();
        field.setBackground(card(WHITE, EDGE, 14));
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.addView(text(label, 13, MUTED, false));
        TextView textValue = text(value, 16, INK, true);
        textValue.setTextIsSelectable(true);
        field.addView(textValue);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(8);
        details.addView(field, spacing);
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
            int unread = unreadCount(thread);
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

    void requestChatNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            chatNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
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

    private String replyTitle(FamilyChatStore.Message message) {
        return store.own(message) ? "You" : store.name(message.sender);
    }

    private String replySummary(FamilyChatStore.Message message) {
        if (message.deleted()) return "Message deleted";
        if (hiddenForMe(message)) return "Message deleted for you";
        if ("photo".equals(message.kind())) return "Photo";
        if ("voice".equals(message.kind())) {
            ChatVoiceAttachment attachment = message.voiceAttachment();
            Integer millis = message.snapshot.child("voiceDuration").getValue(Integer.class);
            int duration = attachment != null ? attachment.duration : millis == null ? 0 : millis;
            return duration <= 0 ? "Voice note" : "Voice note · " + ChatVoiceControls.elapsed(duration);
        }
        return message.body;
    }

    private View replyQuote(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, boolean draft) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card(0xffe3f1ee, EDGE, 11));
        row.setPadding(dp(8), dp(6), dp(6), dp(6));
        View stripe = new View(requireContext());
        stripe.setBackgroundColor(GREEN);
        row.addView(stripe, new LinearLayout.LayoutParams(dp(3), dp(36)));
        LinearLayout words = column();
        words.setPadding(dp(8), 0, dp(6), 0);
        words.addView(text((draft ? "Replying to " : "") + replyTitle(message), 12, GREEN, true));
        TextView summary = text(replySummary(message), 13, INK, false);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(summary);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if ("photo".equals(message.kind()) && !message.deleted() && !hiddenForMe(message)) {
            android.widget.ImageView thumbnail = new android.widget.ImageView(requireContext());
            thumbnail.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            thumbnail.setContentDescription("Original photo");
            row.addView(thumbnail, new LinearLayout.LayoutParams(dp(42), dp(42)));
            attachments.loadPhoto(thread, message, bitmap -> {
                if (bitmap != null) thumbnail.setImageBitmap(bitmap);
            });
        }
        return row;
    }

    private String hiddenKey(FamilyChatStore.Message message) {
        return store.accountId() + "|" + message.snapshot.getRef().toString();
    }

    private android.content.SharedPreferences hiddenMessages() {
        return requireContext().getSharedPreferences("family_chat_hidden_messages", android.content.Context.MODE_PRIVATE);
    }

    boolean hiddenForMe(FamilyChatStore.Message message) {
        return store.accountId() != null && hiddenMessages().getBoolean(hiddenKey(message), false);
    }

    void deleteForMe(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!isVoiceThreadOpen(thread) || !store.canUse(thread)) return;
        hiddenMessages().edit().putBoolean(hiddenKey(message), true).apply();
        voiceNotes.stopMessagePlayback(message);
        if (java.util.Objects.equals(replyKey, message.snapshot.getKey())) clearReply();
        render();
    }

    private int unreadCount(FamilyChatStore.ThreadState thread) {
        int count = 0;
        for (FamilyChatStore.Message message : thread.messages) {
            if (!message.deleted() && store.unread(message) && !hiddenForMe(message)) count++;
        }
        return count;
    }

    private void updateJumpToLatest() {
        if (jumpToLatest == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        boolean visible = thread != null && !thread.error && !scrollingUp && scroll.canScrollVertically(1);
        jumpToLatest.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        int unread = unreadCount(thread);
        newMessageCount.setVisibility(unread == 0 ? View.GONE : View.VISIBLE);
        newMessageCount.setText(Integer.toString(unread));
        jumpToLatest.setContentDescription(unread == 0 ? "Jump to latest message"
                : "Jump to latest message. New messages: " + unread);
    }

    private void renderThread(FamilyChatStore.ThreadState thread, int oldY, boolean atBottom) {
        String latestKey = thread.messages.isEmpty() ? null
                : thread.messages.get(thread.messages.size() - 1).snapshot.getKey();
        boolean goToBottom = lastMessageKey == null || atBottom;
        lastMessageKey = latestKey;
        if (thread.messages.isEmpty()) addLine(text("No messages yet", 14, MUTED, false));
        ChatAttachmentCards cards = new ChatAttachmentCards(requireContext(), attachments);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int compactWidth = Math.min(dp(420), Math.round(screenWidth * 0.82f));
        int compactMediaWidth = Math.max(1, compactWidth - dp(20));
        java.util.Map<String, FamilyChatStore.Message> known = new java.util.HashMap<>();
        for (FamilyChatStore.Message item : thread.messages) known.put(item.snapshot.getKey(), item);
        for (FamilyChatStore.Message message : thread.messages) {
            if (hiddenForMe(message)) {
                bubbles.add(null);
                continue;
            }
            boolean own = store.own(message);
            if (message.deleted()) voiceNotes.stopMessagePlayback(message);
            LinearLayout bubble = new ChatMessageBubble(requireContext(), (view, touch) ->
                    smart.messageActions(thread, message, view, touch));
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
            bubble.setBackground(card(own ? 0xffe5f2fa : WHITE, EDGE, 15));
            bubble.setTag(message.snapshot.getKey());
            if (!own && thread.isGroup()) {
                bubble.addView(text(store.name(message.sender), 13, GREEN, true));
            }
            String target = message.snapshot.child("replyTo").getValue(String.class);
            if (target != null && !message.deleted()) {
                FamilyChatStore.Message original = known.get(target);
                View quoted = original == null ? text("↪ View original message", 13, BLUE, true)
                        : replyQuote(thread, original, false);
                quoted.setOnClickListener(view -> smart.openReply(thread, target));
                bubble.addView(quoted);
            }
            View attachment = cards.create(thread, message, compactMediaWidth);
            if (attachment != null) {
                bubble.addView(attachment, new LinearLayout.LayoutParams(-1, -2));
            } else {
                TextView messageText = text(message.body, 17, INK, false);
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
            menu.setOnClickListener(view -> smart.messageActions(thread, message, bubble));
            if (!message.deleted()) metadata.addView(menu);
            if (own) {
                boolean seen = store.acknowledged(thread, message, true);
                boolean delivered = seen || store.acknowledged(thread, message, false);
                boolean sending = store.sending(message) && !delivered;
                View tick = sending ? text("◷", 13, MUTED, true) : receiptTick(delivered, seen);
                tick.setContentDescription(seen ? "Seen" : delivered ? "Delivered" : sending ? "Sending" : "Sent");
                metadata.addView(tick);
            }
            bubble.addView(metadata);
            if (attachment == null) bubble.measure(View.MeasureSpec.makeMeasureSpec(compactWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int bubbleWidth = attachment == null ? Math.min(compactWidth, Math.max(dp(140), bubble.getMeasuredWidth())) : compactWidth;
            LinearLayout messageRow = new LinearLayout(requireContext());
            messageRow.setGravity((own ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(7);
            if (!own) params.setMarginEnd(dp(24));
            if (own && !message.deleted()) {
                MaterialButton reply = new MaterialButton(requireContext(), null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                reply.setIconResource(R.drawable.ic_chat_reply);
                reply.setIconSize(dp(22));
                reply.setIconPadding(0);
                reply.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                reply.setIconTint(android.content.res.ColorStateList.valueOf(BLUE));
                reply.setPadding(0, 0, 0, 0);
                reply.setContentDescription("Reply to this message");
                reply.setOnClickListener(v -> replyTo(message));
                int maxBubble = Math.max(dp(100), rows.getWidth() > 0
                        ? rows.getWidth() - dp(48) : screenWidth - dp(68));
                bubbleWidth = Math.min(bubbleWidth, maxBubble);
                messageRow.addView(reply, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            messageRow.addView(bubble, new LinearLayout.LayoutParams(bubbleWidth, -2));
            rows.addView(messageRow, params);
            bubbles.add(bubble);
        }
        scroll.post(() -> {
            if (screen == null || !thread.peer.equals(peer)) return;
            if (goToBottom) scroll.scrollTo(0, rows.getHeight());
            else scroll.scrollTo(0, oldY);
            updateJumpToLatest();
            markVisibleRead();
        });
    }

    private void markVisibleRead() {
        if (screen == null || secondaryPage != null || !isResumed() || peer == null || !screen.hasWindowFocus()) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        if (thread == null || bubbles.size() != thread.messages.size()) return;
        android.graphics.Rect viewport = new android.graphics.Rect();
        if (!scroll.getGlobalVisibleRect(viewport)) return;
        for (int i = 0; i < bubbles.size(); i++) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            if (bubbles.get(i) != null && bubbles.get(i).getGlobalVisibleRect(bounds) && android.graphics.Rect.intersects(viewport, bounds)) {
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

    private View receiptTick(boolean doubleTick, boolean seen) {
        View tick = new View(requireContext()) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();

            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                canvas.save();
                canvas.translate(dp(3), (getHeight() - dp(12)) / 2f);
                float unit = dp(12) / 12f;
                canvas.scale(unit, unit);
                paint.setColor(seen ? 0xff005b39 : MUTED);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(1.65f);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                path.reset();
                path.moveTo(1, 6);
                path.lineTo(4, 9);
                path.lineTo(11, 2);
                if (doubleTick) {
                    path.moveTo(6.5f, 7.5f);
                    path.lineTo(8, 9);
                    path.lineTo(15, 2);
                }
                canvas.drawPath(path, paint);
                canvas.restore();
            }
        };
        tick.setLayoutParams(new LinearLayout.LayoutParams(dp(doubleTick ? 20 : 16), dp(16)));
        return tick;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
