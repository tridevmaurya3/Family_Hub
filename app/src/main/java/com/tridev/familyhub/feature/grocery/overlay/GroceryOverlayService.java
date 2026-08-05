package com.tridev.familyhub.feature.grocery.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;

import java.util.ArrayList;
import java.util.List;

/** Draggable, adjustable quick-grocery surface shown over other phone screens. */
public class GroceryOverlayService extends Service {

    public static final String ACTION_STOP =
            "com.tridev.familyhub.action.STOP_GROCERY_OVERLAY";
    public static final String PREFS = "grocery_overlay";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_REQUESTED = "permission_requested";

    private static final String CHANNEL_ID = "grocery_overlay_channel";
    private static final int NOTIFICATION_ID = 4107;

    private WindowManager windowManager;
    private WindowManager.LayoutParams stripParams;
    private View stripView;
    private View panelView;
    private LinearLayout itemContainer;
    private GroceryRepository repository;
    private FamilyMemberRepository memberRepository;
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    private String visibleListType = GroceryItem.LIST_DAILY;
    private EditText voiceTarget;
    private final BroadcastReceiver voiceResultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (voiceTarget == null || intent == null) return;
            String result = intent.getStringExtra(GroceryVoiceCaptureActivity.EXTRA_RESULT);
            if (result != null && !result.trim().isEmpty()) {
                voiceTarget.setText(result.trim());
                voiceTarget.setSelection(voiceTarget.length());
            }
            voiceTarget.setHint(R.string.grocery_overlay_add_hint);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).apply();
        repository = new GroceryRepository(this);
        memberRepository = new FamilyMemberRepository(this);
        memberRepository.loadMembers("", members -> {
            familyMembers.clear();
            familyMembers.addAll(members);
        });
        repository.startRealtimeSync(this::refreshPanel);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        androidx.core.content.ContextCompat.registerReceiver(this,
                voiceResultReceiver,
                new IntentFilter(GroceryVoiceCaptureActivity.ACTION_RESULT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        if (Settings.canDrawOverlays(this)) {
            showStrip();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void showStrip() {
        if (stripView != null) {
            return;
        }
        TextView strip = new TextView(this);
        strip.setText("+");
        strip.setTextColor(Color.rgb(11, 79, 59));
        strip.setTextSize(26f);
        strip.setTypeface(strip.getTypeface(), android.graphics.Typeface.BOLD);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(0, 0, 0, dp(2));
        strip.setBackground(rounded(Color.rgb(231, 246, 240),
                Color.rgb(15, 122, 90), 22));
        strip.setElevation(dp(8));
        strip.setAlpha(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat("alpha", 0.88f));
        stripView = strip;

        stripParams = overlayParams(dp(44), dp(44), true);
        stripParams.gravity = Gravity.TOP | Gravity.START;
        stripParams.x = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("x", dp(12));
        stripParams.y = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("y", dp(160));
        strip.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
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
                    moved = moved || Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4);
                    stripParams.x = Math.max(0, startX + dx);
                    stripParams.y = Math.max(0, startY + dy);
                    windowManager.updateViewLayout(stripView, stripParams);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("x", stripParams.x)
                            .putInt("y", stripParams.y).apply();
                    if (!moved) {
                        togglePanel();
                    }
                    return true;
                }
                return false;
            }
        });
        windowManager.addView(stripView, stripParams);
    }

    private void togglePanel() {
        if (panelView != null) {
            closePanel();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackground(panelGradient());
        root.setElevation(dp(12));
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return false;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.grocery_overlay_title), 16, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(20f);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(header);

        TextView subtitle = text(getString(R.string.grocery_overlay_subtitle),
                11, false);
        subtitle.setTextColor(Color.rgb(91, 101, 114));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        final String[] selectedListType = {GroceryItem.LIST_DAILY};
        RadioGroup listTypeGroup = new RadioGroup(this);
        listTypeGroup.setOrientation(RadioGroup.HORIZONTAL);
        listTypeGroup.setGravity(Gravity.CENTER_VERTICAL);
        RadioButton daily = new RadioButton(this);
        daily.setId(View.generateViewId());
        daily.setText(R.string.grocery_list_daily);
        daily.setTextSize(12f);
        daily.setChecked(true);
        RadioButton monthly = new RadioButton(this);
        monthly.setId(View.generateViewId());
        monthly.setText(R.string.grocery_list_monthly);
        monthly.setTextSize(12f);
        listTypeGroup.addView(daily, new RadioGroup.LayoutParams(
                0, dp(42), 1f));
        listTypeGroup.addView(monthly, new RadioGroup.LayoutParams(
                0, dp(42), 1f));
        listTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            selectedListType[0] = checkedId == monthly.getId()
                    ? GroceryItem.LIST_MONTHLY
                    : GroceryItem.LIST_DAILY;
            visibleListType = selectedListType[0];
            refreshPanel();
        });
        LinearLayout typeBlock = labelledField(
                getString(R.string.grocery_list_type), listTypeGroup);
        root.addView(typeBlock);

        LinearLayout detailsOne = new LinearLayout(this);
        detailsOne.setOrientation(LinearLayout.HORIZONTAL);
        EditText quantity = compactInput(getString(R.string.grocery_quantity));
        detailsOne.addView(labelledField(
                getString(R.string.grocery_quantity), quantity), weightedField());
        Spinner category = compactSpinner(
                getResources().getStringArray(R.array.grocery_category_labels));
        LinearLayout.LayoutParams categoryParams = weightedField();
        categoryParams.setMarginStart(dp(8));
        detailsOne.addView(labelledField(
                getString(R.string.grocery_category), category), categoryParams);
        root.addView(detailsOne);

        LinearLayout detailsTwo = new LinearLayout(this);
        detailsTwo.setOrientation(LinearLayout.HORIZONTAL);
        Spinner priority = compactSpinner(
                getResources().getStringArray(R.array.grocery_priority_labels));
        detailsTwo.addView(labelledField(
                getString(R.string.grocery_priority), priority), weightedField());
        List<String> memberLabels = new ArrayList<>();
        memberLabels.add(getString(R.string.grocery_whole_family));
        for (FamilyMember member : familyMembers) {
            memberLabels.add(member.name);
        }
        Spinner member = compactSpinner(memberLabels.toArray(new String[0]));
        LinearLayout.LayoutParams memberParams = weightedField();
        memberParams.setMarginStart(dp(8));
        detailsTwo.addView(labelledField(
                getString(R.string.grocery_assign_to), member), memberParams);
        root.addView(detailsTwo);

        TextView quickLabel = text(getString(
                R.string.grocery_overlay_quick_item), 11, true);
        LinearLayout.LayoutParams quickLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        quickLabelParams.topMargin = dp(8);
        root.addView(quickLabel, quickLabelParams);

        LinearLayout quickAdd = new LinearLayout(this);
        quickAdd.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new BackAwareEditText();
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.setClickable(true);
        input.setTextSize(13f);
        input.setHint(R.string.grocery_overlay_add_hint);
        input.setPadding(dp(12), 0, dp(8), 0);
        input.setBackground(rounded(Color.rgb(248, 249, 250),
                Color.rgb(214, 220, 227), 12));
        input.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return false;
        });
        input.setOnClickListener(v -> {
            input.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        quickAdd.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageButton voice = new ImageButton(this);
        voice.setImageResource(R.drawable.ic_mic);
        voice.setContentDescription(getString(R.string.grocery_overlay_voice));
        voice.setColorFilter(Color.rgb(15, 108, 189));
        voice.setPadding(dp(11), dp(11), dp(11), dp(11));
        voice.setBackground(rounded(Color.rgb(232, 243, 252),
                Color.rgb(190, 216, 236), 22));
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(
                dp(44), dp(44));
        voiceParams.setMarginStart(dp(6));
        quickAdd.addView(voice, voiceParams);
        Button add = new Button(this);
        add.setText("＋ Add");
        add.setTextSize(12f);
        add.setTextColor(Color.WHITE);
        add.setBackground(rounded(Color.rgb(15, 108, 189),
                Color.rgb(15, 108, 189), 22));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                dp(86), dp(48));
        addParams.setMarginStart(dp(8));
        quickAdd.addView(add, addParams);
        root.addView(quickAdd);

        itemContainer = new LinearLayout(this);
        itemContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(10);
        root.addView(itemContainer, listParams);

        TextView opacityLabel = text(getString(
                R.string.grocery_overlay_opacity), 11, false);
        root.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this);
        int savedProgress = Math.round((stripView.getAlpha() - 0.35f) / 0.65f * 100f);
        opacity.setProgress(Math.max(0, Math.min(100, savedProgress)));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = 0.35f + (progress / 100f) * 0.65f;
                stripView.setAlpha(alpha);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putFloat("alpha", alpha).apply();
            }
        });
        root.addView(opacity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        voice.setOnClickListener(v -> {
            voiceTarget = input;
            input.setHint(R.string.grocery_overlay_voice_listening);
            Intent capture = new Intent(this, GroceryVoiceCaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(capture);
        });

        add.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError(getString(R.string.grocery_name_required));
                return;
            }
            GroceryItem item = new GroceryItem();
            item.name = name;
            item.listType = selectedListType[0];
            item.quantity = quantity.getText().toString().trim();
            item.category = String.valueOf(category.getSelectedItem());
            int priorityIndex = priority.getSelectedItemPosition();
            item.priority = priorityIndex == 2
                    ? GroceryItem.PRIORITY_URGENT
                    : priorityIndex == 1
                    ? GroceryItem.PRIORITY_HIGH
                    : GroceryItem.PRIORITY_NORMAL;
            int memberIndex = member.getSelectedItemPosition();
            if (memberIndex > 0 && memberIndex <= familyMembers.size()) {
                FamilyMember selected = familyMembers.get(memberIndex - 1);
                item.assignedMemberId = selected.cloudProfileId.isEmpty()
                        ? String.valueOf(selected.id) : selected.cloudProfileId;
                item.assignedMemberName = selected.name;
            }
            repository.save(item, () -> {
                input.setText("");
                quantity.setText("");
                refreshPanel();
            });
        });

        panelView = root;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams params = overlayParams(
                screenWidth - dp(24), WindowManager.LayoutParams.WRAP_CONTENT, false);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(74);
        windowManager.addView(panelView, params);
        root.requestFocus();
        refreshPanel();
        input.requestFocus();
        input.postDelayed(() -> ((InputMethodManager) getSystemService(
                INPUT_METHOD_SERVICE)).showSoftInput(input,
                InputMethodManager.SHOW_IMPLICIT), 150L);
    }

    private void refreshPanel() {
        if (repository == null || itemContainer == null) {
            return;
        }
        repository.loadItems("", items -> renderItems(items));
    }

    private void renderItems(List<GroceryItem> items) {
        if (itemContainer == null) {
            return;
        }
        itemContainer.removeAllViews();
        boolean monthly = GroceryItem.LIST_MONTHLY.equals(visibleListType);
        renderSection(items, visibleListType,
                getString(monthly ? R.string.grocery_overlay_monthly_section
                        : R.string.grocery_overlay_daily_section), 0);
    }

    private int renderSection(List<GroceryItem> items, String listType,
                              String heading, int alreadyShown) {
        int count = 0;
        for (GroceryItem item : items) {
            if (!item.isPurchased && listType.equals(item.listType)) count++;
        }
        TextView sectionTitle = text(heading + "  (" + count + ")", 12, true);
        sectionTitle.setTextColor(GroceryItem.LIST_MONTHLY.equals(listType)
                ? Color.rgb(107, 76, 154) : Color.rgb(15, 108, 89));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        titleParams.topMargin = alreadyShown == 0 ? 0 : dp(6);
        itemContainer.addView(sectionTitle, titleParams);

        int shownHere = 0;
        for (GroceryItem item : items) {
            if (item.isPurchased || !listType.equals(item.listType)
                    || shownHere >= 6) {
                continue;
            }
            CheckBox row = new CheckBox(this);
            String detail = item.name;
            if (!item.quantity.isEmpty()) detail += "  •  " + item.quantity;
            if (!item.category.isEmpty()) detail += "  •  " + item.category;
            if (!item.assignedMemberName.isEmpty()) {
                detail += "  •  " + item.assignedMemberName;
            }
            row.setText(detail);
            row.setTextSize(13f);
            row.setTextColor(Color.rgb(36, 36, 36));
            row.setMinHeight(dp(42));
            row.setPadding(dp(8), 0, dp(8), 0);
            row.setBackground(roundedFill(
                    GroceryItem.LIST_MONTHLY.equals(listType)
                            ? Color.rgb(246, 241, 252)
                            : Color.rgb(237, 249, 243), 10));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    repository.setPurchased(item, true, this::refreshPanel);
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            rowParams.bottomMargin = dp(4);
            itemContainer.addView(row, rowParams);
            shownHere++;
        }
        if (count == 0) {
            TextView empty = text(getString(
                    GroceryItem.LIST_MONTHLY.equals(listType)
                            ? R.string.grocery_overlay_monthly_empty
                            : R.string.grocery_overlay_daily_empty), 11, false);
            empty.setTextColor(Color.rgb(110, 118, 128));
            empty.setPadding(dp(8), 0, 0, 0);
            itemContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        }
        return alreadyShown + shownHere;
    }

    private void closePanel() {
        voiceTarget = null;
        if (panelView != null) {
            windowManager.removeView(panelView);
            panelView = null;
            itemContainer = null;
        }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, boolean passive) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (passive) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        return new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(36, 36, 36));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private EditText compactInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(12f);
        input.setHint(hint);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(rounded(Color.rgb(248, 249, 250),
                Color.rgb(214, 220, 227), 12));
        return input;
    }

    private Spinner compactSpinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setBackground(rounded(Color.rgb(248, 249, 250),
                Color.rgb(214, 220, 227), 12));
        return spinner;
    }

    private LinearLayout.LayoutParams weightedField() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(66), 1f);
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout labelledField(String label, View field) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView fieldLabel = text(label, 10, true);
        fieldLabel.setTextColor(Color.rgb(84, 93, 105));
        block.addView(fieldLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
        block.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        return block;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable roundedFill(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable panelGradient() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(239, 250, 243),
                        Color.rgb(255, 244, 245), Color.rgb(238, 246, 253)});
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), Color.rgb(214, 220, 227));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Captures Back before the IME so one press closes the floating panel. */
    private final class BackAwareEditText extends androidx.appcompat.widget.AppCompatEditText {
        BackAwareEditText() {
            super(GroceryOverlayService.this);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.grocery_overlay_channel),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent stop = new Intent(this, GroceryOverlayService.class);
        stop.setAction(ACTION_STOP);
        android.app.PendingIntent stopIntent = android.app.PendingIntent.getService(
                this, 0, stop,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_grocery)
                .setContentTitle(getString(R.string.grocery_overlay_title))
                .setContentText(getString(R.string.grocery_overlay_notification))
                .setOngoing(true)
                .addAction(0, getString(R.string.grocery_floating_disable), stopIntent)
                .build();
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (repository != null) {
            repository.stopRealtimeSync();
        }
        closePanel();
        if (stripView != null) {
            windowManager.removeView(stripView);
            stripView = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false).apply();
        unregisterReceiver(voiceResultReceiver);
        super.onDestroy();
    }
}
