package com.tridev.familyhub.feature.tasks.overlay;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Grocery-visual-language Family To-Do overlay.
 * This service remains Task-only: it never reads Grocery, Finance or Loan data.
 * It deliberately does not use a microphone foreground-service type; voice capture
 * remains an optional in-panel action after RECORD_AUDIO has already been granted.
 */
public final class FamilyTaskOverlayService extends Service {
    public static final String ACTION_SHOW = "com.tridev.familyhub.action.SHOW_TASK_OVERLAY";
    public static final String ACTION_HIDE = "com.tridev.familyhub.action.HIDE_TASK_OVERLAY";
    public static final String ACTION_OPEN_PANEL = "com.tridev.familyhub.action.OPEN_TASK_PANEL";
    public static final String ACTION_STOP = "com.tridev.familyhub.action.STOP_TASK_OVERLAY";
    public static final String PREFS = "family_task_overlay";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_REQUESTED = "permission_requested";

    private static final String CHANNEL = "family_task_overlay";
    private static final int NOTIFICATION_ID = 4217;
    private static final int SORT_DUE = 0;
    private static final int SORT_PRIORITY = 1;
    private static final int DATE_TODAY = 0;
    private static final int DATE_ADJACENT_DAY = 1;
    private static final int DATE_SEVEN_DAYS = 2;
    private static final int DATE_FIFTEEN_DAYS = 3;
    private static final int DATE_THIRTY_DAYS = 4;
    private static final int DATE_ALL = 5;

