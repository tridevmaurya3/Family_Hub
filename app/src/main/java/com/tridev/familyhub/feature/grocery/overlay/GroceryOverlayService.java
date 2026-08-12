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
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Draggable, adjustable quick-grocery surface shown over other phone screens. */
public class GroceryOverlayService extends Service {

    public static final String ACTION_STOP =
            "com.tridev.familyhub.action.STOP_GROCERY_OVERLAY";
    public static final String ACTION_HIDE =
            "com.tridev.familyhub.action.HIDE_GROCERY_OVERLAY";
    public static final String ACTION_SHOW =
            "com.tridev.familyhub.action.SHOW_GROCERY_OVERLAY";
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
    private LinearLayout overlayFormDetails;
    private ScrollView overlayItemScroll;
    private Button overlayFormToggle;
    private boolean overlayFormCollapsed;
    private int overlayListCompactHeight;
    private int overlayListExpandedHeight;
    private GroceryRepository repository;
    private FamilyMemberRepository memberRepository;
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    private String visibleListType = GroceryItem.LIST_DAILY;
    private String pendingVoiceText = "";
    private String overlaySearchQuery = "";
    private String overlayCategoryFilter = "";
    private boolean collapseAllCategories;
    private final Set<String> collapsedCategories = new HashSet<>();
    private final BroadcastReceiver voiceResultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String result = intent.getStringExtra(GroceryVoiceCaptureActivity.EXTRA_RESULT);
            if (result != null && !result.trim().isEmpty()) {
                pendingVoiceText = result.trim();
            }
            if (panelView == null) togglePanel();
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
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            closePanel();
            if (stripView != null) {
                stripView.setVisibility(View.GONE);
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            if (stripView == null && Settings.canDrawOverlays(this)) {
                showStrip();
            }
            if (stripView != null) {
                stripView.setVisibility(View.VISIBLE);
            }
            return START_STICKY;
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
        overlayFormToggle = new Button(this);
        overlayFormToggle.setText(R.string.grocery_overlay_hide_form);
        overlayFormToggle.setTextSize(10f);
        overlayFormToggle.setTextColor(Color.rgb(15, 108, 189));
        overlayFormToggle.setAllCaps(false);
        overlayFormToggle.setMinWidth(0);
        overlayFormToggle.setMinimumWidth(0);
        overlayFormToggle.setPadding(dp(6), 0, dp(6), 0);
        overlayFormToggle.setBackground(rounded(Color.rgb(232, 243, 252),
                Color.rgb(190, 216, 236), 16));
        header.addView(overlayFormToggle,
                new LinearLayout.LayoutParams(dp(78), dp(34)));
        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(20f);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(header);

        overlayFormDetails = new LinearLayout(this);
        overlayFormDetails.setOrientation(LinearLayout.VERTICAL);

        TextView subtitle = text(getString(R.string.grocery_overlay_subtitle),
                11, false);
        subtitle.setTextColor(Color.rgb(91, 101, 114));
        overlayFormDetails.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        final String[] selectedListType = {visibleListType};
        RadioGroup listTypeGroup = new RadioGroup(this);
        listTypeGroup.setOrientation(RadioGroup.HORIZONTAL);
        listTypeGroup.setGravity(Gravity.CENTER_VERTICAL);
        RadioButton daily = new RadioButton(this);
        daily.setId(View.generateViewId());
        daily.setText(R.string.grocery_list_daily);
        daily.setTextSize(12f);
        daily.setChecked(GroceryItem.LIST_DAILY.equals(visibleListType));
        RadioButton monthly = new RadioButton(this);
        monthly.setId(View.generateViewId());
        monthly.setText(R.string.grocery_list_monthly);
        monthly.setTextSize(12f);
        monthly.setChecked(GroceryItem.LIST_MONTHLY.equals(visibleListType));
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
        overlayFormDetails.addView(typeBlock);

        LinearLayout detailsOne = new LinearLayout(this);
        detailsOne.setOrientation(LinearLayout.HORIZONTAL);
        EditText quantity = compactInput(getString(R.string.grocery_quantity_amount_hint));
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        detailsOne.addView(labelledField(
                getString(R.string.grocery_quantity_amount), quantity), weightedField());
        Spinner quantityUnit = compactSpinner(
                getResources().getStringArray(R.array.grocery_quantity_units));
        LinearLayout.LayoutParams unitParams = weightedField();
        unitParams.setMarginStart(dp(8));
        detailsOne.addView(labelledField(
                getString(R.string.grocery_quantity_unit), quantityUnit), unitParams);
        overlayFormDetails.addView(detailsOne);

        LinearLayout detailsTwo = new LinearLayout(this);
        detailsTwo.setOrientation(LinearLayout.HORIZONTAL);
        Spinner category = compactSpinner(
                getResources().getStringArray(R.array.grocery_category_labels));
        detailsTwo.addView(labelledField(
                getString(R.string.grocery_category), category), weightedField());
        EditText price = compactInput(getString(R.string.grocery_overlay_price_hint));
        price.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams priceParams = weightedField();
        priceParams.setMarginStart(dp(8));
        detailsTwo.addView(labelledField(
                getString(R.string.grocery_overlay_price), price), priceParams);
        overlayFormDetails.addView(detailsTwo);

        LinearLayout detailsThree = new LinearLayout(this);
        detailsThree.setOrientation(LinearLayout.HORIZONTAL);
        Spinner priority = compactSpinner(
                getResources().getStringArray(R.array.grocery_priority_labels));
        detailsThree.addView(labelledField(
                getString(R.string.grocery_priority), priority), weightedField());
        List<String> memberLabels = new ArrayList<>();
        memberLabels.add(getString(R.string.grocery_whole_family));
        for (FamilyMember member : familyMembers) {
            memberLabels.add(member.name);
        }
        Spinner member = compactSpinner(memberLabels.toArray(new String[0]));
        LinearLayout.LayoutParams memberParams = weightedField();
        memberParams.setMarginStart(dp(8));
        detailsThree.addView(labelledField(
                getString(R.string.grocery_assign_to), member), memberParams);
        overlayFormDetails.addView(detailsThree);
        root.addView(overlayFormDetails);

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
        if (!pendingVoiceText.isEmpty()) {
            input.setText(pendingVoiceText);
            input.setSelection(input.length());
            pendingVoiceText = "";
        }
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

        LinearLayout listTools = new LinearLayout(this);
        listTools.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(rounded(Color.rgb(248, 249, 250),
                Color.rgb(214, 220, 227), 12));
        EditText search = compactInput(getString(R.string.grocery_search_hint));
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setText(overlaySearchQuery);
        search.setHint(overlayCategoryFilter.isEmpty()
                ? getString(R.string.grocery_search_hint)
                : getString(R.string.grocery_search_hint) + " • " + overlayCategoryFilter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                overlaySearchQuery = s == null ? "" : s.toString().trim();
                refreshPanel();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1f));

