package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.core.security.FamilyHubAppLockManager;
import com.tridev.familyhub.feature.main.MainActivity;

public final class FloatingChatActivity extends AppCompatActivity {
    private static final int GREEN = 0xff146553;
    private static final int BLUE = 0xff0f6cbd;
    private static final String CHAT_TAG = "floating_family_chat";
    private SharedPreferences preferences;
    private LinearLayout surface;
    private LinearLayout controls;
    private FrameLayout content;
    private TextView shortcut;
    private final Runnable unreadObserver = () -> updateShortcutUnread(FamilyChatStore.unreadTotal());
    private FrameLayout resizeHandles;
    private GradientDrawable background;
    private String account;
    private int x, y, width, height;
    private boolean collapsed;
    private FirebaseAuth.AuthStateListener accountListener;

    public static Intent launchIntent(Context context) {
        return AccountDataScope.stamp(context, new Intent(context, FloatingChatActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = getIntent().getStringExtra(AccountDataScope.EXTRA_UID);
        if (!authorized()) {
            Toast.makeText(this, "Sign in to Family Hub to open chat", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        setFinishOnTouchOutside(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        preferences = getSharedPreferences("floating_chat_window", MODE_PRIVATE);
        Rect area = displayArea();
        width = saved == null ? preferences.getInt("width", dp(360)) : saved.getInt("width");
        height = saved == null ? preferences.getInt("height", dp(620)) : saved.getInt("height");
        x = saved == null ? preferences.getInt("x", Math.max(0, area.width() - width - dp(10))) : saved.getInt("x");
        y = saved == null ? preferences.getInt("y", dp(42)) : saved.getInt("y");
        collapsed = saved != null && saved.getBoolean("collapsed");

        background = shape(20, 0xffc7d5dc, 0xffeffaf3, 0xfffff4f5, 0xffeef6fd);
        background.setAlpha(preferences.getInt("opacity", 100) * 255 / 100);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackground(background);
        surface.setClipToOutline(true);
        surface.setPadding(dp(8), dp(6), dp(8), dp(14));
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), 0, dp(1), 0);
        controls.setBackground(shape(12, 0xffc7d5dc, 0xfff5fcf8, 0xffe6f1f9));
        TextView drag = label("Family Chat", 13, GREEN);
        drag.setPadding(dp(5), 0, 0, 0);
        drag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(drag, new LinearLayout.LayoutParams(0, dp(38), 1));
        drag.setOnTouchListener(windowGesture(0, 0, null));
        addControl("⋮", "Panel settings", this::showSettings);
        addControl("−", "Minimize floating chat", () -> setCollapsed(true));
        addControl("↗", "Open in Family Hub", this::openFullApp);
        addControl("×", "Close floating chat", this::finish);
        LinearLayout.LayoutParams controlRow = new LinearLayout.LayoutParams(-1, dp(38));
        controlRow.setMargins(dp(14), 0, dp(14), 0);
        surface.addView(controls, controlRow);

        content = new FrameLayout(this);
        content.setId(R.id.floating_chat_content);
        surface.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        resizeHandles = new FrameLayout(this);
        root.addView(resizeHandles, new FrameLayout.LayoutParams(-1, -1));
        addResizeHandle(-1, -1, "top left");
        addResizeHandle(1, -1, "top right");
        addResizeHandle(-1, 1, "bottom left");
        addResizeHandle(1, 1, "bottom right");

        shortcut = label("Chat", 13, Color.WHITE);
        shortcut.setBackground(shape(16, 0xff58a3cb, 0xff2489ca, BLUE));
        shortcut.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_nav_chat, 0, 0);
        shortcut.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        shortcut.setPadding(0, dp(6), 0, dp(3));
        shortcut.setContentDescription("Restore floating Family Chat");
        shortcut.setOnClickListener(v -> setCollapsed(false));
        shortcut.setOnTouchListener(windowGesture(0, 0, shortcut::performClick));
        root.addView(shortcut, new FrameLayout.LayoutParams(-1, -1));
        FamilyChatStore.observeUnread(unreadObserver);
        updateVisibility();
        applyBounds();

        if (getSupportFragmentManager().findFragmentById(R.id.floating_chat_content) == null) getSupportFragmentManager().beginTransaction()
                .replace(R.id.floating_chat_content, new FamilyChatFragment(), CHAT_TAG)
                .commitNow();
        getSupportFragmentManager().addOnBackStackChangedListener(
                () -> FamilyHubAppLockManager.refreshFloatingChatProtection(this));
        accountListener = auth -> { if (!authorized()) finish(); };
        FirebaseAuth.getInstance().addAuthStateListener(accountListener);
    }

    private boolean authorized() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null && user.isEmailVerified() && user.getUid().equals(account);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (authorized() && account.equals(intent.getStringExtra(AccountDataScope.EXTRA_UID))) setCollapsed(false);
        else finish();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!authorized()) finish();
    }

