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
    private String pendingInviteCode;
    private boolean connectionsTab;
    private LinearLayout conversationTabs, connectionActions;
    private final ChatPhoneDiscovery phoneDiscovery = new ChatPhoneDiscovery(this);
    private MaterialButton connectionRequests, headerSearch;
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
    private List<FamilyChatStore.Message> displayedMessages = java.util.Collections.emptyList();
    private FamilyChatStore.Message historicalPin;
    private LinearLayout screen, rows, bottom;
    private android.widget.FrameLayout pageRoot;
    private View secondaryPage;
    private static final String SELF_CONVERSATION = "familyhub:self";
    private Runnable secondaryBack;
    private java.util.function.BooleanSupplier secondaryAllowed;
    private ModuleOverviewView header;
    private LinearLayout selectionToolbar;
    private final java.util.Set<String> selectedMessageKeys = new java.util.HashSet<>();
    private ScrollView scroll;
    private final ChatDictation draftVoice = new ChatDictation(this);
    private LinearLayout searchBar, searchNavigation;
    private View searchBack, searchClear;
    private TextView searchCount;
    private final ChatKeywordSearch keywordSearch = new ChatKeywordSearch(store);
    private boolean searching, searchLoaded;
    private String activeMatch;
    private String highlightedPinKey;
    private final Runnable clearPinnedHighlight = () -> {
        highlightedPinKey = null;
        updateMessageSelection();
    };
    private int searchResultsPerChat = 40;
    private List<FamilyChatStore.Message> threadMatches = java.util.Collections.emptyList();
    private final Runnable searchHistory = this::loadSearchHistory;
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

    public static FamilyChatFragment forInvite(String code) {
        FamilyChatFragment fragment = new FamilyChatFragment();
        Bundle arguments = new Bundle();
        arguments.putString("chat_invite", code);
        fragment.setArguments(arguments);
        return fragment;
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
        pendingInviteCode = state == null ? (args == null ? null : args.getString("chat_invite"))
                : state.getString("chat_invite");
        connectionsTab = pendingInviteCode != null || (state != null && state.getBoolean("connections_tab"));
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
        out.putString("chat_invite", pendingInviteCode);
        out.putBoolean("connections_tab", connectionsTab);
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
        if (getActivity() instanceof FloatingChatActivity) {
            screen.setPadding(dp(5), dp(4), dp(5), dp(4));
            screen.addOnLayoutChangeListener((view, l, t, r, bottom, oldL, oldT, oldR, oldBottom) -> {
                if (r - l != oldR - oldL) view.post(() -> {
                    if (screen == view) render();
                });
            });
        }
        header = (ModuleOverviewView) inflater.inflate(R.layout.view_family_chat_header, screen, false);
        newGroup = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        newGroup.setTextSize(FloatingChatSizing.text(requireContext(), 12));
        newGroup.setMaxLines(2);
        newGroup.setMinWidth(0);
        styleHeaderIcon(newGroup, R.drawable.ic_chat_new_group, "New group");
        newGroup.setAllCaps(false);
        newGroup.setOnClickListener(v -> {
            if (choosingLiveRecipient) {
                cancelLiveRecipient();
            } else {
                groupScreens.showCreate();
            }
        });
        header.addHeaderAction(newGroup);
        statusButton = flatButton("");
        styleHeaderIcon(statusButton, R.drawable.ic_chat_status_ring, "Status");
        statusButton.setOnClickListener(v -> chatStatus.open());
        header.addHeaderAction(statusButton);
        headerSearch = flatButton("");
        styleHeaderIcon(headerSearch, R.drawable.ic_search, "Search messages");
        headerSearch.setOnClickListener(view -> openMessageSearch());
        header.addHeaderAction(headerSearch);
        headerAvatar = new LinearLayout(requireContext());
        header.addHeaderAction(headerAvatar);
        toolsButton = flatButton("⋮");
        toolsButton.setContentDescription("Chat tools");
        toolsButton.setMinWidth(0);
        toolsButton.setMinimumWidth(0);
        toolsButton.setPadding(0, 0, 0, 0);
        toolsButton.setLayoutParams(new LinearLayout.LayoutParams(dp(42), target(48)));
        toolsButton.setOnClickListener(view -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) smart.threadActions(thread, view);
        });
        header.addHeaderAction(toolsButton);
        compactHeader(header);
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(-1, -2);
        if (FloatingChatSizing.applies(requireContext())) {
            headerSize.setMargins(-dp(5), -dp(4), -dp(5), 0);
            com.google.android.material.card.MaterialCardView surface = header.findViewById(R.id.module_overview_surface);
            surface.setRadius(0);
            surface.setStrokeWidth(0);
        }
        screen.addView(header, headerSize);
        selectionToolbar = new LinearLayout(requireContext());
        selectionToolbar.setGravity(Gravity.CENTER_VERTICAL);
        selectionToolbar.setPadding(dp(5), dp(3), dp(5), dp(3));
        selectionToolbar.setBackground(card(0xffe4f3ec, 0xff16816a, 16));
        selectionToolbar.setVisibility(View.GONE);
        screen.addView(selectionToolbar);
        selectionToolbar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!selectedMessageKeys.isEmpty() && right - left != oldRight - oldLeft) updateMessageSelection();
        });

        search = new EditText(requireContext());
        search.setSingleLine();
        search.setTextSize(FloatingChatSizing.text(requireContext(), 15));
        search.setHint("Search messages");
        search.setPadding(space(12), 0, space(12), 0);
        search.setBackground(null);
        searchBar = new LinearLayout(requireContext());
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(card(WHITE, EDGE, 14));
        searchBack = searchButton("‹", "Close search", this::closeMessageSearch);
        searchBar.addView(searchBack, new LinearLayout.LayoutParams(target(48), target(48)));
        searchBar.addView(search, new LinearLayout.LayoutParams(0, target(48), 1));
        searchClear = searchButton("×", "Clear search", () -> search.setText(""));
        searchBar.addView(searchClear, new LinearLayout.LayoutParams(target(48), target(48)));
        screen.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));
        searchNavigation = new LinearLayout(requireContext());
        searchNavigation.setGravity(Gravity.CENTER_VERTICAL);
        searchCount = text("", 13, GREEN, true);
        searchCount.setPadding(space(8), 0, 0, 0);
        searchNavigation.addView(searchCount, new LinearLayout.LayoutParams(0, -2, 1));
        searchNavigation.addView(searchButton("↑", "Previous match", () -> stepSearch(-1)),
                new LinearLayout.LayoutParams(target(48), target(48)));
        searchNavigation.addView(searchButton("↓", "Next match", () -> stepSearch(1)),
                new LinearLayout.LayoutParams(target(48), target(48)));
        screen.addView(searchNavigation);
        conversationTabs = new LinearLayout(requireContext());
        conversationTabs.setBackground(card(WHITE, EDGE, 22));
        conversationTabs.addView(conversationTab("Family Chat", false), new LinearLayout.LayoutParams(0, target(44), 1));
        conversationTabs.addView(conversationTab("Connections", true), new LinearLayout.LayoutParams(0, target(44), 1));
        LinearLayout.LayoutParams tabSize = new LinearLayout.LayoutParams(-1, -2);
        tabSize.topMargin = space(6);
        screen.addView(conversationTabs, tabSize);
        search.setOnFocusChangeListener((view, focused) -> {
            if (focused && !searching && peer == null && !choosingLiveRecipient) openMessageSearch();
        });
        search.addTextChangedListener(watcher(() -> {
            if (!searching) {
                if (choosingLiveRecipient) render();
                return;
            }
            activeMatch = null;
            searchResultsPerChat = 40;
            screen.removeCallbacks(searchHistory);
            if (!searchLoaded && !search.getText().toString().trim().isEmpty()) {
                screen.postDelayed(searchHistory, 220);
            }
            render();
            revealActiveMatch();
        }));
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
        connectionActions = column();
        MaterialButton findByNumber = flatButton("Find by mobile number");
        findByNumber.setOnClickListener(view -> phoneDiscovery.showSearch());
        connectionActions.addView(findByNumber, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout inviteActions = new LinearLayout(requireContext());
        connectionActions.addView(inviteActions, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton invite = flatButton("+ Invite");
        invite.setOnClickListener(view -> showConnectionInvite(null));
        inviteActions.addView(invite, new LinearLayout.LayoutParams(0, -2, 1));
        connectionRequests = flatButton("Requests");
        connectionRequests.setOnClickListener(view -> showConnectionRequests());
        inviteActions.addView(connectionRequests, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(connectionActions, new LinearLayout.LayoutParams(-1, -2));
        pinBanner = flatButton("Pinned messages");
        pinBanner.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pinBanner.setMaxLines(1);
        pinBanner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pinBanner.setOnClickListener(view -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread == null || !store.canUse(thread)) return;
            FamilyChatStore.Message latest = latestVisiblePin(thread);
            if (latest != null) jumpToPin(thread, latest);
            else smart.openLatestPin(thread);
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
        draft.setTextSize(FloatingChatSizing.text(requireContext(), 17));
        draft.setMinHeight(target(54));
        draft.setMaxLines(6);
        draft.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draft.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        draft.setBackground(null);
        draft.setPadding(space(6), space(8), space(6), space(8));
        MaterialButton attach = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        attach.setIconResource(R.drawable.ic_chat_attachment);
        attach.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        attach.setIconPadding(0);
        attach.setIconSize(space(22));
        attach.setContentDescription("Add attachment");
        attach.setPadding(0, 0, 0, 0);
        attach.setOnClickListener(v -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) attachments.show(thread);
        });
        bottom.addView(attach, new LinearLayout.LayoutParams(target(48), target(48)));
        MaterialButton emoji = flatButton("");
        emoji.setIconResource(R.drawable.ic_chat_emoji);
        emoji.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        emoji.setIconSize(space(23));
        emoji.setIconPadding(0);
        emoji.setPadding(0, 0, 0, 0);
        emoji.setStrokeWidth(0);
        emoji.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        emoji.setIconTint(android.content.res.ColorStateList.valueOf(GREEN));
        emoji.setContentDescription("Choose emoji");
        emoji.setOnClickListener(view -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread == null || !store.canUse(thread)) return;
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.ViewCompat.getWindowInsetsController(draft);
            if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime());
            ChatEmojiPicker.show(requireContext(), value -> {
                if (screen == null || !isVoiceThreadOpen(thread)) return;
                int start = Math.max(0, Math.min(draft.getSelectionStart(), draft.getSelectionEnd()));
                int end = Math.max(start, Math.max(draft.getSelectionStart(), draft.getSelectionEnd()));
                if (draft.length() - (end - start) + value.length() <= 2000) {
                    draft.getText().replace(start, end, value);
                }
            });
        });
        bottom.addView(emoji, new LinearLayout.LayoutParams(target(48), target(48)));
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
        mic.setIconSize(space(25));
        mic.setStrokeWidth(0);
        mic.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        mic.setGravity(Gravity.CENTER);
        mic.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        mic.setIconPadding(0);
        mic.setIconTint(android.content.res.ColorStateList.valueOf(ChatVoiceControls.PURPLE));
        mic.setPadding(0, 0, 0, 0);
        if (getActivity() instanceof FloatingChatActivity) bindFloatingMicrophone();
        else {
            mic.setContentDescription("Record voice note. Hold and slide down to lock");
            mic.setOnClickListener(v -> {
                draftVoice.stop();
                voiceNotes.record(store.thread(peer));
            });
            mic.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) draftVoice.stop();
                return voiceNotes.touch(v, event, store.thread(peer));
            });
        }
        bottom.addView(mic, new LinearLayout.LayoutParams(target(48), target(48)));
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(target(48), target(48));
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
        MaterialButton cancelReply = flatButton("");
        cancelReply.setIconResource(R.drawable.ic_family_map_close);
        cancelReply.setIconSize(space(22));
        cancelReply.setIconPadding(0);
        cancelReply.setPadding(0, 0, 0, 0);
        cancelReply.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        cancelReply.setIconTint(android.content.res.ColorStateList.valueOf(GREEN));
        cancelReply.setContentDescription("Cancel reply");
        cancelReply.setOnClickListener(view -> clearReply());
        replyBanner.addView(cancelReply, new LinearLayout.LayoutParams(target(48), target(48)));
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
                else if (searching) closeMessageSearch();
                else if (!selectedMessageKeys.isEmpty()) clearMessageSelection();
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
        scroll.removeCallbacks(clearPinnedHighlight);
        highlightedPinKey = null;
        jumpToLatest = null;
        scrollingUp = false;
        groupScreens.dismiss();
        chatStatus.dismiss();
        clearSecondaryPage();
        pageRoot = null;
        draftVoice.bind(null, null);
        voiceNotes.bind(null, null, null);
        screen.removeCallbacks(searchHistory);
        screen = null;
        historicalPin = null;
        displayedMessages = java.util.Collections.emptyList();
        keywordSearch.clear();
        searchLoaded = false;
        activeMatch = null;
        searching = false;
        bubbles.clear();
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        attachments.close();
        voiceNotes.close();
        groupScreens.close();
        super.onDestroy();
    }

    private void bindFloatingMicrophone() {
        mic.setContentDescription("Tap to dictate. Hold to record a voice note");
        mic.setOnClickListener(v -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            if (thread != null && store.canUse(thread)) draftVoice.toggle();
        });
        androidx.core.view.ViewCompat.addAccessibilityAction(mic, "Record a voice note", (view, args) -> {
            draftVoice.stop();
            voiceNotes.record(store.thread(peer));
            return true;
        });
        android.view.GestureDetector press = new android.view.GestureDetector(requireContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent event) { return true; }
                    @Override public boolean onSingleTapUp(android.view.MotionEvent event) {
                        return mic.performClick();
                    }
                    @Override public void onLongPress(android.view.MotionEvent event) {
                        if (!isStartedForVoice()) return;
                        draftVoice.stop();
                        voiceNotes.beginHeldRecording(store.thread(peer), event.getRawY(), event.getPointerId(0));
                    }
                });
        mic.setOnTouchListener((view, event) -> {
            press.onTouchEvent(event);
            if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
                voiceNotes.touch(view, event, store.thread(peer));
            }
            return true;
        });
    }

    void stopFloatingAudio() {
        draftVoice.stop();
        voiceNotes.stop();
    }

    String openConversationReference() {
        if (store.isSelf(store.thread(peer))) return SELF_CONVERSATION;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        return thread == null ? null : thread.ref.toString();
    }

    void toggleVoiceTyping(FamilyChatStore.ThreadState thread) {
        if (isVoiceThreadOpen(thread) && store.canUse(thread)) draftVoice.toggle();
    }

    boolean isVoiceThreadOpen(FamilyChatStore.ThreadState thread) {
        return store.thread(peer) == thread;
    }

    boolean isStartedForVoice() {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                && (!(getActivity() instanceof FloatingChatActivity)
                    || ((FloatingChatActivity) getActivity()).panelVisible());
    }

    public boolean isConversationOpen() {
        return peer != null || secondaryPage != null;
    }

    MaterialButton flatButton(String title) {
        MaterialButton button = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        FloatingChatSizing.compactButton(button);
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

    private FamilyChatStore.Message latestVisiblePin(FamilyChatStore.ThreadState thread) {
        for (int i = thread.messages.size() - 1; i >= 0; i--) {
            FamilyChatStore.Message message = thread.messages.get(i);
            if (message.pinned() && !message.deleted() && !hiddenForMe(message)) return message;
        }
        return null;
    }

    void jumpToPin(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        jumpToMessage(thread, message, true);
    }

    private void jumpToMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, boolean highlightPin) {
        if (!isVoiceThreadOpen(thread) || !store.canUse(thread)) return;
        clearSecondaryPage();
        String key = message.snapshot.getKey();
        if (thread.messages.stream().noneMatch(item -> java.util.Objects.equals(key, item.snapshot.getKey()))) {
            historicalPin = message;
            render();
        }
        scroll.post(() -> {
            if (screen == null || !isVoiceThreadOpen(thread) || !reveal(key) || !highlightPin) return;
            highlightedPinKey = key;
            updateMessageSelection();
            scroll.removeCallbacks(clearPinnedHighlight);
            scroll.postDelayed(clearPinnedHighlight, 3000);
        });
    }

    boolean wasUnread(FamilyChatStore.Message message) {
        return unreadOnOpen.contains(message.snapshot.getKey()) || store.unread(message);
    }

    private void open(@Nullable String destination) {
        if (destination != null) connectionsTab = store.isConnection(store.thread(destination));
        resetMessageSearch();
        scroll.removeCallbacks(clearPinnedHighlight);
        highlightedPinKey = null;
        historicalPin = null;
        selectedMessageKeys.clear();
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
        if (pendingInviteCode != null && verifiedConnectionAccount()) {
            String code = pendingInviteCode;
            pendingInviteCode = null;
            if (ConnectionInvites.validCode(code)) showConnectionInvite(code);
        }
        if (SELF_CONVERSATION.equals(pendingNotificationConversation) && store.thread(store.accountId()) != null) {
            pendingNotificationConversation = null;
            open(store.accountId());
            return;
        }
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
        if (backCallback != null) backCallback.setEnabled(secondaryPage != null || inThread || choosingLiveRecipient || searching);
        FamilyChatStore.ThreadState thread = store.thread(peer);
        header.findViewById(R.id.module_overview_icon_container).setVisibility(
                FloatingChatSizing.applies(requireContext()) && !inThread && !choosingLiveRecipient ? View.GONE : View.VISIBLE);
        header.findViewById(R.id.module_overview_search).setVisibility(View.GONE);
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
        int groupIcon = choosingLiveRecipient ? R.drawable.ic_family_map_close : R.drawable.ic_chat_new_group;
        if (!java.util.Objects.equals(newGroup.getTag(), groupIcon)) {
            newGroup.setTag(groupIcon);
            newGroup.setIconResource(groupIcon);
            newGroup.setContentDescription(choosingLiveRecipient ? "Cancel" : "New group");
        }
        headerSearch.setVisibility(!inThread && !choosingLiveRecipient ? View.VISIBLE : View.GONE);
        newGroup.setVisibility(!inThread && (choosingLiveRecipient || !store.groupCandidates().isEmpty())
                ? View.VISIBLE : View.GONE);
        newGroup.setEnabled(choosingLiveRecipient || store.online());
        statusButton.setVisibility(!inThread && !choosingLiveRecipient && store.statusFamily() != null
                ? View.VISIBLE : View.GONE);
        boolean chatReady = thread != null && !thread.error;
        toolsButton.setVisibility(chatReady ? View.VISIBLE : View.GONE);
        pinBanner.setVisibility(chatReady && !searching ? View.VISIBLE : View.GONE);
        FamilyChatStore.Message latestPin = chatReady ? latestVisiblePin(thread) : null;
        pinBanner.setText(latestPin == null ? "Pinned messages" : "Pinned · " + latestPin.body);
        headerAvatar.removeAllViews();
        if (thread != null) {
            int size = FloatingChatSizing.applies(requireContext()) ? 32 : 40;
            headerAvatar.addView(store.isSelf(thread) ? statusAvatar(thread.peer, ownStatusName(), size, true)
                    : threadAvatar(thread, size));
        }
        header.showDashboardActions(false);
        searchBar.setVisibility(searching || choosingLiveRecipient ? View.VISIBLE : View.GONE);
        boolean showTabs = !inThread && !choosingLiveRecipient;
        conversationTabs.setVisibility(showTabs ? View.VISIBLE : View.GONE);
        for (int i = 0; i < conversationTabs.getChildCount(); i++) {
            View tab = conversationTabs.getChildAt(i);
            boolean selected = connectionsTab == (i == 1);
            tab.setSelected(selected);
            tab.setBackground(card(selected ? 0xffd1eee1 : WHITE, selected ? 0xff98cbb7 : WHITE, 22));
        }
        connectionActions.setVisibility(showTabs && connectionsTab ? View.VISIBLE : View.GONE);
        int requests = 0;
        for (ConnectionInvites.Invite invite : store.connections.entries()) {
            if (invite.pendingFor(store.accountId())) requests++;
        }
        connectionRequests.setText(requests == 0 ? "Requests" : "Requests " + requests);
        search.setHint(choosingLiveRecipient ? "Search recipients" : "Search messages");
        searchBack.setVisibility(searching ? View.VISIBLE : View.GONE);
        searchClear.setVisibility(searching ? View.VISIBLE : View.GONE);
        searchNavigation.setVisibility(searching && inThread ? View.VISIBLE : View.GONE);
        chatFilters.setVisibility(inThread || choosingLiveRecipient || searching || connectionsTab ? View.GONE : View.VISIBLE);
        for (ChatFilter filter : ChatFilter.values()) {
            TextView chip = (TextView) chatFilters.getChildAt(filter.ordinal());
            boolean selected = filter == chatFilter;
            chip.setSelected(selected);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setBackground(card(selected ? 0xffd1eee1 : WHITE, selected ? 0xff98cbb7 : EDGE, 22));
        }
        if (!chatReady) voiceNotes.stop();
        voiceNotes.setInputVisible(chatReady);
        header.setTitle(choosingLiveRecipient ? "Share to chat" : thread == null ? "Family Chat"
                : store.isSelf(thread) ? ownStatusName() + " (You)" : store.title(thread));
        boolean memberDetails = chatReady && !store.isSelf(thread) && !thread.isGroup() && store.groupCandidates().containsKey(thread.peer);
        View.OnClickListener showDetails = memberDetails ? v -> showMemberDetails(thread) : null;
        header.setOnClickListener(showDetails);
        header.setClickable(memberDetails);
        header.setFocusable(memberDetails);
        headerAvatar.setOnClickListener(showDetails);
        headerAvatar.setClickable(memberDetails);
        headerAvatar.setFocusable(memberDetails);
        headerAvatar.setContentDescription(memberDetails ? "View member details" : null);
        String currentStatus = store.status();
        header.setDetail(store.isSelf(thread) ? "Message yourself" : store.isConnection(thread) ? "Connection" : memberDetails ? "Tap to view details ›" : !currentStatus.isEmpty() ? currentStatus
                : thread != null && thread.isGroup() ? store.groupMembers(thread).size() + " members · Group info"
                : store.online() ? "Live · Family sync" : "Offline · messages send when reconnected");
        updateSend();
        updateReplyBanner();
        int oldY = scroll.getScrollY();
        boolean atBottom = !scroll.canScrollVertically(1);
        rows.removeAllViews();
        bubbles.clear();
        displayedMessages = java.util.Collections.emptyList();
        if (!inThread) {
            if (searching && searchPattern() != null) renderSearchResults();
            else if (connectionsTab && !choosingLiveRecipient) renderConnectionList();
            else renderList();
            scroll.post(() -> { if (screen != null && peer == null) scroll.scrollTo(0, oldY); });
        } else if (thread != null && !thread.error) {
            renderThread(thread, oldY, atBottom);
        } else {
            if (peer.equals(store.accountId())) addLegacySelfMessages(peer);
            addLine(text(thread != null ? "Chat is unavailable" : "Connecting to conversation…", 14, MUTED, false));
        }
        updateMessageSelection();
        updateJumpToLatest();
    }

    private TextView searchButton(String symbol, String description, Runnable action) {
        TextView button = text(symbol, 22, GREEN, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    void openMessageSearch() {
        if (screen == null || searching) return;
        clearMessageSelection();
        searching = true;
        render();
        search.requestFocus();
        search.post(() -> {
            if (screen == null || !searching) return;
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.ViewCompat.getWindowInsetsController(search);
            if (controller != null) controller.show(androidx.core.view.WindowInsetsCompat.Type.ime());
        });
    }

    private void resetMessageSearch() {
        if (!searching) return;
        searching = false;
        searchLoaded = false;
        activeMatch = null;
        threadMatches = java.util.Collections.emptyList();
        keywordSearch.clear();
        screen.removeCallbacks(searchHistory);
        screen.requestFocus();
        search.setText("");
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.ViewCompat.getWindowInsetsController(search);
        if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime());
    }

    private void closeMessageSearch() {
        resetMessageSearch();
        render();
    }

    private java.util.regex.Pattern searchPattern() {
        String query = search.getText().toString().trim();
        return query.isEmpty() ? null : ChatKeywordSearch.keyword(query);
    }

    private void loadSearchHistory() {
        if (screen == null || !searching || searchLoaded || searchPattern() == null) return;
        searchLoaded = true;
        List<FamilyChatStore.ThreadState> scope = new ArrayList<>();
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (inSearchScope(thread) && store.canUse(thread)) scope.add(thread);
        }
        keywordSearch.load(scope, () -> {
            if (screen == null || !searching) return;
            boolean reveal = activeMatch == null;
            render();
            if (reveal) revealActiveMatch();
        });
    }

    private int searchMatchIndex() {
        for (int i = 0; i < threadMatches.size(); i++) {
            if (java.util.Objects.equals(activeMatch, threadMatches.get(i).snapshot.getKey())) return i;
        }
        return -1;
    }

    private void stepSearch(int offset) {
        if (threadMatches.isEmpty()) return;
        int index = Math.floorMod(searchMatchIndex() + offset, threadMatches.size());
        activeMatch = threadMatches.get(index).snapshot.getKey();
        render();
        revealActiveMatch();
    }

    private void revealActiveMatch() {
        String key = activeMatch;
        if (key != null) scroll.post(() -> {
            if (screen != null && searching && key.equals(activeMatch)) reveal(key);
        });
    }

    private String searchStatus() {
        if (searchPattern() == null) return "Type a keyword";
        if (!searchLoaded || keywordSearch.loading) return "Searching messages…";
        return keywordSearch.incomplete ? "Some chat history could not be searched" : "No matching messages";
    }

    private void includeHistoricalMessage(FamilyChatStore.Message extra) {
        if (extra == null || displayedMessages.stream().anyMatch(message ->
                java.util.Objects.equals(message.snapshot.getKey(), extra.snapshot.getKey()))) return;
        displayedMessages.add(extra);
        displayedMessages.sort(java.util.Comparator.comparingLong((FamilyChatStore.Message message) -> message.at)
                .thenComparing(message -> message.snapshot.getKey()));
    }

    private void renderSearchResults() {
        java.util.regex.Pattern keyword = searchPattern();
        LinearLayout results = column();
        int count = 0;
        boolean more = false;
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (!inSearchScope(thread) || !store.canUse(thread)) continue;
            List<FamilyChatStore.Message> matches = keywordSearch.matches(thread, keyword, this::hiddenForMe);
            if (matches.isEmpty()) continue;
            count += matches.size();
            TextView heading = text(store.isSelf(thread) ? ownStatusName() + " (You)" : store.title(thread),
                    16, GREEN, true);
            heading.setPadding(space(8), space(10), 0, space(3));
            results.addView(heading);
            more |= matches.size() > searchResultsPerChat;
            for (FamilyChatStore.Message message : matches.subList(0, Math.min(matches.size(), searchResultsPerChat))) {
                LinearLayout row = column();
                row.setPadding(space(12), space(8), space(12), space(8));
                row.setBackground(card(WHITE, EDGE, 14));
                String sender = store.own(message) ? "You" : store.isConnection(thread) ? store.title(thread) : store.name(message.sender);
                row.addView(text(sender + " · " + dateTime(message.at), 12, MUTED, true));
                TextView snippet = text("", 15, INK, false);
                java.util.regex.Matcher match = keyword.matcher(message.body);
                if (match.find()) {
                    int start = Math.max(0, match.start() - 45);
                    int end = Math.min(message.body.length(), Math.max(match.end(), start + 145));
                    String excerpt = (start > 0 ? "…" : "") + message.body.substring(start, end)
                            + (end < message.body.length() ? "…" : "");
                    snippet.setText(ChatKeywordSearch.highlight(excerpt, keyword));
                }
                row.addView(snippet);
                row.setFocusable(true);
                row.setOnClickListener(view -> openSearchResult(thread, message.snapshot.getKey()));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
                size.topMargin = space(5);
                results.addView(row, size);
            }
        }
        addLine(text(count == 0 ? searchStatus() : "Messages · " + count + (count == 1 ? " match" : " matches")
                + (keywordSearch.loading || !searchLoaded ? " · Searching…" : keywordSearch.incomplete ? " · Partial" : ""),
                14, GREEN, true));
        addLine(results);
        if (more) {
            MaterialButton showMore = flatButton("Show more matches");
            showMore.setOnClickListener(view -> {
                searchResultsPerChat += 40;
                render();
            });
            addLine(showMore);
        }
    }

    private void openSearchResult(FamilyChatStore.ThreadState thread, String key) {
        String query = search.getText().toString();
        store.loadMessage(thread, key, message -> {
            if (screen == null || !searching || peer != null || !store.canUse(thread)
                    || !query.contentEquals(search.getText())) return;
            if (message == null || message.deleted() || hiddenForMe(message)) {
                Toast.makeText(requireContext(), "Message unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            open(thread.peer);
            jumpToMessage(thread, message, false);
        });
    }

    private void styleHeaderIcon(MaterialButton button, int icon, String label) {
        button.setText("");
        button.setIconResource(icon);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        button.setIconSize(space(21));
        button.setIconTint(android.content.res.ColorStateList.valueOf(GREEN));
        button.setContentDescription(label);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(36), target(44)));
    }

    private TextView conversationTab(String label, boolean connections) {
        TextView tab = text(label, 14, GREEN, true);
        tab.setGravity(Gravity.CENTER);
        tab.setFocusable(true);
        tab.setOnClickListener(view -> {
            if (connectionsTab == connections) return;
            connectionsTab = connections;
            if (searching) {
                screen.removeCallbacks(searchHistory);
                keywordSearch.clear();
                searchLoaded = false;
                searchResultsPerChat = 40;
                loadSearchHistory();
            }
            scroll.scrollTo(0, 0);
            render();
        });
        return tab;
    }

    private boolean inSearchScope(FamilyChatStore.ThreadState thread) {
        return peer == null ? store.isConnection(thread) == connectionsTab : thread.peer.equals(peer);
    }

    boolean verifiedConnectionAccount() {
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return user != null && user.isEmailVerified() && user.getUid().equals(store.accountId());
    }

    private void renderConnectionList() {
        List<FamilyChatStore.ThreadState> threads = new ArrayList<>();
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (store.isConnection(thread)) threads.add(thread);
        }
        threads.sort((first, second) -> Long.compare(lastTime(second), lastTime(first)));
        if (threads.isEmpty()) addLine(text("No connections yet", 14, MUTED, false));
        else for (FamilyChatStore.ThreadState thread : threads) addConversation(thread);
    }

    void showConnectionInvite(String prefilled) {
        if (!verifiedConnectionAccount()) {
            connectionNotice("Sign in with a verified Family Hub account to use invitations");
            return;
        }
        String account = store.accountId();
        ScrollView page = new ScrollView(requireContext());
        LinearLayout body = column();
        body.setPadding(0, space(12), 0, space(10));
        page.addView(body);
        body.addView(text("Share an invite with another Family Hub user.", 15, MUTED, false));
        MaterialButton create = flatButton("Create invite");
        body.addView(create);
        TextView generated = text("", 14, GREEN, true);
        generated.setTextIsSelectable(true);
        body.addView(generated);
        MaterialButton share = flatButton("Share invite link");
        MaterialButton copy = flatButton("Copy code");
        body.addView(share);
        body.addView(copy);
        share.setVisibility(View.GONE);
        copy.setVisibility(View.GONE);
        create.setOnClickListener(view -> {
            create.setEnabled(false);
            store.connections.create(ownStatusName(), code -> {
                if (!isSecondaryPage(page) || !account.equals(store.accountId())) return;
                create.setEnabled(true);
                if (code == null) {
                    connectionNotice("Could not create an invite");
                    return;
                }
                generated.setText(code + "\nExpires in 24 hours");
                share.setVisibility(View.VISIBLE);
                copy.setVisibility(View.VISIBLE);
                share.setOnClickListener(button -> {
                    android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(android.content.Intent.EXTRA_TEXT,
                            "Connect with me on Family Hub\nfamilyhub://chat-invite/" + code + "\nInvite code: " + code);
                    startActivity(android.content.Intent.createChooser(shareIntent, "Share chat invite"));
                });
                copy.setOnClickListener(button -> {
                    android.content.ClipboardManager clipboard = requireContext().getSystemService(android.content.ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chat invite", code));
                });
            });
        });

        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint("Enter invite code");
        if (prefilled != null) input.setText(prefilled);
        body.addView(ChatFormDialog.outlined(input));
        MaterialButton acceptCode = flatButton("Continue");
        body.addView(acceptCode);
        acceptCode.setOnClickListener(view -> {
            String code = input.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (!ConnectionInvites.validCode(code)) {
                input.setError("Enter a valid invite code");
                return;
            }
            acceptCode.setEnabled(false);
            store.connections.claim(code, ownStatusName(), entry -> {
                if (!isSecondaryPage(page) || !account.equals(store.accountId())) return;
                acceptCode.setEnabled(true);
                if (entry == null) connectionNotice("Invite unavailable, expired or already used");
                else if (entry.pendingFor(account)) showConnectionRequests(entry);
                else closeSecondaryPage();
            });
        });
        showSecondaryPage("Connect by invite", page, this::closeSecondaryPage,
                () -> account.equals(store.accountId()) && verifiedConnectionAccount());
    }

    void showConnectionRequests() {
        showConnectionRequests(null);
    }

    private void showConnectionRequests(ConnectionInvites.Invite justClaimed) {
        if (!verifiedConnectionAccount()) {
            connectionNotice("Sign in with a verified Family Hub account to view requests");
            return;
        }
        String account = store.accountId();
        ScrollView page = new ScrollView(requireContext());
        LinearLayout body = column();
        page.addView(body);
        List<ConnectionInvites.Invite> requests = store.connections.entries();
        if (justClaimed != null && requests.stream().noneMatch(item -> item.code.equals(justClaimed.code))) {
            requests.add(justClaimed);
        }
        for (ConnectionInvites.Invite invite : requests) {
            if (!invite.pendingFor(account)) continue;
            LinearLayout card = column();
            card.setPadding(space(10), space(8), space(10), space(8));
            card.setBackground(card(WHITE, EDGE, 16));
            card.addView(text(invite.ownerName + " wants to chat", 16, GREEN, true));
            card.addView(text("Direct chat only", 13, MUTED, false));
            LinearLayout actions = new LinearLayout(requireContext());
            MaterialButton accept = flatButton("Accept");
            MaterialButton decline = flatButton("Decline");
            actions.addView(accept, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(decline, new LinearLayout.LayoutParams(0, -2, 1));
            View.OnClickListener answer = view -> {
                accept.setEnabled(false);
                decline.setEnabled(false);
                store.connections.respond(invite, view == accept, ok -> {
                    if (!isSecondaryPage(page) || !account.equals(store.accountId())) return;
                    if (ok) {
                        body.removeView(card);
                        if (view == accept) closeSecondaryPage();
                        else if (body.getChildCount() == 0) body.addView(text("No pending requests", 14, MUTED, false));
                    } else {
                        accept.setEnabled(true);
                        decline.setEnabled(true);
                        connectionNotice("Could not update this request");
                    }
                });
            };
            accept.setOnClickListener(answer);
            decline.setOnClickListener(answer);
            card.addView(actions);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
            size.topMargin = space(8);
            body.addView(card, size);
        }
        if (body.getChildCount() == 0) body.addView(text("No pending requests", 14, MUTED, false));
        showSecondaryPage("Requests", page, this::closeSecondaryPage,
                () -> account.equals(store.accountId()) && verifiedConnectionAccount());
    }

    void connectionNotice(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void renderList() {
        String filter = choosingLiveRecipient ? search.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        ChatFilter category = choosingLiveRecipient ? ChatFilter.ALL : chatFilter;
        String uid = store.accountId();
        String selfName = uid == null ? "" : ownStatusName();
        boolean showSelf = !choosingLiveRecipient && uid != null
                && (category == ChatFilter.ALL || category == ChatFilter.DIRECT)
                && (selfName + " (You)").toLowerCase(Locale.ROOT).contains(filter);
        List<FamilyChatStore.ThreadState> direct = new ArrayList<>();
        List<FamilyChatStore.ThreadState> groups = new ArrayList<>();
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (store.isSelf(thread) || store.isConnection(thread)) continue;
            if (!store.title(thread).toLowerCase(Locale.ROOT).contains(filter)) continue;
            boolean group = FamilyChatStore.GROUP.equals(thread.peer) || thread.isGroup();
            if ((category == ChatFilter.GROUPS && !group) || (category == ChatFilter.DIRECT && group)
                    || (category == ChatFilter.UNREAD && (thread.error || store.unreadCount(thread) == 0))) continue;
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
        if (showSelf || !direct.isEmpty()) addLine(text("Direct messages", 14, BLUE, true));
        if (showSelf) addLine(selfRow(uid, selfName, true, () -> open(uid)));
        for (FamilyChatStore.ThreadState thread : direct) addConversation(thread);
        if (rows.getChildCount() == 0 && store.status().isEmpty()) {
            addLine(text(category == ChatFilter.UNREAD && filter.isEmpty()
                    ? "No unread chats" : "No matching chats", 14, MUTED, false));
        }
    }

    void showForwardRecipients(FamilyChatStore.ThreadState source,
                               java.util.function.Consumer<FamilyChatStore.ThreadState> select) {
        ScrollView list = new ScrollView(requireContext());
        LinearLayout recipients = column();
        recipients.setPadding(0, space(8), 0, space(8));
        list.addView(recipients);
        FamilyChatStore.ThreadState self = store.thread(store.accountId());
        if (self != null && self != source && store.canUse(self)) {
            recipients.addView(selfRow(self.peer, ownStatusName(), false, () -> {
                if (isSecondaryPage(list) && store.canUse(source) && store.canUse(self)) select.accept(self);
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        for (int sectionIndex = 0; sectionIndex < 3; sectionIndex++) {
            boolean groups = sectionIndex == 0;
            boolean connections = sectionIndex == 2;
            List<FamilyChatStore.ThreadState> section = new ArrayList<>();
            for (FamilyChatStore.ThreadState target : store.threads()) {
                if (target != source && !store.isSelf(target) && store.canUse(target) && target.isGroup() == groups
                        && store.isConnection(target) == connections) section.add(target);
            }
            if (section.isEmpty()) continue;
            section.sort((left, right) -> store.title(left).compareToIgnoreCase(store.title(right)));
            TextView heading = text(connections ? "Connections" : groups ? "Groups" : "Direct messages", 14, BLUE, true);
            heading.setPadding(space(8), space(8), 0, space(4));
            recipients.addView(heading);
            for (FamilyChatStore.ThreadState target : section) {
                String name = store.title(target);
                View avatar = groupScreens.avatar(name, connections ? null : groups ? target.photo : avatars().photo(target.peer),
                        FloatingChatSizing.applies(requireContext()) ? 36 : 44, null);
                LinearLayout content = column();
                content.addView(text(name, 16, INK, true));
                LinearLayout row = avatarRow(avatar, content);
                row.setFocusable(true);
                row.setOnClickListener(view -> {
                    if (isSecondaryPage(list) && store.canUse(source) && store.canUse(target)) select.accept(target);
                });
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
                size.bottomMargin = space(7);
                recipients.addView(row, size);
            }
        }
        if (recipients.getChildCount() == 0) {
            recipients.addView(text("No other chats available", 14, MUTED, false));
        }
        showSecondaryPage("Forward to", list, this::closeSecondaryPage, () -> store.canUse(source));
    }

    void showSecondaryPage(String title, ScrollView scroll, Runnable back,
                           java.util.function.BooleanSupplier allowed) {
        showSecondaryPage(title, "Family Chat", scroll, null, null, back, allowed);
    }

    private void showSecondaryPage(String title, String detail, ScrollView scroll, View footer, View avatar,
                                   Runnable back, java.util.function.BooleanSupplier allowed) {
        if (pageRoot == null || !allowed.getAsBoolean()) return;
        clearSecondaryPage();
        LinearLayout page = column();
        if (!(getActivity() instanceof FloatingChatActivity)) page.setBackgroundResource(R.drawable.bg_page_three_tone);
        page.setPadding(dp(12), dp(8), dp(12), dp(6));
        ModuleOverviewView pageHeader = (ModuleOverviewView) getLayoutInflater()
                .inflate(R.layout.view_family_chat_header, page, false);
        pageHeader.setTitle(title);
        pageHeader.setDetail(detail);
        if (avatar != null) pageHeader.addHeaderAction(avatar);
        pageHeader.showDashboardActions(false);
        pageHeader.setNavigationAction(R.drawable.ic_arrow_back, R.string.back, v -> back.run());
        compactHeader(pageHeader);
        page.addView(pageHeader);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (footer != null) page.addView(footer);
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
        if (store.isConnection(thread)) return groupScreens.avatar(store.title(thread), null, size, null);
        Runnable openInfo = thread.isGroup() ? () -> groupScreens.showGroup(thread)
                : () -> groupScreens.showMember(thread.peer, groupScreens::dismiss);
        return groupScreens.avatar(store.title(thread), thread.isGroup() ? thread.photo : avatars().photo(thread.peer),
                size, choosingLiveRecipient ? null : openInfo);
    }

    private LinearLayout avatarRow(View avatar, LinearLayout content) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(space(12), space(9), space(12), space(9));
        row.setBackground(card(WHITE, EDGE, 16));
        row.addView(avatar);
        content.setPadding(space(12), 0, 0, 0);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout selfRow(String uid, String name, boolean showPreview, Runnable open) {
        LinearLayout content = column();
        content.addView(text(name + " (You)", 16, GREEN, true));
        FamilyChatStore.ThreadState self = store.thread(uid);
        if (showPreview && self != null && !self.messages.isEmpty()) {
            addConversationPreview(content, self.messages.get(self.messages.size() - 1), 0);
        } else {
            org.json.JSONObject latest = showPreview ? latestLegacySelfMessage(uid) : null;
            if (latest == null) content.addView(text("Message yourself", 13, MUTED, false));
            else addPreview(content, "You: " + latest.optString("body"),
                    "Sent · " + dateTime(latest.optLong("at")), 0);
        }
        LinearLayout row = avatarRow(statusAvatar(uid, name, FloatingChatSizing.applies(requireContext()) ? 36 : 44, true), content);
        row.setBackground(card(0xffe5f3ed, 0xffb9d9c9, 16));
        row.setFocusable(true);
        row.setOnClickListener(v -> open.run());
        return row;
    }

    private org.json.JSONArray legacySelfMessages(String uid) {
        android.content.Context context = requireContext();
        String preferences = com.tridev.familyhub.core.security.AccountDataScope
                .preferencesName(context, "family_chat_self", uid);
        android.content.SharedPreferences journal = context.getSharedPreferences(preferences,
                android.content.Context.MODE_PRIVATE);
        try {
            return new org.json.JSONArray(journal.getString("messages", "[]"));
        } catch (org.json.JSONException ignored) {
            return new org.json.JSONArray();
        }
    }

    private org.json.JSONObject latestLegacySelfMessage(String uid) {
        org.json.JSONArray messages = legacySelfMessages(uid);
        for (int i = messages.length() - 1; i >= 0; i--) {
            org.json.JSONObject item = messages.optJSONObject(i);
            if (item != null) return item;
        }
        return null;
    }

    private void addLegacySelfMessages(String uid) {
        org.json.JSONArray messages = legacySelfMessages(uid);
        for (int i = 0; i < messages.length(); i++) {
            org.json.JSONObject item = messages.optJSONObject(i);
            if (item != null) addSelfMessage(rows, item.optString("body"), item.optLong("at"));
        }
    }

    private void addSelfMessage(LinearLayout messages, String body, long at) {
        LinearLayout bubble = column();
        bubble.setPadding(space(10), space(7), space(10), space(7));
        bubble.setBackground(card(0xffe5f2fa, EDGE, 15));
        TextView message = text(body, 16, INK, false);
        message.setTextIsSelectable(true);
        message.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.76f));
        bubble.addView(message);
        TextView time = text(dateTime(at), 11, MUTED, false);
        LinearLayout metadata = new LinearLayout(requireContext());
        metadata.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        metadata.addView(time);
        View tick = receiptTick(true, true);
        tick.setContentDescription("Seen");
        metadata.addView(tick);
        bubble.addView(metadata);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.gravity = Gravity.END;
        size.topMargin = dp(7);
        messages.addView(bubble, size);
    }

    private void addConversationPreview(LinearLayout content, FamilyChatStore.Message latest, int unread) {
        boolean own = store.own(latest);
        String sender = own ? "You: " : store.name(latest.sender) + ": ";
        String direction = own ? (store.sending(latest) ? "Sending · " : "Sent · ") : "Received · ";
        addPreview(content, sender + latest.body, direction + dateTime(store.listTime(latest)), unread);
    }

    private void addPreview(LinearLayout content, String preview, String timestamp, int unread) {
        TextView body = text(preview, 14, MUTED, false);
        body.setMaxLines(1);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(body);
        content.addView(text(timestamp, 12, unread == 0 ? MUTED : BLUE, unread != 0));
    }

    private void addConversation(FamilyChatStore.ThreadState thread) {
        LinearLayout card = column();
        TextView name = text(store.title(thread), 16, INK, true);
        card.addView(name);
        int unread = store.unreadCount(thread);
        if (thread.messages.isEmpty()) {
            card.addView(text(thread.error ? "Chat is unavailable" : "Start a conversation", 13, MUTED, false));
        } else {
            FamilyChatStore.Message latest = thread.messages.get(thread.messages.size() - 1);
            if (store.isConnection(thread) && !store.own(latest)) {
                addPreview(card, store.title(thread) + ": " + latest.body,
                        "Received · " + dateTime(store.listTime(latest)), unread);
            } else addConversationPreview(card, latest, unread);
        }
        LinearLayout row = avatarRow(threadAvatar(thread, FloatingChatSizing.applies(requireContext()) ? 36 : 44), card);
        if (unread > 0) {
            TextView badge = text(Integer.toString(unread), 12, 0xffffffff, true);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(24));
            badge.setPadding(dp(5), dp(3), dp(5), dp(3));
            badge.setBackground(card(GREEN, GREEN, 12));
            badge.setContentDescription(unread + " unread messages");
            row.addView(badge);
        }
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
        openLinkedFeature(com.tridev.familyhub.feature.tasks.FamilyTasksFragment.forLinkedTask(task.cloudId));
    }

    void openLinkedFeature(Fragment feature) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openFeature(feature);
        } else if (getActivity() instanceof FloatingChatActivity) {
            ((FloatingChatActivity) getActivity()).openLinkedFeature(feature);
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

    boolean hiddenForMe(FamilyChatStore.Message message) {
        return store.hiddenForMe(message);
    }

    void deleteForMe(FamilyChatStore.ThreadState thread, List<FamilyChatStore.Message> messages) {
        if (!isVoiceThreadOpen(thread) || !store.canUse(thread)) return;
        for (FamilyChatStore.Message message : messages) {
            voiceNotes.stopMessagePlayback(message);
            if (java.util.Objects.equals(replyKey, message.snapshot.getKey())) clearReply();
        }
        store.hideMessages(thread, messages);
    }

    private void updateJumpToLatest() {
        if (jumpToLatest == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        boolean visible = thread != null && !thread.error && !scrollingUp && scroll.canScrollVertically(1);
        jumpToLatest.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        int unread = store.unreadCount(thread);
        newMessageCount.setVisibility(unread == 0 ? View.GONE : View.VISIBLE);
        newMessageCount.setText(Integer.toString(unread));
        jumpToLatest.setContentDescription(unread == 0 ? "Jump to latest message"
                : "Jump to latest message. New messages: " + unread);
    }

    private void selectMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (searching || !isVoiceThreadOpen(thread) || message.deleted() || hiddenForMe(message)) return;
        String key = message.snapshot.getKey();
        if (!selectedMessageKeys.add(key)) selectedMessageKeys.remove(key);
        updateMessageSelection();
    }

    void clearMessageSelection() {
        if (selectedMessageKeys.isEmpty()) return;
        selectedMessageKeys.clear();
        updateMessageSelection();
    }

    private void updateMessageSelection() {
        if (screen == null || selectionToolbar == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        List<FamilyChatStore.Message> selected = new ArrayList<>();
        java.util.Set<String> valid = new java.util.HashSet<>();
        if (!selectedMessageKeys.isEmpty() && thread != null && store.canUse(thread)) {
            for (FamilyChatStore.Message message : displayedMessages) {
                String key = message.snapshot.getKey();
                if (selectedMessageKeys.contains(key) && !message.deleted() && !hiddenForMe(message)) {
                    selected.add(message);
                    valid.add(key);
                }
            }
        }
        selectedMessageKeys.retainAll(valid);
        for (View bubble : bubbles) {
            if (bubble == null) continue;
            boolean highlighted = selectedMessageKeys.contains(bubble.getTag())
                    || (searching && activeMatch != null && activeMatch.equals(bubble.getTag()));
            bubble.setSelected(highlighted);
            boolean pinned = highlightedPinKey != null && highlightedPinKey.equals(bubble.getTag());
            bubble.setForeground(highlighted ? card(0x2465ae85, 0xff42977c, 15)
                    : pinned ? card(0x38ffd16a, 0xffc79840, 15) : null);
        }
        header.setVisibility(selected.isEmpty() && !searching ? View.VISIBLE : View.GONE);
        selectionToolbar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        selectionToolbar.removeAllViews();
        if (selected.isEmpty()) return;
        android.widget.ImageButton back = new android.widget.ImageButton(requireContext());
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(GREEN));
        back.setBackground(card(0xfff5fcf8, 0xffe4f3ec, 10));
        back.setContentDescription("Clear message selection");
        back.setOnClickListener(view -> clearMessageSelection());
        selectionToolbar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(46)));
        TextView count = text(Integer.toString(selected.size()), 17, GREEN, true);
        count.setGravity(Gravity.CENTER_VERTICAL);
        count.setMinWidth(dp(22));
        count.setContentDescription(selected.size() + " messages selected");
        selectionToolbar.addView(count, new LinearLayout.LayoutParams(0, -1, 1));
        smart.selectionActions(thread, selected, selectionToolbar);
    }

    private void renderThread(FamilyChatStore.ThreadState thread, int oldY, boolean atBottom) {
        String latestKey = thread.messages.isEmpty() ? null
                : thread.messages.get(thread.messages.size() - 1).snapshot.getKey();
        boolean goToBottom = lastMessageKey == null || atBottom;
        lastMessageKey = latestKey;
        if (store.isSelf(thread)) addLegacySelfMessages(thread.peer);
        if (thread.messages.isEmpty() && rows.getChildCount() == 0) addLine(text("No messages yet", 14, MUTED, false));
        ChatAttachmentCards cards = new ChatAttachmentCards(requireContext(), attachments);
        int screenWidth = screen.getWidth() > 0 ? screen.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int compactWidth = Math.min(dp(420), Math.round(screenWidth * 0.82f));
        int compactMediaWidth = Math.max(1, compactWidth - dp(20));
        displayedMessages = new ArrayList<>(thread.messages);
        java.util.regex.Pattern keyword = searching ? searchPattern() : null;
        FamilyChatStore.Message searchHit = null;
        if (searching) {
            threadMatches = keyword == null ? java.util.Collections.emptyList()
                    : keywordSearch.matches(thread, keyword, this::hiddenForMe);
            int selected = searchMatchIndex();
            if (!threadMatches.isEmpty()) {
                searchHit = threadMatches.get(Math.max(0, selected));
                activeMatch = searchHit.snapshot.getKey();
            }
            searchCount.setText(searchHit == null ? searchStatus()
                    : (Math.max(0, selected) + 1) + " / " + threadMatches.size()
                            + (keywordSearch.loading ? " · Searching…" : keywordSearch.incomplete ? " · Partial" : ""));
            searchNavigation.getChildAt(1).setEnabled(threadMatches.size() > 1);
            searchNavigation.getChildAt(2).setEnabled(threadMatches.size() > 1);
        }
        includeHistoricalMessage(historicalPin);
        includeHistoricalMessage(searchHit);
        java.util.Map<String, FamilyChatStore.Message> known = new java.util.HashMap<>();
        for (FamilyChatStore.Message item : displayedMessages) known.put(item.snapshot.getKey(), item);
        for (FamilyChatStore.Message message : displayedMessages) {
            if (hiddenForMe(message)) {
                bubbles.add(null);
                continue;
            }
            boolean own = store.own(message);
            if (message.deleted()) voiceNotes.stopMessagePlayback(message);
            LinearLayout bubble = column();
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(space(10), space(7), space(10), space(7));
            bubble.setBackground(card(own ? 0xffe5f2fa : WHITE, EDGE, 15));
            bubble.setTag(message.snapshot.getKey());
            if (selectedMessageKeys.contains(bubble.getTag())) {
                bubble.setSelected(true);
                bubble.setForeground(card(0x2465ae85, 0xff42977c, 15));
            }
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
                if (keyword != null && !message.deleted() && keyword.matcher(message.body).find()) {
                    TextView match = text(message.body, 14, INK, false);
                    match.setText(ChatKeywordSearch.highlight(message.body, keyword));
                    bubble.addView(match);
                }
            } else {
                TextView messageText = text(message.body, 17, INK, false);
                if (keyword != null && !message.deleted()) messageText.setText(ChatKeywordSearch.highlight(message.body, keyword));
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
                boolean seen = (store.isSelf(thread) && !store.sending(message))
                        || store.acknowledged(thread, message, true);
                boolean delivered = seen || store.acknowledged(thread, message, false);
                boolean sending = store.sending(message) && !delivered;
                View tick = sending ? text("◷", 13, MUTED, true) : receiptTick(delivered, seen);
                tick.setContentDescription(seen ? "Seen" : delivered ? "Delivered" : sending ? "Sending" : "Sent");
                metadata.addView(tick);
            }
            bubble.addView(metadata);
            if (attachment == null) bubble.measure(View.MeasureSpec.makeMeasureSpec(compactWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int bubbleWidth = attachment == null ? Math.min(compactWidth, Math.max(space(140), bubble.getMeasuredWidth())) : compactWidth;
            Runnable select = () -> selectMessage(thread, message);
            LinearLayout messageRow = new ChatMessageBubble(requireContext(), (view, touch) -> select.run(),
                    () -> !selectedMessageKeys.isEmpty(), select);
            messageRow.setGravity((own ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = space(7);
            if (!own) messageRow.setPaddingRelative(0, 0, dp(24), 0);
            if (own && !message.deleted()) {
                MaterialButton reply = new MaterialButton(requireContext(), null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                reply.setIconResource(R.drawable.ic_chat_reply);
                reply.setIconSize(space(22));
                reply.setIconPadding(0);
                reply.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                reply.setIconTint(android.content.res.ColorStateList.valueOf(BLUE));
                reply.setPadding(0, 0, 0, 0);
                reply.setContentDescription("Reply to this message");
                reply.setOnClickListener(v -> replyTo(message));
                int maxBubble = Math.max(dp(100), rows.getWidth() > 0
                        ? rows.getWidth() - target(48) : screenWidth - target(48) - dp(20));
                bubbleWidth = Math.min(bubbleWidth, maxBubble);
                messageRow.addView(reply, new LinearLayout.LayoutParams(target(48), target(48)));
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
        if (screen == null || secondaryPage != null || !isResumed() || peer == null
                || !screen.isShown() || !screen.hasWindowFocus()) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        if (thread == null || bubbles.size() != displayedMessages.size()) return;
        android.graphics.Rect viewport = new android.graphics.Rect();
        if (!scroll.getGlobalVisibleRect(viewport)) return;
        for (int i = 0; i < bubbles.size(); i++) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            FamilyChatStore.Message message = displayedMessages.get(i);
            if (!message.deleted() && store.unread(message) && bubbles.get(i) != null
                    && bubbles.get(i).getGlobalVisibleRect(bounds) && android.graphics.Rect.intersects(viewport, bounds)) {
                store.receipt(thread, message, true);
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
        view.setTextSize(FloatingChatSizing.text(requireContext(), size));
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private void addLine(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = space(8);
        rows.addView(view, params);
    }

    private GradientDrawable card(int fill, int edge, int radius) {
        boolean floating = getActivity() instanceof FloatingChatActivity;
        GradientDrawable drawable = floating && (fill == WHITE || fill == 0xffe5f2fa)
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{fill, fill == WHITE ? 0xfff4f9fc : 0xffdbeef9})
                : new GradientDrawable();
        if (!floating || (fill != WHITE && fill != 0xffe5f2fa)) drawable.setColor(fill);
        drawable.setCornerRadius(space(radius));
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
                paint.setColor(seen ? 0xff00432a : MUTED);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(seen ? 2f : 1.65f);
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

    private void compactHeader(ModuleOverviewView view) {
        if (!FloatingChatSizing.applies(requireContext())) return;
        TextView title = view.findViewById(R.id.module_overview_title);
        TextView detail = view.findViewById(R.id.module_overview_detail);
        title.setTextSize(15);
        detail.setTextSize(11);
        View iconTile = view.findViewById(R.id.module_overview_icon_container);
        android.view.ViewGroup.LayoutParams tile = iconTile.getLayoutParams();
        tile.width = dp(38);
        tile.height = dp(38);
        iconTile.setLayoutParams(tile);
        View headerRow = (View) iconTile.getParent();
        headerRow.setPaddingRelative(dp(6), dp(3), dp(6), dp(3));
    }

    private int space(int value) { return FloatingChatSizing.pixels(requireContext(), value); }

    private int target(int value) { return FloatingChatSizing.target(requireContext(), value); }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
