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
    private final Runnable observer = this::render;
    private final List<View> bubbles = new ArrayList<>();
    private LinearLayout screen, header, rows, bottom;
    private ScrollView scroll;
    private EditText search, draft;
    private TextView title, status;
    private MaterialButton back, send;
    private OnBackPressedCallback backCallback;
    private String peer;
    private String lastMessageKey;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) peer = state.getString("chat_peer");
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("chat_peer", peer);
        if (draft != null) out.putString("chat_draft", draft.getText().toString());
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                       @Nullable Bundle state) {
        screen = column();
        screen.setPadding(dp(10), dp(8), dp(10), dp(6));
        header = new LinearLayout(requireContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(5), dp(8), dp(5));
        header.setBackground(card(WHITE, EDGE, 18));
        back = new MaterialButton(requireContext());
        back.setText("‹");
        back.setTextSize(24);
        back.setContentDescription("Back to chats");
        back.setOnClickListener(v -> open(null));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout headings = column();
        title = text("Family Chat", 19, INK, true);
        status = text("", 12, GREEN, false);
        headings.addView(title);
        headings.addView(status);
        header.addView(headings, new LinearLayout.LayoutParams(0, -2, 1));
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

        scroll = new ScrollView(requireContext());
        rows = column();
        scroll.addView(rows);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
        bottom.addView(draft, new LinearLayout.LayoutParams(0, -2, 1));
        send = new MaterialButton(requireContext());
        send.setText("Send");
        send.setAllCaps(false);
        send.setContentDescription("Send message");
        bottom.addView(send);
        screen.addView(bottom);
        if (state != null) draft.setText(state.getString("chat_draft", ""));
        screen.setFocusableInTouchMode(true);
        screen.requestFocus();

        send.setOnClickListener(v -> {
            FamilyChatStore.ThreadState thread = store.thread(peer);
            String message = draft.getText().toString().trim();
            if (thread == null || thread.error || message.isEmpty()) return;
            draft.setText("");
            store.send(thread, message, () -> {
                if (screen == null) return;
                if (thread.peer.equals(peer) && draft.getText().length() == 0) draft.setText(message);
                Toast.makeText(requireContext(), "Unable to send message", Toast.LENGTH_SHORT).show();
            });
        });
        draft.addTextChangedListener(watcher(this::updateSend));
        return screen;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        backCallback = new OnBackPressedCallback(peer != null) {
            @Override public void handleOnBackPressed() { open(null); }
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
        render();
    }

    @Override public void onResume() {
        super.onResume();
        if (scroll != null) scroll.post(this::markVisibleRead);
    }

    @Override public void onStop() {
        store.removeObserver(observer);
        super.onStop();
    }

    @Override public void onDestroyView() {
        screen = null;
        bubbles.clear();
        super.onDestroyView();
    }

    private void open(@Nullable String destination) {
        peer = destination;
        backCallback.setEnabled(peer != null);
        lastMessageKey = null;
        draft.setText("");
        render();
    }

    private void updateSend() {
        if (send == null || draft == null) return;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        send.setEnabled(thread != null && !thread.error && !draft.getText().toString().trim().isEmpty());
    }

    private void render() {
        if (screen == null) return;
        boolean inThread = peer != null;
        FamilyChatStore.ThreadState thread = store.thread(peer);
        back.setVisibility(inThread ? View.VISIBLE : View.GONE);
        search.setVisibility(inThread ? View.GONE : View.VISIBLE);
        bottom.setVisibility(inThread && thread != null && !thread.error ? View.VISIBLE : View.GONE);
        title.setText(thread == null ? "Family Chat" : store.title(thread));
        String currentStatus = store.status();
        status.setText(!currentStatus.isEmpty() ? currentStatus
                : store.online() ? "Live · Family sync" : "Offline · messages send when reconnected");
        status.setTextColor(store.online() ? GREEN : MUTED);
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
        for (FamilyChatStore.ThreadState thread : store.threads()) {
            if (!store.title(thread).toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (FamilyChatStore.GROUP.equals(thread.peer)) {
                addLine(text("Group", 14, BLUE, true));
                addConversation(thread);
            } else direct.add(thread);
        }
        direct.sort((first, second) -> Long.compare(lastTime(second), lastTime(first)));
        if (!direct.isEmpty()) addLine(text("Direct messages", 14, BLUE, true));
        for (FamilyChatStore.ThreadState thread : direct) addConversation(thread);
        if (rows.getChildCount() == 0 && store.status().isEmpty()) {
            addLine(text("No matching chats", 14, MUTED, false));
        }
    }

    private void addConversation(FamilyChatStore.ThreadState thread) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(card(WHITE, EDGE, 16));
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
        card.setFocusable(true);
        card.setOnClickListener(v -> open(thread.peer));
        addLine(card);
    }

    private void renderThread(FamilyChatStore.ThreadState thread, int oldY) {
        String latestKey = thread.messages.isEmpty() ? null
                : thread.messages.get(thread.messages.size() - 1).snapshot.getKey();
        boolean goToBottom = !java.util.Objects.equals(lastMessageKey, latestKey);
        lastMessageKey = latestKey;
        if (thread.messages.isEmpty()) addLine(text("No messages yet", 14, MUTED, false));
        for (FamilyChatStore.Message message : thread.messages) {
            boolean own = store.own(message);
            LinearLayout bubble = column();
            bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
            bubble.setBackground(card(own ? 0xffe5f2fa : WHITE, EDGE, 15));
            if (!own && FamilyChatStore.GROUP.equals(thread.peer)) {
                bubble.addView(text(store.name(message.sender), 13, GREEN, true));
            }
            TextView messageText = text(message.body, 15, INK, false);
            messageText.setTextIsSelectable(true);
            bubble.addView(messageText);
            LinearLayout metadata = new LinearLayout(requireContext());
            metadata.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            metadata.addView(text(dateTime(message.at), 11, MUTED, false));
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
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
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