    private WindowManager windowManager;
    private WindowManager.LayoutParams stripParams;
    @Nullable private WindowManager.LayoutParams panelParams;
    @Nullable private View stripView;
    @Nullable private View panelView;
    @Nullable private LinearLayout taskRows;
    @Nullable private TextView countText;
    @Nullable private TextView liveStatus;
    @Nullable private TextView voiceStatus;
    @Nullable private android.speech.SpeechRecognizer speechRecognizer;
    @Nullable private PopupWindow activePopup;
    private FamilyTaskRepository repository;
    private boolean completedMode;
    private int dateMode = DATE_TODAY;
    private int sortMode = SORT_DUE;
    @NonNull private String searchQuery = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply();
        repository = new FamilyTaskRepository(this);
        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull FamilyTask task) { refresh(); }
            @Override public void onRemoved(long localId) { refresh(); }
        });
        windowManager = getSystemService(WindowManager.class);
        if (Settings.canDrawOverlays(this)) showStrip();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE.equals(action)) {
            closePanel();
            if (stripView != null) stripView.setVisibility(View.GONE);
            return START_STICKY;
        }
        if (ACTION_OPEN_PANEL.equals(action)) {
            if (stripView == null && Settings.canDrawOverlays(this)) showStrip();
            if (stripView != null) stripView.setVisibility(View.GONE);
            if (panelView == null) showPanel();
            return START_STICKY;
        }
        if (stripView == null && Settings.canDrawOverlays(this)) showStrip();
        if (stripView != null) stripView.setVisibility(View.VISIBLE);
        return START_STICKY;
    }

    private void showStrip() {
        if (stripView != null || windowManager == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        TextView strip = text("✓", 20f, true);
        strip.setTextColor(Color.rgb(15, 104, 80));
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(0, 0, 0, dp(1));
        strip.setContentDescription(getString(R.string.family_tasks_title));
        strip.setBackground(round(Color.argb(238, 231, 246, 240),
                22, Color.argb(210, 15, 122, 90)));
        strip.setElevation(dp(10));
        strip.setAlpha(prefs.getFloat("alpha", 0.88f));

        stripParams = params(dp(44), dp(44));
        stripParams.gravity = Gravity.TOP | Gravity.START;
        stripParams.x = prefs.getInt("x", dp(12));
        stripParams.y = prefs.getInt("y", dp(160));
        stripView = strip;
        strip.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = stripParams.x;
                    startY = stripParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    moved |= Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4);
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    stripParams.x = clamp(startX + dx, 0, Math.max(0, screenWidth - dp(44)));
                    stripParams.y = clamp(startY + dy, 0, Math.max(0, screenHeight - dp(44)));
                    safeUpdate(stripView, stripParams);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    prefs.edit().putInt("x", stripParams.x).putInt("y", stripParams.y).apply();
                    if (!moved) togglePanel();
                    return true;
                }
                return false;
            }
        });
        windowManager.addView(stripView, stripParams);
    }

    private void togglePanel() {
        if (panelView == null) showPanel();
        else closePanel();
    }

    private void showPanel() {
        if (windowManager == null || panelView != null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxWidth = Math.max(dp(280), screenWidth - dp(24));
        int maxHeight = Math.max(dp(360), screenHeight - dp(72));
        int defaultWidth = Math.min(maxWidth, Math.max(dp(310), Math.round(screenWidth * 0.94f)));
        int defaultHeight = Math.min(maxHeight, Math.max(dp(410), Math.round(screenHeight * 0.70f)));
        int panelWidth = clamp(prefs.getInt("panel_w", defaultWidth), dp(280), maxWidth);
        int panelHeight = clamp(prefs.getInt("panel_h", defaultHeight), dp(360), maxHeight);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackground(panelGradient());
        shell.setElevation(dp(16));
        shell.setAlpha(prefs.getFloat("alpha", 0.88f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View highlight = new View(this);
        highlight.setBackground(glassTopHighlight());
        FrameLayout.LayoutParams highlightParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(8), Gravity.TOP);
        highlightParams.leftMargin = dp(18);
        highlightParams.rightMargin = dp(18);
        highlightParams.topMargin = dp(4);
        shell.addView(highlight, highlightParams);

        LinearLayout header = row();
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.family_tasks_title), 15f, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(Color.rgb(31, 52, 46));
        titleStack.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));
        liveStatus = text(getString(R.string.family_tasks_overlay_connecting), 9f, true);
        liveStatus.setTextColor(Color.rgb(84, 93, 105));
        titleStack.addView(liveStatus, new LinearLayout.LayoutParams(-1, dp(18)));
        header.addView(titleStack, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button day = headerChip(dayLabel() + "  ▾");
        day.setOnClickListener(v -> showDayPopup(day));
        header.addView(day, headerChipParams(76));

        Button close = new Button(this);
        close.setText("×");
        close.setTextColor(Color.rgb(36, 63, 56));
        close.setTextSize(20f);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("Close To-Do overlay");
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(46)));
        attachPanelDrag(titleStack);

        LinearLayout searchTools = row();
        LinearLayout searchBox = row();
        searchBox.setPadding(dp(4), 0, dp(3), 0);
        searchBox.setBackground(glassFieldBackground());
        searchBox.setElevation(dp(1));
        EditText search = compactInput(getString(R.string.family_tasks_overlay_search));
        search.setText(searchQuery);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(10), 0, dp(6), 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button clear = compactAction("×", Color.rgb(73, 86, 98),
                Color.argb(218, 242, 246, 248));
        clear.setTextSize(16f);
        clear.setContentDescription(getString(R.string.family_tasks_overlay_clear));
        clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(34), dp(34)));
        searchTools.addView(searchBox, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button sort = compactAction(sortLabel() + "  ▾", Color.rgb(15, 108, 189),
                Color.argb(220, 232, 243, 252));
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(dp(88), dp(38));
        sortParams.setMarginStart(dp(6));
        searchTools.addView(sort, sortParams);
        root.addView(searchTools, new LinearLayout.LayoutParams(-1, dp(44)));

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                searchQuery = s == null ? "" : s.toString().trim();
                clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                refresh();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        clear.setOnClickListener(v -> search.setText(""));
        sort.setOnClickListener(v -> showSortPopup(sort));

        LinearLayout quick = row();
        LinearLayout quickField = row();
        quickField.setBackground(glassFieldBackground());
        EditText input = compactInput(getString(R.string.family_tasks_overlay_add_hint));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12), 0, dp(8), 0);
        ImageButton voice = new ImageButton(this);
        voice.setImageResource(R.drawable.ic_mic);
        voice.setContentDescription(getString(R.string.family_tasks_voice_add));
        voice.setColorFilter(Color.rgb(15, 105, 80));
        voice.setPadding(dp(10), dp(10), dp(10), dp(10));
        voice.setBackground(round(Color.argb(225, 237, 248, 244),
                14, Color.argb(180, 138, 194, 176)));
        Button add = compactAction("+ " + getString(R.string.family_tasks_add),
                Color.WHITE, Color.rgb(15, 108, 89));
        add.setBackground(round(Color.rgb(15, 108, 89), 14, Color.rgb(15, 108, 89)));
        quickField.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1f));
        quickField.addView(voice, new LinearLayout.LayoutParams(dp(42), dp(42)));
        quick.addView(quickField, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(104), dp(42));
        addParams.setMarginStart(dp(5));
        quick.addView(add, addParams);
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, dp(48));
        quickParams.topMargin = dp(2);
        root.addView(quick, quickParams);

        voiceStatus = text("", 10f, true);
        voiceStatus.setTextColor(Color.rgb(15, 105, 80));
        voiceStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        voiceStatus.setPadding(dp(6), 0, dp(6), 0);
        voiceStatus.setVisibility(View.GONE);
        root.addView(voiceStatus, new LinearLayout.LayoutParams(-1, dp(22)));

        countText = text("", 10f, true);
        countText.setTextColor(Color.rgb(84, 93, 105));
        countText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        countText.setPadding(dp(6), 0, dp(6), 0);
        root.addView(countText, new LinearLayout.LayoutParams(-1, dp(20)));

        taskRows = new LinearLayout(this);
        taskRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(taskRows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(2);
        root.addView(scroll, scrollParams);

        View.OnClickListener save = v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) return;
            FamilyTask task = new FamilyTask();
            task.title = value;
            task.dueAt = dueAt(!completedMode && dateMode == DATE_ADJACENT_DAY);
            repository.save(task, () -> {
                input.setText("");
                refresh();
            });
        };
        add.setOnClickListener(save);
        voice.setOnClickListener(v -> startVoiceCapture(input, voice));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save.onClick(v);
                return true;
            }
            return false;
        });

        TextView resize = text("↘", 17f, true);
        resize.setGravity(Gravity.CENTER);
        resize.setTextColor(Color.argb(135, 15, 105, 80));
        resize.setContentDescription("Resize To-Do panel");
        FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(
                dp(34), dp(34), Gravity.END | Gravity.BOTTOM);
        resizeParams.rightMargin = dp(2);
        resizeParams.bottomMargin = dp(2);
        shell.addView(resize, resizeParams);
        attachPanelResize(resize);

        panelView = shell;
        panelParams = params(panelWidth, panelHeight);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = clamp(prefs.getInt("panel_x", Math.max(0, (screenWidth - panelWidth) / 2)),
                0, Math.max(0, screenWidth - panelWidth));
        panelParams.y = clamp(prefs.getInt("panel_y", dp(74)),
                0, Math.max(0, screenHeight - panelHeight));
        windowManager.addView(panelView, panelParams);
        refresh();
    }

    private void attachPanelDrag(@NonNull View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (panelParams == null || panelView == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = panelParams.x;
                    startY = panelParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    panelParams.x = clamp(startX + Math.round(event.getRawX() - downX),
                            0, Math.max(0, screenWidth - panelParams.width));
                    panelParams.y = clamp(startY + Math.round(event.getRawY() - downY),
                            0, Math.max(0, screenHeight - panelParams.height));
                    safeUpdate(panelView, panelParams);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    persistPanelGeometry();
                    return true;
                }
                return false;
            }
        });
    }

    private void attachPanelResize(@NonNull View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startWidth, startHeight;
            float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (panelParams == null || panelView == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startWidth = panelParams.width;
                    startHeight = panelParams.height;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    int maxWidth = Math.max(dp(280), screenWidth - panelParams.x - dp(8));
                    int maxHeight = Math.max(dp(360), screenHeight - panelParams.y - dp(8));
                    panelParams.width = clamp(startWidth + Math.round(event.getRawX() - downX),
                            dp(280), maxWidth);
                    panelParams.height = clamp(startHeight + Math.round(event.getRawY() - downY),
                            dp(360), maxHeight);
                    safeUpdate(panelView, panelParams);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    persistPanelGeometry();
                    return true;
                }
                return false;
            }
        });
    }

    private void persistPanelGeometry() {
        if (panelParams == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("panel_x", panelParams.x)
                .putInt("panel_y", panelParams.y)
                .putInt("panel_w", panelParams.width)
                .putInt("panel_h", panelParams.height)
                .apply();
    }

    private void showDayPopup(@NonNull Button anchor) {
        String[] labels = completedMode
                ? new String[]{getString(R.string.family_tasks_overlay_today),
                getString(R.string.family_tasks_overlay_yesterday),
                getString(R.string.family_tasks_overlay_last_7_days),
                getString(R.string.family_tasks_overlay_last_15_days),
                getString(R.string.family_tasks_overlay_last_30_days),
                getString(R.string.family_tasks_overlay_all)}
                : new String[]{getString(R.string.family_tasks_overlay_today),
                getString(R.string.family_tasks_overlay_tomorrow),
                getString(R.string.family_tasks_overlay_next_7_days),
                getString(R.string.family_tasks_overlay_next_15_days),
                getString(R.string.family_tasks_overlay_next_30_days),
                getString(R.string.family_tasks_overlay_all)};
        showChoicePopup(anchor, labels, dateMode, Color.rgb(15, 108, 89), index -> {
            dateMode = index;
            anchor.setText(dayLabel() + "  ▾");
            refresh();
        });
    }

    private void showStatusPopup(@NonNull Button anchor, @NonNull Button dayAnchor) {
        String[] labels = {getString(R.string.family_tasks_overlay_pending),
                getString(R.string.family_tasks_overlay_completed)};
        showChoicePopup(anchor, labels, completedMode ? 1 : 0,
                Color.rgb(15, 108, 89), index -> {
                    completedMode = index == 1;
                    dateMode = DATE_TODAY;
                    anchor.setText(statusLabel() + "  ▾");
                    dayAnchor.setText(dayLabel() + "  ▾");
                    refresh();
                });
    }

    private void showSortPopup(@NonNull Button anchor) {
        String[] labels = {getString(R.string.family_tasks_sort_due),
                getString(R.string.family_tasks_sort_priority)};
        showChoicePopup(anchor, labels, sortMode, Color.rgb(15, 108, 189), index -> {
            sortMode = index;
            anchor.setText(sortLabel() + "  ▾");
            refresh();
        });
    }

    private interface ChoiceListener { void onChoice(int index); }

    private void showChoicePopup(@NonNull View anchor, @NonNull String[] labels,
                                 int selectedIndex, int accentColor,
                                 @NonNull ChoiceListener listener) {
        dismissPopup();
        LinearLayout root = popupSurface();
        int widest = Math.max(anchor.getWidth(), dp(132));
        TextView measure = text("", 12.5f, false);
        for (String label : labels) {
            widest = Math.max(widest,
                    Math.round(measure.getPaint().measureText("✓  " + label)) + dp(36));
        }
        int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(28);
        PopupWindow popup = new PopupWindow(root, Math.min(widest, maxWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        for (int i = 0; i < labels.length; i++) {
            final int choice = i;
            boolean selected = i == selectedIndex;
            TextView row = text((selected ? "✓  " : "   ") + labels[i], 12.5f, selected);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), 0, dp(10), 0);
            row.setTextColor(selected ? accentColor : Color.rgb(31, 42, 49));
            row.setBackground(popupRowBackground(selected, accentColor));
            row.setOnClickListener(v -> {
                listener.onChoice(choice);
                popup.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
            if (i < labels.length - 1) lp.bottomMargin = dp(4);
            root.addView(row, lp);
        }
        activePopup = popup;
        popup.setOnDismissListener(() -> {
            if (activePopup == popup) activePopup = null;
        });
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void showOpacityPopup(@NonNull Button anchor) {
        dismissPopup();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float saved = prefs.getFloat("alpha", 0.88f);
        LinearLayout root = popupSurface();
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout titleRow = row();
        TextView label = text(getString(R.string.family_tasks_overlay_opacity), 12f, true);
        label.setTextColor(Color.rgb(31, 42, 49));
        TextView value = text(Math.round(saved * 100f) + "%", 10f, true);
        value.setTextColor(Color.rgb(15, 108, 89));
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        titleRow.addView(label, new LinearLayout.LayoutParams(0, dp(28), 1f));
        titleRow.addView(value, new LinearLayout.LayoutParams(dp(52), dp(28)));
        root.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(30)));
        SeekBar opacity = new SeekBar(this);
        int progress = Math.round((saved - 0.35f) / 0.65f * 100f);
        opacity.setProgress(clamp(progress, 0, 100));
        root.addView(opacity, new LinearLayout.LayoutParams(-1, dp(42)));
        PopupWindow popup = new PopupWindow(root, Math.min(dp(224),
                getResources().getDisplayMetrics().widthPixels - dp(28)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                float alpha = 0.35f + (p / 100f) * 0.65f;
                value.setText(Math.round(alpha * 100f) + "%");
                if (stripView != null) stripView.setAlpha(alpha);
                if (panelView != null) panelView.setAlpha(alpha);
                prefs.edit().putFloat("alpha", alpha).apply();
            }
        });
        activePopup = popup;
        popup.setOnDismissListener(() -> {
            if (activePopup == popup) activePopup = null;
        });
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    @NonNull
    private LinearLayout popupSurface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(253, 255, 255, 255), Color.argb(249, 243, 249, 252)});
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), Color.argb(210, 199, 211, 221));
        root.setBackground(bg);
        root.setElevation(dp(10));
        return root;
    }

    @NonNull
    private GradientDrawable popupRowBackground(boolean selected, int accent) {
        int fill = selected
                ? Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(248, 255, 255, 255);
        int stroke = selected
                ? Color.argb(135, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(125, 212, 222, 226);
        return round(fill, 10, stroke);
    }

    private void dismissPopup() {
        PopupWindow popup = activePopup;
        activePopup = null;
        if (popup != null && popup.isShowing()) popup.dismiss();
    }

    private void refresh() {
        if (taskRows == null) return;
        repository.loadAll("", tasks -> {
            if (taskRows == null) return;
            taskRows.removeAllViews();
            completedMode = false;
            long[] range = selectedRange();
            List<FamilyTask> visible = new ArrayList<>();
            String query = searchQuery.toLowerCase(Locale.getDefault());
            for (FamilyTask task : tasks) {
                boolean isCompleted = FamilyTask.STATUS_COMPLETED.equals(task.status);
                if (isCompleted != completedMode) continue;
                long eventAt = completedMode
                        ? (task.completedAt > 0L ? task.completedAt : task.updatedAt)
                        : task.dueAt;
                if (dateMode != DATE_ALL
                        && (eventAt < range[0] || eventAt >= range[1])) continue;
                if (!query.isEmpty()) {
                    String haystack = (task.title + " " + task.notes + " "
                            + task.assignedMemberName).toLowerCase(Locale.getDefault());
                    if (!haystack.contains(query)) continue;
                }
                visible.add(task);
            }
            sortTasks(visible);
            if (countText != null) {
                countText.setText(getString(R.string.family_tasks_overlay_results, visible.size())
                        + " • " + statusLabel() + " • " + dayLabel()
                        + " • " + sortLabel());
            }
            if (liveStatus != null) {
                liveStatus.setText(getString(R.string.family_tasks_overlay_live)
                        + " • " + statusLabel() + " • " + dayLabel());
                liveStatus.setTextColor(Color.rgb(15, 108, 89));
            }
            if (visible.isEmpty()) {
                addEmptyCard();
                return;
            }
            for (FamilyTask task : visible) addTaskRow(task);
        });
    }

    private void sortTasks(@NonNull List<FamilyTask> tasks) {
        if (sortMode == SORT_PRIORITY) {
            Collections.sort(tasks, Comparator
                    .comparingInt(this::priorityRank)
                    .thenComparingLong(task -> task.dueAt));
        } else {
            Collections.sort(tasks, Comparator.comparingLong((FamilyTask task) -> task.dueAt));
        }
    }

    private int priorityRank(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return 0;
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return 1;
        return 2;
    }

    private void addEmptyCard() {
        if (taskRows == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(Color.argb(238, 248, 251, 252),
                14, Color.argb(190, 205, 218, 224)));
        TextView title = text(getString(R.string.family_tasks_overlay_no_results), 12f, true);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.rgb(73, 86, 98));
        card.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));
        TextView hint = text(getString(R.string.family_tasks_overlay_no_results_hint), 10f, false);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(Color.rgb(103, 113, 122));
        card.addView(hint, new LinearLayout.LayoutParams(-1, dp(34)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(82));
        lp.topMargin = dp(6);
        taskRows.addView(card, lp);
    }

    private void addTaskRow(@NonNull FamilyTask task) {
        if (taskRows == null) return;
        LinearLayout card = row();
        card.setPadding(dp(8), dp(7), dp(8), dp(7));
        card.setBackground(round(Color.argb(235, 231, 246, 240),
                14, Color.argb(160, 184, 220, 207)));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, dp(3), 0, dp(4));

        CheckBox check = new CheckBox(this);
        check.setChecked(completedMode);
        check.setContentDescription((completedMode ? "Reopen " : "Complete ") + task.title);
        card.addView(check, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(task.title, 13.5f, true);
        title.setTextColor(Color.rgb(31, 48, 43));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String detail = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(task.dueAt));
        detail += task.assignedMemberName.isEmpty()
                ? " • " + getString(R.string.family_tasks_whole_family)
                : " • " + task.assignedMemberName;
        TextView meta = text(detail, 10.5f, false);
        meta.setTextColor(Color.rgb(69, 112, 99));
        copy.addView(meta, new LinearLayout.LayoutParams(-1, dp(22)));
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView priority = text(priorityLabel(task), 9f, true);
        priority.setGravity(Gravity.CENTER);
        priority.setSingleLine(true);
        priority.setTextColor(priorityTextColor(task));
        priority.setBackground(round(priorityFill(task), 11, priorityStroke(task)));
        LinearLayout.LayoutParams priorityParams = new LinearLayout.LayoutParams(dp(58), dp(28));
        priorityParams.setMarginStart(dp(4));
        card.addView(priority, priorityParams);

        check.setOnCheckedChangeListener((button, checked) -> {
            repository.setCompleted(task, checked, () -> {
                if (checked) FamilyTaskScheduler.cancel(this, task.id);
                repository.loadAll("", all -> {
                    for (FamilyTask pending : all) {
                        if (pending.reminderEnabled
                                && FamilyTask.STATUS_PENDING.equals(pending.status)) {
                            FamilyTaskScheduler.schedule(this, pending);
                        }
                    }
                    refresh();
                });
            });
        });
        taskRows.addView(card, cardParams);
    }

    @NonNull
    private String priorityLabel(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return getString(R.string.task_priority_urgent);
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return getString(R.string.task_priority_high);
        return getString(R.string.task_priority_normal);
    }

    private int priorityTextColor(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return Color.rgb(164, 43, 62);
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return Color.rgb(147, 91, 13);
        return Color.rgb(15, 108, 89);
    }

    private int priorityFill(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return Color.argb(225, 255, 235, 239);
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return Color.argb(225, 255, 246, 224);
        return Color.argb(225, 232, 247, 241);
    }

    private int priorityStroke(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return Color.argb(180, 220, 118, 136);
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return Color.argb(180, 224, 177, 105);
        return Color.argb(170, 166, 207, 191);
    }

    private void startVoiceCapture(@NonNull EditText input, @NonNull ImageButton voice) {
        if (speechRecognizer != null) {
            stopVoiceCapture();
            voice.setColorFilter(Color.rgb(15, 105, 80));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showVoiceStatus(R.string.family_tasks_voice_permission, true);
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus(R.string.family_tasks_voice_unavailable, true);
            return;
        }
        voice.setColorFilter(Color.rgb(220, 45, 82));
        showVoiceStatus(R.string.family_tasks_voice_listening, false);
        try {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    showVoiceStatus(R.string.family_tasks_voice_processing, false);
                }
                @Override public void onError(int error) {
                    stopVoiceCapture();
                    voice.setColorFilter(Color.rgb(15, 105, 80));
                    showVoiceStatus(R.string.family_tasks_voice_no_match, true);
                }
                @Override public void onResults(android.os.Bundle results) {
                    boolean added = applyVoiceResult(results, input);
                    stopVoiceCapture();
                    voice.setColorFilter(Color.rgb(15, 105, 80));
                    showVoiceStatus(added ? R.string.family_tasks_voice_added
                            : R.string.family_tasks_voice_no_match, true);
                }
                @Override public void onPartialResults(android.os.Bundle results) {
                    applyVoiceResult(results, input);
                }
                @Override public void onEvent(int eventType, android.os.Bundle params) { }
            });
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                            Locale.getDefault().toLanguageTag())
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechRecognizer.startListening(intent);
        } catch (RuntimeException error) {
            stopVoiceCapture();
            voice.setColorFilter(Color.rgb(15, 105, 80));
            showVoiceStatus(R.string.family_tasks_voice_unavailable, true);
        }
    }

    private boolean applyVoiceResult(@Nullable android.os.Bundle results,
                                     @NonNull EditText input) {
        if (results == null) return false;
        ArrayList<String> matches = results.getStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null
                || matches.get(0).trim().isEmpty()) return false;
        input.setText(matches.get(0).trim());
        input.setSelection(input.length());
        return true;
    }

    private void showVoiceStatus(int message, boolean hide) {
        if (voiceStatus == null) return;
        voiceStatus.setText(message);
        voiceStatus.setVisibility(View.VISIBLE);
        if (hide) voiceStatus.postDelayed(() -> {
            if (voiceStatus != null) voiceStatus.setVisibility(View.GONE);
        }, 2600L);
    }

    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void closePanel() {
        stopVoiceCapture();
        dismissPopup();
        if (panelView != null && windowManager != null) {
            try { windowManager.removeView(panelView); } catch (RuntimeException ignored) { }
        }
        panelView = null;
        panelParams = null;
        taskRows = null;
        countText = null;
        liveStatus = null;
        voiceStatus = null;
    }

    @NonNull
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    @NonNull
    private EditText compactInput(@NonNull String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(12.5f);
        input.setHint(hint);
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setIncludeFontPadding(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(10), 0, dp(8), 0);
        input.setBackgroundColor(Color.TRANSPARENT);
        return input;
    }

    @NonNull
    private Button headerChip(@NonNull String value) {
        Button button = compactAction(value, Color.rgb(15, 105, 80),
                Color.argb(220, 237, 248, 244));
        button.setTextSize(8.5f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams headerChipParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(34));
        params.setMarginStart(dp(3));
        return params;
    }

    @NonNull
    private Button compactAction(@NonNull String value, int textColor, int fillColor) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(10f);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(round(fillColor, 14, Color.argb(150, 176, 207, 197)));
        return button;
    }

    @NonNull
    private TextView text(@NonNull String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    @NonNull
    private GradientDrawable panelGradient() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(250, 252, 255, 253),
                        Color.argb(247, 241, 249, 246),
                        Color.argb(250, 252, 253, 255)});
        drawable.setCornerRadius(dp(24));
        drawable.setStroke(dp(1), Color.argb(205, 126, 190, 165));
        return drawable;
    }

    @NonNull
    private GradientDrawable glassTopHighlight() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, Color.argb(155, 255, 255, 255), Color.TRANSPARENT});
        drawable.setCornerRadius(dp(5));
        return drawable;
    }

    @NonNull
    private GradientDrawable glassFieldBackground() {
        return round(Color.argb(238, 255, 255, 255),
                15, Color.argb(165, 184, 207, 199));
    }

    @NonNull
    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    @NonNull
    private WindowManager.LayoutParams params(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return params;
    }

    private void safeUpdate(@Nullable View view, @Nullable WindowManager.LayoutParams params) {
        if (view == null || params == null || windowManager == null) return;
        try { windowManager.updateViewLayout(view, params); } catch (RuntimeException ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @NonNull
    private String dayLabel() {
        if (dateMode == DATE_ALL) return getString(R.string.family_tasks_overlay_all);
        if (dateMode == DATE_TODAY) return getString(R.string.family_tasks_overlay_today);
        if (dateMode == DATE_ADJACENT_DAY) return getString(completedMode
                ? R.string.family_tasks_overlay_yesterday
                : R.string.family_tasks_overlay_tomorrow);
        if (dateMode == DATE_SEVEN_DAYS) return getString(completedMode
                ? R.string.family_tasks_overlay_last_7_days
                : R.string.family_tasks_overlay_next_7_days);
        if (dateMode == DATE_FIFTEEN_DAYS) return getString(completedMode
                ? R.string.family_tasks_overlay_last_15_days
                : R.string.family_tasks_overlay_next_15_days);
        return getString(completedMode ? R.string.family_tasks_overlay_last_30_days
                : R.string.family_tasks_overlay_next_30_days);
    }

    @NonNull
    private String statusLabel() {
        return getString(completedMode ? R.string.family_tasks_overlay_completed
                : R.string.family_tasks_overlay_pending);
    }

    @NonNull
    private String sortLabel() {
        return getString(sortMode == SORT_PRIORITY
                ? R.string.family_tasks_sort_priority : R.string.family_tasks_sort_due);
    }

    private long dueAt(boolean next) {
        Calendar calendar = Calendar.getInstance();
        if (next) calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 18);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long[] selectedRange() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (dateMode == DATE_ALL) return new long[]{Long.MIN_VALUE, Long.MAX_VALUE};
        if (dateMode == DATE_ADJACENT_DAY) {
            calendar.add(Calendar.DAY_OF_YEAR, completedMode ? -1 : 1);
        } else if (completedMode && dateMode >= DATE_SEVEN_DAYS) {
            calendar.add(Calendar.DAY_OF_YEAR, -(daysInMode() - 1));
        }
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_YEAR,
                dateMode >= DATE_SEVEN_DAYS ? daysInMode() : 1);
        return new long[]{start, calendar.getTimeInMillis()};
    }

    private int daysInMode() {
        if (dateMode == DATE_SEVEN_DAYS) return 7;
        if (dateMode == DATE_FIFTEEN_DAYS) return 15;
        if (dateMode == DATE_THIRTY_DAYS) return 30;
        return 1;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.family_tasks_overlay_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @NonNull
    private Notification notification() {
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TASKS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_family_task)
                .setContentTitle(getString(R.string.family_tasks_title))
                .setContentText(getString(R.string.family_tasks_overlay_notification))
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        closePanel();
        if (stripView != null && windowManager != null) {
            try { windowManager.removeView(stripView); } catch (RuntimeException ignored) { }
            stripView = null;
        }
        if (repository != null) repository.stopRealtimeSync();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
        super.onDestroy();
    }
}