        ImageButton categoryFilter = new ImageButton(this);
        categoryFilter.setImageResource(R.drawable.ic_filter);
        categoryFilter.setContentDescription(
                getString(R.string.grocery_filter_by_category));
        categoryFilter.setColorFilter(overlayCategoryFilter.isEmpty()
                ? Color.rgb(15, 108, 189) : Color.rgb(15, 108, 89));
        categoryFilter.setPadding(dp(10), dp(10), dp(10), dp(10));
        categoryFilter.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(categoryFilter,
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        listTools.addView(searchBox, new LinearLayout.LayoutParams(
                0, dp(42), 1f));

        Button collapse = compactAction(getString(R.string.grocery_collapse_all),
                Color.rgb(15, 108, 89), Color.rgb(226, 244, 238));
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(
                dp(94), dp(38));
        collapseParams.setMarginStart(dp(6));
        listTools.addView(collapse, collapseParams);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        toolsParams.topMargin = dp(6);
        root.addView(listTools, toolsParams);

        String[] categoryLabels = getResources().getStringArray(
                R.array.grocery_category_labels);
        categoryFilter.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, categoryFilter);
            popup.getMenu().setGroupCheckable(1, true, true);
            android.view.MenuItem allCategories = popup.getMenu().add(
                    1, 20_000, 0, R.string.grocery_filter_all_categories);
            allCategories.setChecked(overlayCategoryFilter.isEmpty());
            for (int index = 1; index < categoryLabels.length; index++) {
                android.view.MenuItem categoryItem = popup.getMenu().add(
                        1, 20_000 + index, index, categoryLabels[index]);
                categoryItem.setChecked(categoryLabels[index]
                        .equalsIgnoreCase(overlayCategoryFilter));
            }
            popup.setOnMenuItemClickListener(item -> {
                int categoryIndex = item.getItemId() - 20_000;
                overlayCategoryFilter = categoryIndex <= 0
                        ? "" : categoryLabels[categoryIndex];
                item.setChecked(true);
                search.setHint(overlayCategoryFilter.isEmpty()
                        ? getString(R.string.grocery_search_hint)
                        : getString(R.string.grocery_search_hint)
                                + " • " + overlayCategoryFilter);
                categoryFilter.setColorFilter(overlayCategoryFilter.isEmpty()
                        ? Color.rgb(15, 108, 189) : Color.rgb(15, 108, 89));
                refreshPanel();
                return true;
            });
            popup.show();
        });
        collapse.setOnClickListener(v -> {
            collapseAllCategories = !collapseAllCategories;
            collapsedCategories.clear();
            collapse.setText(collapseAllCategories
                    ? R.string.grocery_expand_all : R.string.grocery_collapse_all);
            refreshPanel();
        });

        itemContainer = new LinearLayout(this);
        itemContainer.setOrientation(LinearLayout.VERTICAL);
        overlayItemScroll = new ScrollView(this);
        overlayItemScroll.setFillViewport(false);
        overlayItemScroll.setVerticalScrollBarEnabled(true);
        overlayItemScroll.setScrollbarFadingEnabled(false);
        overlayItemScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        overlayItemScroll.addView(itemContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        overlayListCompactHeight = Math.max(dp(110),
                Math.min(dp(180), screenHeight / 5));
        overlayListExpandedHeight = Math.max(dp(250),
                Math.min(dp(480), screenHeight * 46 / 100));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                overlayListCompactHeight);
        listParams.topMargin = dp(10);
        root.addView(overlayItemScroll, listParams);

        overlayFormCollapsed = false;
        overlayFormToggle.setOnClickListener(v ->
                setOverlayFormCollapsed(!overlayFormCollapsed));
        overlayItemScroll.setOnScrollChangeListener((view, scrollX, scrollY,
                oldScrollX, oldScrollY) -> {
            if (!overlayFormCollapsed && scrollY > oldScrollY + dp(2)) {
                setOverlayFormCollapsed(true);
            }
        });

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
            pendingVoiceText = input.getText().toString();
            closePanel();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                Intent capture = new Intent(this, GroceryVoiceCaptureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(capture);
            }, 180L);
        });

        add.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError(getString(R.string.grocery_name_required));
                return;
            }
            if (category.getSelectedItemPosition() <= 0) {
                android.widget.Toast.makeText(this,
                        R.string.grocery_category_required,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            GroceryItem item = new GroceryItem();
            item.name = name;
            item.listType = selectedListType[0];
            String amount = quantity.getText().toString().trim();
            item.quantity = amount.isEmpty() ? "" : amount + " "
                    + String.valueOf(quantityUnit.getSelectedItem());
            item.category = String.valueOf(category.getSelectedItem());
            String priceText = price.getText().toString().trim();
            if (!priceText.isEmpty()) {
                try {
                    item.estimatedCost = Double.parseDouble(priceText);
                } catch (NumberFormatException error) {
                    price.setError(getString(R.string.grocery_invalid_cost));
                    return;
                }
            }
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
                price.setText("");
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
            if (!item.isPurchased && listType.equals(item.listType)
                    && matchesOverlayFilters(item)) count++;
        }
        TextView sectionTitle = text(heading + "  (" + count + ")", 12, true);
        sectionTitle.setTextColor(GroceryItem.LIST_MONTHLY.equals(listType)
                ? Color.rgb(107, 76, 154) : Color.rgb(15, 108, 89));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        titleParams.topMargin = alreadyShown == 0 ? 0 : dp(6);
        itemContainer.addView(sectionTitle, titleParams);

        Map<String, List<GroceryItem>> grouped = new LinkedHashMap<>();
        for (GroceryItem item : items) {
            if (item.isPurchased || !listType.equals(item.listType)
                    || !matchesOverlayFilters(item)) {
                continue;
            }
            String category = item.category.isEmpty()
                    ? getString(R.string.grocery_uncategorized) : item.category;
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(item);
        }
        int shownHere = 0;
        for (Map.Entry<String, List<GroceryItem>> group : grouped.entrySet()) {
            String collapseKey = listType + "|"
                    + group.getKey().toLowerCase(java.util.Locale.ENGLISH);
            boolean collapsed = collapseAllCategories
                    || collapsedCategories.contains(collapseKey);
            TextView category = text((collapsed ? "▸  " : "▾  ") + group.getKey() + "  ("
                    + group.getValue().size() + ")", 11, true);
            category.setTextColor(Color.rgb(15, 108, 89));
            category.setGravity(Gravity.CENTER_VERTICAL);
            category.setPadding(dp(9), 0, dp(9), 0);
            category.setBackground(roundedFill(Color.rgb(226, 244, 238), 8));
            LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            categoryParams.topMargin = shownHere == 0 ? 0 : dp(4);
            categoryParams.bottomMargin = dp(3);
            itemContainer.addView(category, categoryParams);
            category.setOnClickListener(v -> {
                collapseAllCategories = false;
                if (!collapsedCategories.add(collapseKey)) {
                    collapsedCategories.remove(collapseKey);
                }
                refreshPanel();
            });
            if (collapsed) continue;
            for (GroceryItem item : group.getValue()) {
            CheckBox row = new CheckBox(this);
            String detail = (shownHere + 1) + ".  " + item.name;
            if (!item.quantity.isEmpty()) detail += "  •  " + item.quantity;
            if (!item.assignedMemberName.isEmpty()) {
                detail += "  •  " + item.assignedMemberName;
            }
            row.setText(detail);
            row.setTextSize(13f);
            row.setTextColor(Color.rgb(36, 36, 36));
            row.setMinHeight(dp(38));
            row.setPadding(dp(6), 0, dp(6), 0);
            row.setBackground(roundedFill(
                    GroceryItem.LIST_MONTHLY.equals(listType)
                            ? Color.rgb(246, 241, 252)
                            : Color.rgb(237, 249, 243), 10));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    button.setChecked(false);
                    showInlinePurchaseEditor(item);
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            rowParams.bottomMargin = dp(3);
            itemContainer.addView(row, rowParams);
            shownHere++;
            }
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

    private boolean matchesOverlaySearch(GroceryItem item) {
        if (overlaySearchQuery.isEmpty()) return true;
        String query = overlaySearchQuery.toLowerCase(java.util.Locale.ENGLISH);
        return item.name.toLowerCase(java.util.Locale.ENGLISH).contains(query)
                || item.category.toLowerCase(java.util.Locale.ENGLISH).contains(query)
                || item.quantity.toLowerCase(java.util.Locale.ENGLISH).contains(query)
                || item.assignedMemberName.toLowerCase(java.util.Locale.ENGLISH).contains(query);
    }

    private boolean matchesOverlayFilters(GroceryItem item) {
        boolean categoryMatches = overlayCategoryFilter.isEmpty()
                || overlayCategoryFilter.equalsIgnoreCase(
                        item.category == null ? "" : item.category.trim());
        return categoryMatches && matchesOverlaySearch(item);
    }

    /** Keeps purchase completion entirely inside the floating surface. */
    private void showInlinePurchaseEditor(GroceryItem item) {
        if (itemContainer == null) return;
        itemContainer.removeAllViews();
        setOverlayFormCollapsed(true);

        TextView title = text(getString(R.string.grocery_complete_title), 14, true);
        title.setTextColor(Color.rgb(15, 108, 89));
        itemContainer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        TextView itemName = text(item.name, 12, true);
        itemName.setPadding(dp(10), 0, dp(10), 0);
        itemName.setBackground(roundedFill(Color.rgb(226, 244, 238), 9));
        itemContainer.addView(itemName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        EditText price = compactInput(getString(R.string.grocery_overlay_price_hint));
        price.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double savedPrice = item.actualCost > 0D
                ? item.actualCost : item.estimatedCost;
        if (savedPrice > 0D) price.setText(String.valueOf(savedPrice));
        itemContainer.addView(labelledField(
                getString(R.string.grocery_actual_cost), price), fullEditorField());

        EditText store = compactInput(getString(R.string.grocery_store_hint));
        store.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        store.setText(item.storeName);
        itemContainer.addView(labelledField(
                getString(R.string.grocery_store_name), store), fullEditorField());

        LinearLayout quantityRow = new LinearLayout(this);
        quantityRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText quantity = compactInput(
                getString(R.string.grocery_quantity_amount_hint));
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        String[] parts = item.quantity.trim().split("\\s+", 2);
        if (parts.length > 0) quantity.setText(parts[0]);
        quantityRow.addView(labelledField(
                getString(R.string.grocery_quantity_amount), quantity), weightedField());
        String[] units = getResources().getStringArray(R.array.grocery_quantity_units);
        Spinner unit = compactSpinner(units);
        if (parts.length > 1) selectSpinner(unit, units, parts[1]);
        LinearLayout.LayoutParams unitParams = weightedField();
        unitParams.setMarginStart(dp(8));
        quantityRow.addView(labelledField(
                getString(R.string.grocery_quantity_unit), unit), unitParams);
        itemContainer.addView(quantityRow);

        String[] categories = getResources().getStringArray(
                R.array.grocery_category_labels);
        Spinner category = compactSpinner(categories);
        selectSpinner(category, categories, item.category);
        itemContainer.addView(labelledField(
                getString(R.string.grocery_category), category), fullEditorField());

        TextView historyInsight = text("", 10, false);
        historyInsight.setTextColor(Color.rgb(15, 108, 89));
        historyInsight.setPadding(dp(8), 0, dp(8), 0);
        historyInsight.setBackground(roundedFill(Color.rgb(226, 244, 238), 8));
        historyInsight.setVisibility(View.GONE);
        itemContainer.addView(historyInsight, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        repository.loadStoreComparison(item.name, item.quantity,
                (history, cheapest) -> {
            if (history == null || itemContainer == null) return;
            applyInlineHistory(history, cheapest, price, store, quantity,
                    unit, units, category, categories, historyInsight);
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = compactAction(getString(R.string.cancel),
                Color.rgb(91, 101, 114), Color.rgb(242, 244, 247));
        Button skip = compactAction(getString(R.string.grocery_skip_and_complete),
                Color.rgb(168, 93, 0), Color.rgb(255, 244, 222));
        Button save = compactAction(getString(R.string.save),
                Color.WHITE, Color.rgb(15, 108, 89));
        actions.addView(cancel, actionParams());
        actions.addView(skip, actionParams());
        actions.addView(save, actionParams());
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        actionsParams.topMargin = dp(8);
        itemContainer.addView(actions, actionsParams);

        cancel.setOnClickListener(v -> refreshPanel());
        skip.setOnClickListener(v -> repository.setPurchased(
                item, true, () -> showFloatingUndo(item)));
        save.setOnClickListener(v -> {
            String priceText = price.getText().toString().trim();
            if (!priceText.isEmpty()) {
                try { item.actualCost = Double.parseDouble(priceText); }
                catch (NumberFormatException error) {
                    price.setError(getString(R.string.grocery_invalid_cost));
                    return;
                }
            }
            String amount = quantity.getText().toString().trim();
            item.quantity = amount.isEmpty() ? "" : amount + " "
                    + String.valueOf(unit.getSelectedItem());
            if (category.getSelectedItemPosition() <= 0) {
                android.widget.Toast.makeText(this,
                        R.string.grocery_category_required,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            item.category = String.valueOf(category.getSelectedItem());
            item.storeName = store.getText().toString().trim();
            repository.setPurchased(item, true, () -> showFloatingUndo(item));
        });
    }

    private void showFloatingUndo(GroceryItem item) {
        if (itemContainer == null) return;
        itemContainer.removeAllViews();
        TextView message = text(getString(
                R.string.grocery_purchase_completed, item.name), 12, true);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.rgb(15, 108, 89));
        itemContainer.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        Button undo = compactAction(getString(R.string.grocery_undo),
                Color.WHITE, Color.rgb(15, 108, 89));
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        undoParams.leftMargin = dp(24); undoParams.rightMargin = dp(24);
        itemContainer.addView(undo, undoParams);
        final boolean[] handled = {false};
        undo.setOnClickListener(v -> {
            handled[0] = true;
            repository.undoPurchase(item, this::refreshPanel);
        });
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!handled[0] && itemContainer != null) refreshPanel();
        }, 7000L);
    }

    private void selectSpinner(Spinner spinner, String[] values, String wanted) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(wanted)) {
                spinner.setSelection(index);
                return;
            }
        }
    }

    private void applyInlineHistory(
            GroceryPurchase history,
            GroceryPurchase cheapest,
            EditText price,
            EditText store,
            EditText quantity,
            Spinner unit,
            String[] units,
            Spinner category,
            String[] categories,
            TextView insight
    ) {
        String[] previousQuantity = history.quantity.trim().split("\\s+", 2);
        if (previousQuantity.length > 0) quantity.setText(previousQuantity[0]);
        if (previousQuantity.length > 1) {
            selectSpinner(unit, units, previousQuantity[1]);
        }
        selectSpinner(category, categories, history.category);
        if (history.actualCost > 0D) {
            price.setText(String.valueOf(history.actualCost));
        }
        if (!history.storeName.isEmpty()) store.setText(history.storeName);
        insight.setVisibility(View.VISIBLE);
        insight.setText(inlineComparisonText(history, cheapest,
                history.actualCost));
        price.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                double current = 0D;
                try { current = Double.parseDouble(s == null
                        ? "" : s.toString().trim()); }
                catch (NumberFormatException ignored) { }
                insight.setText(inlineComparisonText(history, cheapest, current));
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private String inlineComparisonText(GroceryPurchase history,
                                        GroceryPurchase cheapest,
                                        double current) {
        StringBuilder value = new StringBuilder(getString(
                R.string.grocery_previous_purchase,
                history.quantity.isEmpty()
                        ? getString(R.string.grocery_quantity_not_added)
                        : history.quantity,
                history.category.isEmpty()
                        ? getString(R.string.grocery_uncategorized)
                        : history.category,
                String.format(java.util.Locale.ENGLISH, "₹%.2f",
                        history.actualCost)));
        if (!history.storeName.isEmpty()) value.append('\n').append(getString(
                R.string.grocery_previous_store, history.storeName));
        if (cheapest != null && !cheapest.storeName.isEmpty()) {
            value.append('\n').append(getString(R.string.grocery_cheapest_store,
                    cheapest.storeName, cheapest.actualCost));
            if (current > cheapest.actualCost) value.append('\n').append(getString(
                    R.string.grocery_possible_saving,
                    current - cheapest.actualCost));
        }
        if (current > 0D && history.actualCost > 0D) {
            double percent = (current - history.actualCost)
                    / history.actualCost * 100D;
            value.append('\n').append(Math.abs(percent) < 0.05D
                    ? getString(R.string.grocery_price_same)
                    : getString(R.string.grocery_price_change,
                            history.actualCost, current, percent));
        }
        return value.toString();
    }

    private LinearLayout.LayoutParams fullEditorField() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66));
        params.topMargin = dp(5);
        return params;
    }

    private Button compactAction(String label, int textColor, int fillColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11f);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setBackground(roundedFill(fillColor, 16));
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(38), 1f);
        params.setMarginStart(dp(4));
        return params;
    }

    private void closePanel() {
        if (panelView != null) {
            windowManager.removeView(panelView);
            panelView = null;
            itemContainer = null;
            overlayFormDetails = null;
            overlayItemScroll = null;
            overlayFormToggle = null;
            overlayFormCollapsed = false;
        }
    }

    private void setOverlayFormCollapsed(boolean collapsed) {
        if (overlayFormDetails == null || overlayItemScroll == null
                || overlayFormToggle == null) {
            return;
        }
        overlayFormCollapsed = collapsed;
        overlayFormDetails.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        overlayFormToggle.setText(collapsed
                ? R.string.grocery_overlay_show_form
                : R.string.grocery_overlay_hide_form);
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) overlayItemScroll.getLayoutParams();
        params.height = collapsed
                ? overlayListExpandedHeight
                : overlayListCompactHeight;
        overlayItemScroll.setLayoutParams(params);
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
