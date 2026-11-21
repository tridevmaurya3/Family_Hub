package com.tridev.familyhub.feature.chat;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatShareActivity extends AppCompatActivity {
    private static final int GREEN = 0xff146553;
    private static final int INK = 0xff1b3030;
    private static final int MINT = 0xffd3f1e5;
    private static final int PANEL = 0xfff6f3fc;
    private static final int LIMIT = 2000;

    private final FamilyChatStore store = FamilyChatStore.get();
    private final Runnable observer = this::refreshRecipients;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FirebaseAuth.AuthStateListener authListener = auth -> {
        if (!sameAccount()) finish();
    };

    private String account, body, encoded, caption = "";
    private Map<String, Object> details = Collections.emptyMap();
    private Bitmap thumbnail;
    private FamilyChatStore.ThreadState selected;
    private boolean connections, reviewing, sending;
    private LinearLayout panel, recipients;
    private EditText captionInput;
    private MaterialButton next;
    private String query = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.42f);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(-1, -2);
        setFinishOnTouchOutside(false);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            closeWith("Sign in to Family Hub, then share again.");
            return;
        }
        account = user.getUid();
        if (!Intent.ACTION_SEND.equals(getIntent().getAction())) {
            finish();
            return;
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (sending) return;
                if (reviewing) {
                    rememberCaption();
                    showPicker();
                } else finish();
            }
        });
        showPanel("Share to Family Chat");
        panel.addView(label("Preparing shared item…", 15, false));
        readShare();
    }

    @Override protected void onStart() {
        super.onStart();
        if (account == null || isFinishing()) return;
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
        store.observe(observer);
        refreshRecipients();
    }

    @Override protected void onStop() {
        FirebaseAuth.getInstance().removeAuthStateListener(authListener);
        store.removeObserver(observer);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private boolean sameAccount() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null && user.getUid().equals(account);
    }

    private void readShare() {
        Intent intent = getIntent();
        Uri uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() == 1) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = shared == null ? "" : shared.toString().trim();
        if (uri == null) {
            if (text.isEmpty() || text.length() > LIMIT) {
                closeWith("Share text or a link up to 2,000 characters.");
                return;
            }
            body = text;
            showPicker();
            return;
        }
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null
                || uri.getAuthority().startsWith(getPackageName())
                || checkUriPermission(uri, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            closeWith("Family Chat couldn't open this shared file.");
            return;
        }
        Uri attachment = uri;
        worker.execute(() -> {
            try {
                byte[] bytes = ChatAttachments.readFile(this, attachment);
                String type = getContentResolver().getType(attachment);
                String mime = type == null ? intent.getType() : type;
                if (mime == null || mime.length() > 100 || mime.contains("*")) {
                    mime = "application/octet-stream";
                }
                Bitmap preview = mime.startsWith("image/") ? preview(bytes) : null;
                String name = ChatAttachments.fileName(this, attachment);
                String data = Base64.encodeToString(bytes, Base64.NO_WRAP);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("kind", preview == null ? "file" : "photo");
                metadata.put("mime", mime);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !sameAccount()) return;
                    body = name;
                    encoded = data;
                    details = metadata;
                    thumbnail = preview;
                    caption = text.substring(0, Math.min(text.length(), LIMIT));
                    showPicker();
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) closeWith("Couldn't read the attachment. The chat file limit is 1 MB.");
                });
            }
        });
    }

    private static Bitmap preview(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (Math.max(options.outWidth, options.outHeight) > 4096) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, Integer.highestOneBit(
                Math.max(options.outWidth, options.outHeight) / 512));
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void showPicker() {
        reviewing = false;
        showPanel("Share to Family Chat");
        addPreview(false);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search chats");
        search.setText(query);
        panel.addView(ChatFormDialog.outlined(search));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                refreshRecipients();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        LinearLayout tabs = new LinearLayout(this);
        addAction(tabs, "Family Chat", !connections, () -> switchTab(false));
        addAction(tabs, "Connections", connections, () -> switchTab(true));
        panel.addView(tabs);
        ScrollView list = new ScrollView(this);
        recipients = column();
        list.setNestedScrollingEnabled(true);
        list.addView(recipients);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(180)));
        next = button("Next", true, () -> {
            if (validRecipient()) showReview();
        });
        panel.addView(next, new LinearLayout.LayoutParams(-1, -2));
        refreshRecipients();
    }

    private void switchTab(boolean showConnections) {
        if (connections == showConnections) return;
        connections = showConnections;
        selected = null;
        showPicker();
    }

    private boolean validRecipient() {
        return selected != null && sameAccount() && account.equals(store.accountId())
                && store.canUse(selected);
    }

    private void refreshRecipients() {
        if (reviewing || recipients == null || isFinishing()) return;
        recipients.removeAllViews();
        if (!validRecipient()) selected = null;
        if (sameAccount() && account.equals(store.accountId())) {
            java.util.List<FamilyChatStore.ThreadState> threads = store.threads();
            threads.sort(java.util.Comparator.comparing((FamilyChatStore.ThreadState t) -> !store.isSelf(t))
                    .thenComparing(t -> !t.isGroup()).thenComparing(store::title, String.CASE_INSENSITIVE_ORDER));
            String keyword = query.trim().toLowerCase(Locale.ROOT);
            for (FamilyChatStore.ThreadState thread : threads) {
                if (!store.canUse(thread) || store.isConnection(thread) != connections) continue;
                String title = recipientName(thread);
                if (!title.toLowerCase(Locale.ROOT).contains(keyword)) continue;
                MaterialButton row = button((thread == selected ? "✓  " : "") + title,
                        thread == selected, () -> {
                            selected = thread;
                            refreshRecipients();
                        });
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                row.setSelected(thread == selected);
                recipients.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        if (recipients.getChildCount() == 0) {
            recipients.addView(label(store.online() ? "No chats found" : "Connecting…", 15, false));
        }
        next.setEnabled(validRecipient());
    }

    private String recipientName(FamilyChatStore.ThreadState thread) {
        return store.isSelf(thread) ? "You · Message yourself" : store.title(thread);
    }

    private void showReview() {
        reviewing = true;
        showPanel("Send to " + recipientName(selected));
        addPreview(true);
        captionInput = null;
        if (encoded != null) {
            captionInput = new EditText(this);
            captionInput.setHint("Add a caption");
            captionInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LIMIT)});
            captionInput.setText(caption);
            captionInput.setMaxLines(2);
            panel.addView(ChatFormDialog.outlined(captionInput));
        }
        LinearLayout actions = new LinearLayout(this);
        addAction(actions, "Back", false, () -> {
            if (sending) return;
            rememberCaption();
            showPicker();
        });
        MaterialButton send = button("Send", true, null);
        send.setOnClickListener(v -> send(send));
        actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(actions);
    }

    private void rememberCaption() {
        if (captionInput != null) caption = captionInput.getText().toString().trim();
    }

    private void send(MaterialButton action) {
        if (sending) return;
        if (!validRecipient() || !store.online()) {
            Toast.makeText(this, "Chat is unavailable. Check your connection and try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        rememberCaption();
        sending = true;
        action.setEnabled(false);
        action.setText("Sending…");
        store.send(selected, body, details, encoded, caption, () -> {}, success -> {
            if (isFinishing() || isDestroyed()) return;
            if (success) closeWith("Sent to Family Chat");
            else {
                sending = false;
                action.setEnabled(true);
                action.setText("Send");
                Toast.makeText(this, "Couldn't send. Try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPanel(String title) {
        panel = column();
        panel.setFocusableInTouchMode(true);
        panel.setPadding(dp(14), dp(8), dp(14), dp(12));
        panel.setBackground(shape(PANEL));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        setContentView(scroll);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(shape(MINT));
        TextView heading = label(title, 19, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton close = button("×", false, () -> {
            if (!sending) finish();
        });
        close.setContentDescription("Close share panel");
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(48)));
        panel.addView(header);
        getWindow().setLayout(-1, -2);
    }

    private void addPreview(boolean large) {
        if (thumbnail != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(thumbnail);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(body);
            panel.addView(image, new LinearLayout.LayoutParams(-1, dp(large ? 165 : 72)));
        }
        TextView item = label(body, 15, false);
        item.setMaxLines(large ? 6 : 2);
        item.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(item);
    }

    private void addAction(LinearLayout row, String title, boolean primary, Runnable action) {
        row.addView(button(title, primary, action), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private MaterialButton button(String title, boolean primary, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(primary ? 0xffffffff : GREEN);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? GREEN : 0xffebe6f7));
        button.setCornerRadius(dp(14));
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(INK);
        label.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private void closeWith(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