    @Override protected void onDestroy() {
        FamilyChatStore.removeUnreadObserver(unreadObserver);
        if (accountListener != null) FirebaseAuth.getInstance().removeAuthStateListener(accountListener);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("x", x);
        state.putInt("y", y);
        state.putInt("width", width);
        state.putInt("height", height);
        state.putBoolean("collapsed", collapsed);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE && panelVisible()) {
            setCollapsed(true);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    public boolean isShowingChatOnly() {
        return getSupportFragmentManager().findFragmentById(R.id.floating_chat_content) instanceof FamilyChatFragment;
    }

    boolean panelVisible() {
        return !collapsed && !isFinishing();
    }

    void updateShortcutUnread(int count) {
        if (shortcut == null) return;
        shortcut.setText(count == 0 ? "Chat" : count > 99 ? "99+" : Integer.toString(count));
        shortcut.setContentDescription(count == 0 ? "Restore floating Family Chat"
                : "Restore floating Family Chat. Unread messages: " + count);
    }

    private FamilyChatFragment chat() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(CHAT_TAG);
        return fragment instanceof FamilyChatFragment ? (FamilyChatFragment) fragment : null;
    }

    void openLinkedFeature(Fragment feature) {
        if (!panelVisible() || !authorized()) return;
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.floating_chat_content, feature)
                .addToBackStack(null).commit();
    }

    void fitDialog(android.app.Dialog dialog, boolean fullPanel) {
        Window dialogWindow = dialog.getWindow();
        if (dialogWindow == null) return;
        Rect panel = new Rect();
        getWindow().getDecorView().getGlobalVisibleRect(panel);
        if (panel.isEmpty()) return;
        int margin = fullPanel ? dp(4) : dp(8);
        WindowManager.LayoutParams layout = dialogWindow.getAttributes();
        layout.width = Math.max(1, panel.width() - margin * 2);
        layout.height = fullPanel ? Math.max(1, panel.height() - margin * 2) : WindowManager.LayoutParams.WRAP_CONTENT;
        dialogWindow.setAttributes(layout);
    }

    private void openFullApp() {
        if (!authorized()) return;
        FamilyChatFragment chat = chat();
        Intent intent = AccountDataScope.stamp(this, new Intent(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true);
        if (chat != null) intent.putExtra(MainActivity.EXTRA_CHAT_CONVERSATION, chat.openConversationReference());
        startActivity(intent);
        finish();
    }

    void showSettings() {
        ChatActionDialog design = new ChatActionDialog(this, "Floating chat", R.drawable.ic_nav_chat);
        TextView opacity = design.label("Panel transparency", 14, GREEN, true);
        design.add(design.content, opacity, 10);
        SeekBar slider = new SeekBar(this);
        slider.setMin(72);
        slider.setMax(100);
        slider.setProgress(preferences.getInt("opacity", 100));
        slider.setProgressTintList(ColorStateList.valueOf(BLUE));
        slider.setThumbTintList(ColorStateList.valueOf(BLUE));
        slider.setContentDescription("Panel opacity");
        opacity.setText("Panel transparency · " + slider.getProgress() + "%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int percent, boolean user) {
                background.setAlpha(percent * 255 / 100);
                opacity.setText("Panel transparency · " + percent + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                preferences.edit().putInt("opacity", seekBar.getProgress()).apply();
            }
        });
        design.add(design.content, slider, 4);
        new ChatFormDialog(this).setView(design.content).setPositiveButton("Done", null).show();
    }

    private void setCollapsed(boolean value) {
        if (collapsed == value) return;
        if (value) {
            FamilyChatFragment chat = chat();
            if (chat != null) chat.stopFloatingAudio();
            View focus = getCurrentFocus();
            if (focus != null) {
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
                focus.clearFocus();
            }
        }
        collapsed = value;
        updateVisibility();
        applyBounds();
    }

    private void updateVisibility() {
        surface.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        resizeHandles.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        shortcut.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        if (collapsed) getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private void addControl(String symbol, String description, Runnable action) {
        TextView control = label(symbol, 20, BLUE);
        control.setBackground(shape(9, 0xffbfd4de, 0xffffffff, 0xffe9f4f9));
        control.setContentDescription(description);
        control.setFocusable(true);
        control.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(34), dp(34));
        size.setMarginStart(dp(4));
        controls.addView(control, size);
    }

    private void addResizeHandle(int horizontal, int vertical, String corner) {
        TextView handle = label("↘", 12, 0x70146553);
        handle.setScaleX(horizontal);
        handle.setScaleY(vertical);
        handle.setContentDescription("Drag to resize from " + corner);
        handle.setOnTouchListener(windowGesture(horizontal, vertical, null));
        int gravity = (horizontal < 0 ? Gravity.LEFT : Gravity.RIGHT)
                | (vertical < 0 ? Gravity.TOP : Gravity.BOTTOM);
        resizeHandles.addView(handle, new FrameLayout.LayoutParams(dp(24), dp(24), gravity));
    }

    private View.OnTouchListener windowGesture(int horizontal, int vertical, Runnable tap) {
        return new View.OnTouchListener() {
            float rawX, rawY;
            int originX, originY, originWidth, originHeight;
            boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        rawX = event.getRawX();
                        rawY = event.getRawY();
                        originX = x;
                        originY = y;
                        originWidth = width;
                        originHeight = height;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - rawX);
                        int dy = Math.round(event.getRawY() - rawY);
                        moved |= Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4);
                        if (!moved) return true;
                        if (horizontal != 0) {
                            Rect area = displayArea();
                            int maxWidth = horizontal < 0 ? originX + originWidth : area.width() - originX;
                            int maxHeight = vertical < 0 ? originY + originHeight : area.height() - originY;
                            width = clamp(originWidth + horizontal * dx, dp(285), maxWidth);
                            height = clamp(originHeight + vertical * dy, dp(310), maxHeight);
                            x = horizontal < 0 ? originX + originWidth - width : originX;
                            y = vertical < 0 ? originY + originHeight - height : originY;
                        } else {
                            x = originX + dx;
                            y = originY + dy;
                        }
                        applyBounds();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && tap != null) tap.run();
                        // fall through
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) preferences.edit().putInt("x", x).putInt("y", y)
                                .putInt("width", width).putInt("height", height).apply();
                        return true;
                    default: return true;
                }
            }
        };
    }

    private void applyBounds() {
        Rect area = displayArea();
        width = clamp(width, dp(285), area.width());
        height = clamp(height, dp(310), area.height());
        int windowWidth = collapsed ? dp(58) : width;
        int windowHeight = collapsed ? dp(52) : height;
        x = clamp(x, 0, area.width() - windowWidth);
        y = clamp(y, 0, area.height() - windowHeight);
        Window window = getWindow();
        WindowManager.LayoutParams layout = window.getAttributes();
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.x = x;
        layout.y = y;
        layout.width = windowWidth;
        layout.height = windowHeight;
        window.setAttributes(layout);
    }

    private Rect displayArea() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = getWindowManager().getMaximumWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            android.graphics.Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            bounds.inset(bars.left, bars.top, bars.right, bars.bottom);
            return bounds;
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine();
        return view;
    }

    private GradientDrawable shape(int radius, int stroke, int... colors) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int clamp(int value, int lower, int upper) {
        return Math.min(Math.max(lower, value), Math.max(0, upper));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
