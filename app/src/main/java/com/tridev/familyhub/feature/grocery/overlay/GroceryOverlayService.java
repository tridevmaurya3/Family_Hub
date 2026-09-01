package com.tridev.familyhub.feature.grocery.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.widget.TextViewCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.FamilyHubAppLockManager;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.feature.grocery.GroceryMoneyManagerBridge;
import com.tridev.familyhub.feature.grocery.GroceryRecurrenceEngine;
import com.tridev.familyhub.feature.grocery.GroceryOptionCatalog;
import com.tridev.familyhub.feature.grocery.GrocerySmartCategoryPicker;
import com.tridev.familyhub.feature.integration.MoneyManagerMasterCatalogBridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Draggable, adjustable quick-grocery surface shown over other phone screens. */
public class GroceryOverlayService extends Service {

    public static final String ACTION_STOP =
            "com.tridev.familyhub.action.STOP_GROCERY_OVERLAY";
    public static final String ACTION_HIDE =
            "com.tridev.familyhub.action.HIDE_GROCERY_OVERLAY";
    public static final String ACTION_SHOW =
            "com.tridev.familyhub.action.SHOW_GROCERY_OVERLAY";
    public static final String ACTION_SUSPEND_FOR_VOICE =
            "com.tridev.familyhub.action.SUSPEND_GROCERY_FOR_VOICE";
    public static final String ACTION_RESUME_AFTER_VOICE =
            "com.tridev.familyhub.action.RESUME_GROCERY_AFTER_VOICE";
    public static final String PREFS = "grocery_overlay";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_REQUESTED = "permission_requested";

    private static final String CHANNEL_ID = "grocery_overlay_channel";
    private static final int NOTIFICATION_ID = 4107;
    private static final int SMART_VISIBLE_CHIPS = 5;
    private static final String SHOPPING_ALL = "ALL";
    private static final String KEY_OVERLAY_MODE = "professional_mode";
    private static final String KEY_LAST_STANDARD_MODE = "last_standard_mode";
    private static final String MODE_NORMAL = "NORMAL";
    private static final String MODE_MINI = "MINI";
    private static final String MODE_SHOPPING = "SHOPPING";

    private WindowManager windowManager;
    private WindowManager.LayoutParams stripParams;
    private WindowManager.LayoutParams panelParams;
    private View stripView;
    private View panelView;
    private LinearLayout itemContainer;
    private LinearLayout overlayFormDetails;
    private LinearLayout overlayOptionalDetails;
    private ScrollView overlayItemScroll;
    private Button overlayFormToggle;
    private boolean overlayFormCollapsed;
    private int overlayListCompactHeight;
    private int overlayListExpandedHeight;
    private GroceryRepository repository;
    private FamilyMemberRepository memberRepository;
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    private volatile MoneyManagerMasterCatalogBridge.Catalog moneyCatalog =
            MoneyManagerMasterCatalogBridge.Catalog.unavailable("Loading MoneyManager");
    private volatile boolean moneyCatalogRefreshing;
    @Nullable private Runnable pendingMoneyCatalogAction;
    private String visibleListType = GroceryItem.LIST_DAILY;
    private String pendingVoiceText = "";
    private String overlaySearchQuery = "";
    private String overlayCategoryFilter = "";
    private boolean collapseAllCategories;
    private boolean overlayShoppingMode;
    private boolean overlayShoppingScreenOn;
    private String overlayShoppingSelection = SHOPPING_ALL;
    private boolean overlayInlinePurchaseEditorOpen;
    @Nullable private TextView overlayLiveStatus;
    @Nullable private DatabaseReference overlayConnectionReference;
    @Nullable private ValueEventListener overlayConnectionListener;
    private boolean voicePanelDetached;
    private boolean voiceStripWasVisible;
    private final Set<String> collapsedCategories = new HashSet<>();

    private boolean cornerResizeActive;
    private int cornerResizeHorizontalDirection;
    private int cornerResizeVerticalDirection;
    private int cornerResizeStartWidth;
    private int cornerResizeStartHeight;
    private int cornerResizeStartX;
    private int cornerResizeStartY;
    private float cornerResizeDownRawX;
    private float cornerResizeDownRawY;

    private final BroadcastReceiver voiceResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String result = intent.getStringExtra(GroceryVoiceCaptureActivity.EXTRA_RESULT);
            if (result != null && !result.trim().isEmpty()) {
                pendingVoiceText = result.trim();
            }
            FamilyHubAppLockManager.noteTrustedOverlayInteraction();
            resumeOverlayAfterVoice();
            if (panelView == null) togglePanel();
        }
    };

    /** Screen-off must never leave the expanded Grocery form visible on wake. */
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                closePanel();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).apply();
        String savedOverlayMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_OVERLAY_MODE, MODE_NORMAL);
        overlayShoppingMode = MODE_SHOPPING.equals(savedOverlayMode);
        overlayFormCollapsed = overlayShoppingMode || MODE_MINI.equals(savedOverlayMode);
        repository = new GroceryRepository(this);
        memberRepository = new FamilyMemberRepository(this);
        memberRepository.loadMembers("", members -> {
            familyMembers.clear();
            familyMembers.addAll(members);
        });
        new Thread(() -> moneyCatalog = MoneyManagerMasterCatalogBridge.load(this),
                "GroceryMoneyMasterCatalog").start();
        repository.startRealtimeSync(this::refreshPanel);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        androidx.core.content.ContextCompat.registerReceiver(this,
                voiceResultReceiver,
                new IntentFilter(GroceryVoiceCaptureActivity.ACTION_RESULT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        androidx.core.content.ContextCompat.registerReceiver(this,
                screenStateReceiver,
                new IntentFilter(Intent.ACTION_SCREEN_OFF),
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
            if (stripView != null) stripView.setVisibility(View.GONE);
            return START_STICKY;
        }
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            if (stripView == null && Settings.canDrawOverlays(this)) showStrip();
            if (stripView != null) stripView.setVisibility(View.VISIBLE);
            return START_STICKY;
        }
        if (intent != null && ACTION_SUSPEND_FOR_VOICE.equals(intent.getAction())) {
            suspendOverlayForVoice();
            return START_STICKY;
        }
        if (intent != null && ACTION_RESUME_AFTER_VOICE.equals(intent.getAction())) {
            resumeOverlayAfterVoice();
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void showStrip() {
        if (stripView != null) return;
        TextView strip = new TextView(this);
        strip.setText("+");
        strip.setTextColor(Color.rgb(11, 79, 59));
        strip.setTextSize(26f);
        strip.setTypeface(strip.getTypeface(), android.graphics.Typeface.BOLD);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(0, 0, 0, dp(2));
        strip.setBackground(rounded(Color.argb(238, 231, 246, 240),
                Color.argb(210, 15, 122, 90), 22));
        strip.setElevation(dp(10));
        strip.setAlpha(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat("alpha", 0.88f));
        stripView = strip;

        stripParams = overlayParams(dp(44), dp(44), true);
        stripParams.gravity = Gravity.TOP | Gravity.START;
        stripParams.x = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("x", dp(12));
        stripParams.y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("y", dp(160));
        strip.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event != null) FamilyHubAppLockManager.noteTrustedOverlayInteraction();
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
                            .putInt("x", stripParams.x).putInt("y", stripParams.y).apply();
                    if (!moved) togglePanel();
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
        FrameLayout panelRoot = new FrameLayout(this) {
            private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                cornerPaint.setColor(Color.argb(190, 74, 143, 183));
                cornerPaint.setStrokeWidth(dp(1));
                cornerPaint.setStyle(Paint.Style.STROKE);
                setWillNotDraw(false);
            }
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                drawResizeCornerGuides(canvas, getWidth(), getHeight(), cornerPaint);
            }
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event != null) {
                    FamilyHubAppLockManager.noteTrustedOverlayInteraction();
                    if (handlePanelCornerResizeGesture(this, event)) return true;
                    if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                        if (!overlayShoppingMode) closePanel();
                        return true;
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(18), dp(14));
        root.setBackground(panelGradient());
        root.setElevation(dp(16));
        root.setClipToPadding(false);
        root.setFocusableInTouchMode(true);
        root.setClickable(true);
        root.setOnClickListener(v -> {
            if (!overlayShoppingMode) closePanel();
        });
        panelRoot.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View glassHighlight = new View(this);
        glassHighlight.setClickable(false);
        glassHighlight.setFocusable(false);
        glassHighlight.setBackground(glassTopHighlight());
        FrameLayout.LayoutParams highlightParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(9), Gravity.TOP);
        highlightParams.leftMargin = dp(18);
        highlightParams.rightMargin = dp(18);
        highlightParams.topMargin = dp(4);
        panelRoot.addView(glassHighlight, highlightParams);

        root.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return false;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("Family Grocery", 16, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleStack.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));

        LinearLayout subtitleRow = new LinearLayout(this);
        subtitleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView shoppingSubtitle = text("(" + getString(R.string.grocery_list_type) + ")", 10, true);
        shoppingSubtitle.setTextColor(Color.rgb(84, 93, 105));
        shoppingSubtitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        shoppingSubtitle.setSingleLine(true);
        shoppingSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        overlayLiveStatus = text("● Connecting", 9, true);
        overlayLiveStatus.setSingleLine(true);
        overlayLiveStatus.setTextColor(Color.rgb(84, 93, 105));
        subtitleRow.addView(overlayLiveStatus, new LinearLayout.LayoutParams(0, dp(20), 0.55f));
        subtitleRow.addView(shoppingSubtitle, new LinearLayout.LayoutParams(0, dp(20), 0.45f));

        Button screenOn = compactAction("Screen On",
                Color.rgb(15, 108, 189), Color.argb(220, 232, 243, 252));
        screenOn.setTextSize(8f);
        screenOn.setSingleLine(true);
        screenOn.setPadding(dp(3), 0, dp(3), 0);
        screenOn.setElevation(dp(1));
        screenOn.setVisibility(View.GONE);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                screenOn, 7, 8, 1, TypedValue.COMPLEX_UNIT_SP);
        screenOn.setContentDescription("Keep screen on in floating Shopping Mode");
        LinearLayout.LayoutParams screenOnParams = new LinearLayout.LayoutParams(dp(60), dp(20));
        screenOnParams.setMarginStart(dp(3));
        subtitleRow.addView(screenOn, screenOnParams);
        titleStack.addView(subtitleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));

        header.addView(titleStack, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button shoppingModeDropdown = compactAction("Shopping Mode  ▾",
                Color.rgb(15, 108, 89), Color.argb(230, 226, 244, 238));
        shoppingModeDropdown.setTextSize(8.5f);
        shoppingModeDropdown.setSingleLine(true);
        shoppingModeDropdown.setPadding(dp(4), 0, dp(4), 0);
        shoppingModeDropdown.setElevation(dp(2));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                shoppingModeDropdown, 7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        shoppingModeDropdown.setContentDescription("Floating Shopping Mode menu");
        LinearLayout.LayoutParams shoppingHeaderParams = new LinearLayout.LayoutParams(dp(76), dp(32));
        shoppingHeaderParams.setMarginStart(dp(4));
        header.addView(shoppingModeDropdown, shoppingHeaderParams);

        overlayFormToggle = compactAction("More details  ▾",
                Color.rgb(15, 108, 89), Color.argb(230, 226, 244, 238));
        overlayFormToggle.setTextSize(8.5f);
        overlayFormToggle.setSingleLine(true);
        overlayFormToggle.setPadding(dp(4), 0, dp(4), 0);
        overlayFormToggle.setElevation(dp(2));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                overlayFormToggle, 7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams formHeaderParams = new LinearLayout.LayoutParams(dp(78), dp(32));
        formHeaderParams.setMarginStart(dp(4));
        header.addView(overlayFormToggle, formHeaderParams);

        Button headerMenu = compactAction("⋮",
                Color.rgb(73, 86, 98), Color.argb(218, 242, 246, 248));
        headerMenu.setTextSize(18f);
        headerMenu.setSingleLine(true);
        headerMenu.setPadding(0, 0, 0, 0);
        headerMenu.setElevation(dp(1));
        headerMenu.setContentDescription("More Grocery overlay options");
        LinearLayout.LayoutParams headerMenuParams =
                new LinearLayout.LayoutParams(dp(32), dp(32));
        headerMenuParams.setMarginStart(dp(3));
        header.addView(headerMenu, headerMenuParams);

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(20f);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        overlayFormDetails = new LinearLayout(this);
        overlayFormDetails.setOrientation(LinearLayout.VERTICAL);
        overlayOptionalDetails = new LinearLayout(this);
        overlayOptionalDetails.setOrientation(LinearLayout.VERTICAL);

        final String[] selectedListType = {visibleListType};
        RadioGroup listTypeGroup = new RadioGroup(this);
        listTypeGroup.setOrientation(RadioGroup.HORIZONTAL);
        listTypeGroup.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton daily = new RadioButton(this);
        daily.setId(View.generateViewId());
        daily.setText("Daily");
        daily.setTextSize(8.5f);
        daily.setSingleLine(true);
        daily.setMinWidth(0);
        daily.setMinimumWidth(0);
        daily.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(daily, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        daily.setChecked(GroceryItem.LIST_DAILY.equals(visibleListType));

        RadioButton monthly = new RadioButton(this);
        monthly.setId(View.generateViewId());
        monthly.setText("Monthly");
        monthly.setTextSize(8.5f);
        monthly.setSingleLine(true);
        monthly.setMinWidth(0);
        monthly.setMinimumWidth(0);
        monthly.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(monthly, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        monthly.setChecked(GroceryItem.LIST_MONTHLY.equals(visibleListType));

        RadioButton twoMonth = new RadioButton(this);
        twoMonth.setId(View.generateViewId());
        twoMonth.setText("Weekly");
        twoMonth.setTextSize(8.5f);
        twoMonth.setSingleLine(true);
        twoMonth.setMinWidth(0);
        twoMonth.setMinimumWidth(0);
        twoMonth.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(twoMonth, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        twoMonth.setChecked(GroceryItem.LIST_TWO_MONTH.equals(visibleListType));

        RadioButton threeMonth = new RadioButton(this);
        threeMonth.setId(View.generateViewId());
        threeMonth.setText("Fortnightly");
        threeMonth.setTextSize(8.5f);
        threeMonth.setSingleLine(true);
        threeMonth.setMinWidth(0);
        threeMonth.setMinimumWidth(0);
        threeMonth.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(threeMonth, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        threeMonth.setChecked(GroceryItem.LIST_THREE_MONTH.equals(visibleListType));

        listTypeGroup.addView(daily, new RadioGroup.LayoutParams(0, dp(36), 0.75f));
        listTypeGroup.addView(twoMonth, new RadioGroup.LayoutParams(0, dp(36), 0.92f));
        listTypeGroup.addView(threeMonth, new RadioGroup.LayoutParams(0, dp(36), 1.35f));
        listTypeGroup.addView(monthly, new RadioGroup.LayoutParams(0, dp(36), 0.98f));
        listTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == -1) return;
            if (checkedId == threeMonth.getId()) selectedListType[0] = GroceryItem.LIST_THREE_MONTH;
            else if (checkedId == twoMonth.getId()) selectedListType[0] = GroceryItem.LIST_TWO_MONTH;
            else if (checkedId == monthly.getId()) selectedListType[0] = GroceryItem.LIST_MONTHLY;
            else selectedListType[0] = GroceryItem.LIST_DAILY;
            visibleListType = selectedListType[0];
            refreshPanel();
        });

        screenOn.setOnClickListener(v -> {
            FamilyHubAppLockManager.noteTrustedOverlayInteraction();
            if (!overlayShoppingMode) return;
            overlayShoppingScreenOn = !overlayShoppingScreenOn;
            applyOverlayScreenOn(overlayShoppingScreenOn);
            updateOverlayShoppingModeUi(shoppingModeDropdown, screenOn, shoppingSubtitle);
        });

        shoppingModeDropdown.setOnClickListener(v -> showOverlayShoppingMenu(
                shoppingModeDropdown, screenOn, shoppingSubtitle));

        root.addView(listTypeGroup, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout opacityPanel = new LinearLayout(this);
        opacityPanel.setOrientation(LinearLayout.VERTICAL);
        opacityPanel.setPadding(dp(8), dp(2), dp(8), dp(2));
        opacityPanel.setBackground(roundedFill(Color.argb(218, 242, 248, 253), 10));
        opacityPanel.setVisibility(View.GONE);
        TextView opacityLabel = text(getString(R.string.grocery_overlay_opacity), 10, false);
        opacityPanel.addView(opacityLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        SeekBar opacity = new SeekBar(this);
        float savedAlpha = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("alpha", 0.88f);
        int savedProgress = Math.round((savedAlpha - 0.35f) / 0.65f * 100f);
        opacity.setProgress(Math.max(0, Math.min(100, savedProgress)));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = 0.35f + (progress / 100f) * 0.65f;
                if (stripView != null) stripView.setAlpha(alpha);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("alpha", alpha).apply();
            }
        });
        opacityPanel.addView(opacity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        headerMenu.setOnClickListener(v -> {
            FamilyHubAppLockManager.noteTrustedOverlayInteraction();
            PopupMenu menu = new PopupMenu(this, headerMenu);
            menu.getMenu().add(0, 1, 0, getString(R.string.grocery_overlay_opacity));
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() != 1) return false;
                boolean show = opacityPanel.getVisibility() != View.VISIBLE;
                opacityPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                return true;
            });
            menu.show();
        });
        root.addView(opacityPanel);

        final String[] quantityUnits = getResources().getStringArray(R.array.grocery_quantity_units);
        final String[] categoryLabels = GroceryOptionCatalog.categoryLabels(this);
        final String[] categoryChoices = GroceryOptionCatalog.categoryLabelsWithAdd(this);
        EditText quantity = compactInput(getString(R.string.grocery_quantity_amount_hint));
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner quantityUnit = compactSpinner(quantityUnits);
        EditText price = compactInput(getString(R.string.grocery_overlay_price_hint));
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner category = compactSpinner(categoryChoices);
        enableCustomCategoryCreation(category, "");
        GrocerySmartCategoryPicker.attach(this, category, value -> {
            String[] updated = GroceryOptionCatalog.categoryLabelsWithAdd(this);
            category.setAdapter(compactSpinnerAdapter(updated));
            category.setDropDownWidth(adaptivePopupWidth(updated, 12.5f));
            selectSpinner(category, updated, value);
        });
        Spinner moneyAccount = moneyAccountSpinner();
        Spinner moneyCategory = moneyCategorySpinner();
        Spinner priority = compactSpinner(getResources().getStringArray(R.array.grocery_priority_labels));

        List<String> memberLabels = new ArrayList<>();
        memberLabels.add(getString(R.string.grocery_whole_family));
        for (FamilyMember familyMember : familyMembers) memberLabels.add(familyMember.name);
        Spinner member = compactSpinner(memberLabels.toArray(new String[0]));
        final List<GroceryItem> smartSuggestions = new ArrayList<>();
        Spinner quickPick = compactSpinner(new String[]{"Suggested"});

        LinearLayout basicDetails = new LinearLayout(this);
        basicDetails.setOrientation(LinearLayout.HORIZONTAL);
        addCompactColumn(basicDetails, labelledField(getString(R.string.grocery_quantity_amount), quantity), false);
        addCompactColumn(basicDetails, labelledField(getString(R.string.grocery_quantity_unit), quantityUnit), true);
        addCompactColumn(basicDetails, labelledField(getString(R.string.grocery_category), category), true);
        overlayFormDetails.addView(basicDetails);

        LinearLayout optionalMoneyDetails = new LinearLayout(this);
        optionalMoneyDetails.setOrientation(LinearLayout.HORIZONTAL);
        addCompactColumn(optionalMoneyDetails, labelledField(getString(R.string.grocery_overlay_price), price), false);
        addCompactColumn(optionalMoneyDetails, labelledField(getString(R.string.money_manager_paid_from), moneyAccount), true);
        addCompactColumn(optionalMoneyDetails, labelledField(getString(R.string.money_manager_expense_category), moneyCategory), true);
        overlayOptionalDetails.addView(optionalMoneyDetails);
        attachLiveMoneyCatalog(moneyAccount, moneyCategory);

        LinearLayout optionalAssignmentDetails = new LinearLayout(this);
        optionalAssignmentDetails.setOrientation(LinearLayout.HORIZONTAL);
        addCompactColumn(optionalAssignmentDetails, labelledField(getString(R.string.grocery_priority), priority), false);
        addCompactColumn(optionalAssignmentDetails, labelledField(getString(R.string.grocery_assign_to), member), true);
        addCompactColumn(optionalAssignmentDetails, labelledField("Quick pick", quickPick), true);
        overlayOptionalDetails.addView(optionalAssignmentDetails);
        overlayFormDetails.addView(overlayOptionalDetails);

        TextView quickLabel = text(getString(R.string.grocery_overlay_quick_item), 11, true);
        LinearLayout.LayoutParams quickLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        root.addView(quickLabel, quickLabelParams);

        LinearLayout quickAdd = new LinearLayout(this);
        quickAdd.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new BackAwareEditText();
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.setClickable(true);
        input.setTextSize(13f);
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setHint(R.string.grocery_overlay_add_hint);
        input.setPadding(dp(12), 0, dp(8), 0);
        input.setBackground(glassFieldBackground());
        input.setElevation(dp(1));
        if (!pendingVoiceText.isEmpty()) {
            input.setText(pendingVoiceText);
            input.setSelection(input.length());
            pendingVoiceText = "";
        }
        input.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
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
        quickAdd.addView(input, new LinearLayout.LayoutParams(0, dp(42), 1f));

        ImageButton voice = new ImageButton(this);
        voice.setImageResource(R.drawable.ic_mic);
        voice.setContentDescription(getString(R.string.grocery_overlay_voice));
        voice.setColorFilter(Color.rgb(15, 108, 189));
        voice.setPadding(dp(11), dp(11), dp(11), dp(11));
        voice.setBackground(rounded(Color.argb(220, 232, 243, 252),
                Color.argb(190, 190, 216, 236), 22));
        voice.setElevation(dp(2));
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        voiceParams.setMarginStart(dp(6));
        quickAdd.addView(voice, voiceParams);

        Button add = new Button(this);
        add.setText("＋ Add");
        add.setTextSize(12f);
        add.setTextColor(Color.WHITE);
        add.setBackground(rounded(Color.rgb(15, 108, 189), Color.rgb(15, 108, 189), 22));
        add.setElevation(dp(3));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(82), dp(42));
        addParams.setMarginStart(dp(8));
        quickAdd.addView(add, addParams);
        root.addView(quickAdd);

        TextView smartLabel = text("Smart suggestions • Category-wise • Most used first", 9, true);
        smartLabel.setTextColor(Color.rgb(84, 93, 105));
        smartLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams smartLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20));
        smartLabelParams.topMargin = dp(3);
        root.addView(smartLabel, smartLabelParams);

        LinearLayout suggestionBar = new LinearLayout(this);
        suggestionBar.setGravity(Gravity.CENTER_VERTICAL);
        suggestionBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView suggestionScroll = new HorizontalScrollView(this);
        suggestionScroll.setHorizontalScrollBarEnabled(false);
        suggestionScroll.setFillViewport(false);
        suggestionScroll.setVisibility(View.INVISIBLE);
        LinearLayout suggestionRow = new LinearLayout(this);
        suggestionRow.setOrientation(LinearLayout.HORIZONTAL);
        suggestionRow.setGravity(Gravity.CENTER_VERTICAL);
        suggestionScroll.addView(suggestionRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        suggestionBar.addView(suggestionScroll, new LinearLayout.LayoutParams(0, dp(36), 1f));
        Button moreSuggestions = compactAction("•••", Color.rgb(15, 108, 189),
                Color.argb(225, 232, 243, 252));
        moreSuggestions.setContentDescription("More smart suggestions");
        moreSuggestions.setTextSize(14f);
        moreSuggestions.setVisibility(View.GONE);
        moreSuggestions.setElevation(dp(1));
        LinearLayout.LayoutParams moreSuggestionParams = new LinearLayout.LayoutParams(dp(40), dp(32));
        moreSuggestionParams.setMarginStart(dp(5));
        suggestionBar.addView(moreSuggestions, moreSuggestionParams);
        Button clearSuggestions = compactAction("Clear", Color.rgb(170, 62, 62),
                Color.argb(238, 255, 248, 249));
        clearSuggestions.setContentDescription("Clear grocery form");
        clearSuggestions.setTextSize(10f);
        clearSuggestions.setSingleLine(true);
        clearSuggestions.setElevation(dp(1));
        clearSuggestions.setBackground(rounded(Color.argb(238, 255, 248, 249),
                Color.argb(205, 213, 133, 139), 16));
        LinearLayout.LayoutParams clearSuggestionParams = new LinearLayout.LayoutParams(dp(62), dp(32));
        clearSuggestionParams.setMarginStart(dp(5));
        suggestionBar.addView(clearSuggestions, clearSuggestionParams);
        root.addView(suggestionBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        clearSuggestions.setOnClickListener(v -> clearSmartFilledForm(
                input, quantity, quantityUnit, category, price, quickPick));

        repository.loadItems("", allItems -> {
            List<GroceryItem> suggestions = rankSmartSuggestions(allItems);
            smartSuggestions.clear();
            smartSuggestions.addAll(suggestions);
            suggestionRow.removeAllViews();
            if (suggestions.isEmpty()) {
                smartLabel.setVisibility(View.GONE);
                suggestionScroll.setVisibility(View.INVISIBLE);
                moreSuggestions.setVisibility(View.GONE);
                String[] emptyQuickPick = new String[]{"Suggested"};
                quickPick.setAdapter(compactSpinnerAdapter(emptyQuickPick));
                quickPick.setDropDownWidth(adaptivePopupWidth(emptyQuickPick, 12.5f));
                return;
            }
            smartLabel.setVisibility(View.VISIBLE);
            suggestionScroll.setVisibility(View.VISIBLE);
            String[] quickPickLabels = new String[suggestions.size() + 1];
            quickPickLabels[0] = "Suggested";
            for (int index = 0; index < suggestions.size(); index++) {
                quickPickLabels[index + 1] = suggestions.get(index).name;
            }
            int visibleCount = Math.min(SMART_VISIBLE_CHIPS, suggestions.size());
            for (int index = 0; index < visibleCount; index++) {
                GroceryItem suggestion = suggestions.get(index);
                Button chip = compactAction(suggestion.name, Color.rgb(15, 108, 89),
                        Color.argb(220, 239, 250, 243));
                chip.setTextSize(10f);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setElevation(dp(1));
                chip.setOnClickListener(v -> applySmartSuggestion(suggestion, input,
                        quantity, quantityUnit, quantityUnits, category, categoryLabels, price));
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
                chipParams.setMarginEnd(dp(6));
                suggestionRow.addView(chip, chipParams);
            }
            if (suggestions.size() > visibleCount) {
                moreSuggestions.setVisibility(View.VISIBLE);
                moreSuggestions.setOnClickListener(v -> showSmartSuggestionOverflow(
                        moreSuggestions, suggestions, input, quantity,
                        quantityUnit, quantityUnits, category, categoryLabels, price));
            } else {
                moreSuggestions.setVisibility(View.GONE);
                moreSuggestions.setOnClickListener(null);
            }
            quickPick.setAdapter(compactSpinnerAdapter(quickPickLabels));
            quickPick.setDropDownWidth(adaptivePopupWidth(quickPickLabels, 12.5f));
            quickPick.setSelection(0);
        });

        quickPick.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int position, long id) {
                if (position <= 0 || position > smartSuggestions.size()) return;
                applySmartSuggestion(smartSuggestions.get(position - 1), input, quantity,
                        quantityUnit, quantityUnits, category, categoryLabels, price);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        root.addView(overlayFormDetails);

        LinearLayout listTools = new LinearLayout(this);
        listTools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(glassFieldBackground());
        searchBox.setElevation(dp(1));
        EditText search = compactInput(getString(R.string.grocery_search_hint));
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setElevation(0f);
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
        categoryFilter.setContentDescription(getString(R.string.grocery_filter_by_category));
        categoryFilter.setColorFilter(overlayCategoryFilter.isEmpty()
                ? Color.rgb(15, 108, 189) : Color.rgb(15, 108, 89));
        categoryFilter.setPadding(dp(10), dp(10), dp(10), dp(10));
        categoryFilter.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(categoryFilter, new LinearLayout.LayoutParams(dp(42), dp(42)));
        listTools.addView(searchBox, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button collapse = compactAction(getString(R.string.grocery_collapse_all),
                Color.rgb(15, 108, 89), Color.argb(220, 226, 244, 238));
        collapse.setElevation(dp(1));
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(dp(94), dp(38));
        collapseParams.setMarginStart(dp(6));
        listTools.addView(collapse, collapseParams);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        toolsParams.topMargin = dp(2);
        root.addView(listTools, toolsParams);

        categoryFilter.setOnClickListener(v ->
                showCategoryFilterPopup(categoryFilter, search, categoryLabels));
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
        overlayItemScroll.setClickable(true);
        overlayItemScroll.setOnClickListener(v -> {
            if (!overlayShoppingMode) closePanel();
        });
        overlayItemScroll.addView(itemContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int maxPanelWidth = screenWidth - dp(24);
        final int maxPanelHeight = screenHeight - dp(98);
        final int minPanelWidth = Math.min(maxPanelWidth, dp(280));
        final int minPanelHeight = Math.min(maxPanelHeight, dp(360));
        overlayListCompactHeight = dp(150);
        overlayListExpandedHeight = dp(250);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(2);
        root.addView(overlayItemScroll, listParams);

        String savedPanelMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_OVERLAY_MODE, MODE_NORMAL);
        overlayShoppingMode = MODE_SHOPPING.equals(savedPanelMode);
        overlayFormCollapsed = overlayShoppingMode || MODE_MINI.equals(savedPanelMode);
        overlayFormToggle.setOnClickListener(v -> {
            if (overlayShoppingMode) return;
            setOverlayFormCollapsed(!overlayFormCollapsed);
            rememberStandardOverlayMode(overlayFormCollapsed ? MODE_MINI : MODE_NORMAL);
        });
        setOverlayFormCollapsed(overlayFormCollapsed);
        overlayItemScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!overlayFormCollapsed && scrollY > oldScrollY + dp(2)) setOverlayFormCollapsed(true);
        });

        voice.setOnClickListener(v -> {
            FamilyHubAppLockManager.noteTrustedOverlayInteraction();
            pendingVoiceText = input.getText().toString();
            // Temporarily detach the system-overlay window so Google's recognizer
            // is the top foreground surface. The same form/state is reattached
            // when recognition finishes.
            suspendOverlayForVoice();
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
            if (category.getSelectedItemPosition() <= 0) {
                android.widget.Toast.makeText(this, R.string.grocery_category_required,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            rememberMoneyDefaults(moneyAccount, moneyCategory);
            GroceryItem item = new GroceryItem();
            item.name = name;
            item.listType = selectedListType[0];
            String amount = quantity.getText().toString().trim();
            item.quantity = amount.isEmpty() ? "" : amount + " " + quantityUnit.getSelectedItem();
            item.category = String.valueOf(category.getSelectedItem());
            String priceText = price.getText().toString().trim();
            if (!priceText.isEmpty()) {
                try { item.estimatedCost = Double.parseDouble(priceText); }
                catch (NumberFormatException error) {
                    price.setError(getString(R.string.grocery_invalid_cost));
                    return;
                }
            }
            int priorityIndex = priority.getSelectedItemPosition();
            item.priority = priorityIndex == 2 ? GroceryItem.PRIORITY_URGENT
                    : priorityIndex == 1 ? GroceryItem.PRIORITY_HIGH : GroceryItem.PRIORITY_NORMAL;
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
                quickPick.setSelection(0);
                refreshPanel();
            });
        });

        panelView = panelRoot;
        int savedPanelWidth = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("panel_width", maxPanelWidth);
        int savedPanelHeight = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("panel_height", maxPanelHeight);
        int resolvedPanelWidth = clamp(savedPanelWidth, minPanelWidth, maxPanelWidth);
        int resolvedPanelHeight = clamp(savedPanelHeight, minPanelHeight, maxPanelHeight);
        panelParams = overlayParams(resolvedPanelWidth, resolvedPanelHeight, false);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("panel_x", Math.max(0, (screenWidth - resolvedPanelWidth) / 2));
        panelParams.x = clamp(panelParams.x, 0, Math.max(0, screenWidth - resolvedPanelWidth));
        panelParams.y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("panel_y", dp(74));
        panelParams.y = clamp(panelParams.y, 0, Math.max(0, screenHeight - resolvedPanelHeight));
        windowManager.addView(panelView, panelParams);
        startOverlayConnectionStatus();
        root.requestFocus();
        updateOverlayShoppingModeUi(shoppingModeDropdown, screenOn, shoppingSubtitle);
        refreshPanel();
        input.requestFocus();
        input.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 150L);
    }

    private void showOverlayShoppingMenu(Button anchor,
                                         Button screenOn, TextView shoppingSubtitle) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();

        LinearLayout popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.VERTICAL);
        popupRoot.setPadding(dp(6), dp(6), dp(6), dp(6));
        popupRoot.setBackground(premiumDropdownBackground());
        popupRoot.setElevation(dp(12));

        android.widget.PopupWindow popup = new android.widget.PopupWindow(this);
        popup.setContentView(popupRoot);
        popup.setWidth(Math.min(dp(230), getResources().getDisplayMetrics().widthPixels - dp(32)));
        popup.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dp(12), dp(4), dp(8), dp(4));
        modeRow.setBackground(rounded(
                overlayShoppingMode ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                overlayShoppingMode ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));

        TextView modeLabel = text("Shopping Mode", 12, true);
        modeLabel.setTextColor(Color.rgb(15, 108, 89));
        modeLabel.setSingleLine(true);
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, dp(32), 1f));

        androidx.appcompat.widget.SwitchCompat modeSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        modeSwitch.setChecked(overlayShoppingMode);
        modeSwitch.setShowText(false);
        modeSwitch.setMinWidth(dp(48));
        modeSwitch.setMinimumWidth(dp(48));
        modeSwitch.setPadding(dp(2), 0, dp(2), 0);
        int[][] switchStates = new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{-android.R.attr.state_checked}};
        modeSwitch.setThumbTintList(new android.content.res.ColorStateList(
                switchStates, new int[]{Color.WHITE, Color.WHITE}));
        modeSwitch.setTrackTintList(new android.content.res.ColorStateList(
                switchStates, new int[]{Color.rgb(15, 153, 103), Color.rgb(188, 198, 202)}));
        modeRow.addView(modeSwitch, new LinearLayout.LayoutParams(dp(52), dp(36)));
        popupRoot.addView(modeRow, shoppingMenuRowParams(false));

        LinearLayout selectionContainer = new LinearLayout(this);
        selectionContainer.setOrientation(LinearLayout.VERTICAL);
        selectionContainer.setVisibility(overlayShoppingMode ? View.VISIBLE : View.GONE);
        popupRoot.addView(selectionContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, SHOPPING_ALL, "All", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_DAILY, "Daily", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_TWO_MONTH, "Weekly", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_THREE_MONTH, "Fortnightly", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_MONTHLY, "Monthly", true);

        modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            overlayShoppingMode = checked;
            if (checked) {
                String standard = overlayFormCollapsed ? MODE_MINI : MODE_NORMAL;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_STANDARD_MODE, standard)
                        .putString(KEY_OVERLAY_MODE, MODE_SHOPPING).apply();
                setOverlayFormCollapsed(true);
            } else {
                overlayShoppingScreenOn = false;
                overlayShoppingSelection = SHOPPING_ALL;
                applyOverlayScreenOn(false);
                String standard = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_LAST_STANDARD_MODE, MODE_NORMAL);
                boolean mini = MODE_MINI.equals(standard);
                setOverlayFormCollapsed(mini);
                rememberStandardOverlayMode(mini ? MODE_MINI : MODE_NORMAL);
            }
            selectionContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            modeRow.setBackground(rounded(
                    checked ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                    checked ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));
            updateOverlayShoppingModeUi(anchor, screenOn, shoppingSubtitle);
            refreshPanel();
        });
        modeRow.setOnClickListener(v -> modeSwitch.toggle());

        int xOffset = -(popup.getWidth() - Math.max(anchor.getWidth(), dp(120)));
        popup.showAsDropDown(anchor, xOffset, dp(5));
    }

    private void addShoppingSelectionRow(LinearLayout popupRoot,
                                         android.widget.PopupWindow popup,
                                         Button anchor,
                                         Button screenOn,
                                         TextView shoppingSubtitle,
                                         String value,
                                         String label,
                                         boolean last) {
        boolean selected = value.equals(overlayShoppingSelection);
        LinearLayout row = premiumShoppingMenuRow(label, selected, false);
        popupRoot.addView(row, shoppingMenuRowParams(last));
        row.setOnClickListener(v -> {
            overlayShoppingSelection = value;
            updateOverlayShoppingModeUi(anchor, screenOn, shoppingSubtitle);
            refreshPanel();
            popup.dismiss();
        });
    }

    private LinearLayout premiumShoppingMenuRow(String label, boolean selected, boolean modeRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(5), dp(8), dp(5));
        row.setBackground(rounded(
                selected ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                selected ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));
        TextView labelView = text(label, modeRow ? 12 : 11, modeRow || selected);
        labelView.setTextColor(selected ? Color.rgb(15, 108, 89) : Color.rgb(31, 42, 49));
        labelView.setSingleLine(true);
        row.addView(labelView, new LinearLayout.LayoutParams(0, dp(30), 1f));
        if (selected) {
            TextView check = text("✓", 13, true);
            check.setTextColor(Color.rgb(15, 108, 89));
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(30)));
        }
        return row;
    }

    private LinearLayout.LayoutParams shoppingMenuRowParams(boolean last) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        params.bottomMargin = last ? 0 : dp(4);
        return params;
    }

    private void showCategoryFilterPopup(ImageButton anchor, EditText search,
                                         String[] categoryLabels) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.grocery_filter_all_categories));
        for (int index = 1; index < categoryLabels.length; index++) {
            labels.add(categoryLabels[index]);
        }
        String[] widthLabels = labels.toArray(new String[0]);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int popupWidth = Math.min(adaptivePopupWidth(widthLabels, 12.5f), screenWidth - dp(28));
        int desiredHeight = dp(12) + labels.size() * dp(38);
        int maxHeight = Math.min(dp(300), Math.round(screenHeight * 0.40f));
        int popupHeight = Math.min(desiredHeight, maxHeight);

        LinearLayout popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.VERTICAL);
        popupRoot.setPadding(dp(5), dp(5), dp(5), dp(5));
        popupRoot.setBackground(premiumDropdownBackground());
        popupRoot.setElevation(dp(10));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        popupRoot.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(this);
        popup.setContentView(popupRoot);
        popup.setWidth(popupWidth);
        popup.setHeight(Math.max(dp(48), popupHeight));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        for (int index = 0; index < labels.size(); index++) {
            final int categoryIndex = index;
            final String label = labels.get(index);
            final boolean selected = categoryIndex == 0
                    ? overlayCategoryFilter.isEmpty()
                    : label.equalsIgnoreCase(overlayCategoryFilter);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(8), dp(6));
            row.setBackground(rounded(
                    selected ? Color.argb(242, 229, 247, 239)
                            : Color.argb(248, 255, 255, 255),
                    selected ? Color.argb(210, 126, 190, 165)
                            : Color.argb(125, 212, 222, 226), 10));

            TextView labelView = text(label, 12, selected);
            labelView.setTextColor(selected
                    ? Color.rgb(15, 108, 89) : Color.rgb(31, 42, 49));
            labelView.setSingleLine(true);
            labelView.setEllipsize(null);
            labelView.setIncludeFontPadding(false);
            row.addView(labelView, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (selected) {
                TextView check = text("✓", 13, true);
                check.setTextColor(Color.rgb(15, 108, 89));
                check.setGravity(Gravity.CENTER);
                row.addView(check, new LinearLayout.LayoutParams(
                        dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            row.setOnClickListener(v -> {
                overlayCategoryFilter = categoryIndex == 0 ? "" : label;
                search.setHint(overlayCategoryFilter.isEmpty()
                        ? getString(R.string.grocery_search_hint)
                        : getString(R.string.grocery_search_hint)
                                + " • " + overlayCategoryFilter);
                anchor.setColorFilter(overlayCategoryFilter.isEmpty()
                        ? Color.rgb(15, 108, 189) : Color.rgb(15, 108, 89));
                refreshPanel();
                popup.dismiss();
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(3);
            rows.addView(row, rowParams);
        }

        int xOffset = -(popupWidth - Math.max(anchor.getWidth(), dp(42)));
        popup.showAsDropDown(anchor, xOffset, dp(4));
    }

    private void refreshPanel() {
        if (repository == null || itemContainer == null || overlayInlinePurchaseEditorOpen) return;
        repository.loadItems("", this::renderItems);
    }

    private void renderItems(List<GroceryItem> items) {
        if (itemContainer == null || overlayInlinePurchaseEditorOpen) return;
        itemContainer.removeAllViews();
        if (!overlayShoppingMode) {
            renderSection(items, visibleListType, overlaySectionHeading(visibleListType), 0);
            return;
        }
        if (!SHOPPING_ALL.equals(overlayShoppingSelection)) {
            renderSection(items, overlayShoppingSelection,
                    overlaySectionHeading(overlayShoppingSelection), 0);
            return;
        }
        int shown = 0;
        shown = renderSection(items, GroceryItem.LIST_DAILY,
                overlaySectionHeading(GroceryItem.LIST_DAILY), shown);
        shown = renderSection(items, GroceryItem.LIST_TWO_MONTH,
                overlaySectionHeading(GroceryItem.LIST_TWO_MONTH), shown);
        shown = renderSection(items, GroceryItem.LIST_THREE_MONTH,
                overlaySectionHeading(GroceryItem.LIST_THREE_MONTH), shown);
        renderSection(items, GroceryItem.LIST_MONTHLY,
                overlaySectionHeading(GroceryItem.LIST_MONTHLY), shown);
    }

    private String overlaySectionHeading(String listType) {
        if (GroceryItem.LIST_THREE_MONTH.equals(listType)) return "Fortnightly items";
        if (GroceryItem.LIST_TWO_MONTH.equals(listType)) return "Weekly items";
        if (GroceryItem.LIST_MONTHLY.equals(listType)) {
            return getString(R.string.grocery_overlay_monthly_section);
        }
        return getString(R.string.grocery_overlay_daily_section);
    }

    private int renderSection(List<GroceryItem> items, String listType, String heading, int alreadyShown) {
        int count = 0;
        for (GroceryItem item : items) {
            if (!item.isPurchased && !item.recurrenceShadowed
                    && listType.equals(GroceryRecurrenceEngine.effectiveCycle(
                            item, System.currentTimeMillis()))
                    && matchesOverlayFilters(item)) count++;
        }
        TextView sectionTitle = text(heading + "  (" + count + ")", 12, true);
        sectionTitle.setTextColor(GroceryItem.LIST_MONTHLY.equals(listType)
                ? Color.rgb(107, 76, 154) : Color.rgb(15, 108, 89));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        titleParams.topMargin = alreadyShown == 0 ? 0 : dp(3);
        itemContainer.addView(sectionTitle, titleParams);

        List<GroceryItem> ordered = new ArrayList<>(items);
        ordered.sort((left, right) -> Integer.compare(priorityRank(left), priorityRank(right)));
        Map<String, List<GroceryItem>> grouped = new LinkedHashMap<>();
        for (GroceryItem item : ordered) {
            if (item.isPurchased || item.recurrenceShadowed
                    || !listType.equals(GroceryRecurrenceEngine.effectiveCycle(
                            item, System.currentTimeMillis()))
                    || !matchesOverlayFilters(item)) continue;
            String categoryName = item.category.isEmpty()
                    ? getString(R.string.grocery_uncategorized) : item.category;
            grouped.computeIfAbsent(categoryName, key -> new ArrayList<>()).add(item);
        }
        int shownHere = 0;
        for (Map.Entry<String, List<GroceryItem>> group : grouped.entrySet()) {
            String collapseKey = listType + "|" + group.getKey().toLowerCase(java.util.Locale.ENGLISH);
            boolean collapsed = collapseAllCategories || collapsedCategories.contains(collapseKey);
            TextView categoryHeader = text((collapsed ? "▸  " : "▾  ")
                    + group.getKey() + "  (" + group.getValue().size() + ")", 11, true);
            categoryHeader.setTextColor(Color.rgb(15, 108, 89));
            categoryHeader.setGravity(Gravity.CENTER_VERTICAL);
            categoryHeader.setPadding(dp(9), 0, dp(9), 0);
            categoryHeader.setBackground(roundedFill(Color.argb(220, 226, 244, 238), 8));
            LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            categoryParams.topMargin = shownHere == 0 ? 0 : dp(4);
            categoryParams.bottomMargin = dp(3);
            itemContainer.addView(categoryHeader, categoryParams);
            categoryHeader.setOnClickListener(v -> {
                boolean openThisCategory = collapseAllCategories
                        || collapsedCategories.contains(collapseKey);
                collapseAllCategories = false;
                collapsedCategories.clear();
                if (openThisCategory) {
                    // Accordion behavior: open the tapped category and keep every
                    // other category closed.
                    for (String categoryName : grouped.keySet()) {
                        String key = listType + "|" + categoryName.toLowerCase(
                                java.util.Locale.ENGLISH);
                        if (!key.equals(collapseKey)) collapsedCategories.add(key);
                    }
                } else {
                    // Tapping the already-open category closes it as well.
                    for (String categoryName : grouped.keySet()) {
                        collapsedCategories.add(listType + "|"
                                + categoryName.toLowerCase(java.util.Locale.ENGLISH));
                    }
                }
                refreshPanel();
            });
            if (collapsed) continue;
            for (GroceryItem item : group.getValue()) {
                CheckBox row = new CheckBox(this);
                String detail = (shownHere + 1) + ".  " + item.name;
                String badge = GroceryRecurrenceEngine.badgeLabel(
                        item, System.currentTimeMillis());
                if (!badge.isEmpty()) detail += "  ◆ " + badge;
                if (!item.quantity.isEmpty()) detail += "  •  " + item.quantity;
                if (!item.assignedMemberName.isEmpty()) detail += "  •  " + item.assignedMemberName;
                if (GroceryItem.PRIORITY_URGENT.equals(item.priority)) {
                    detail += "  •  " + getString(R.string.grocery_priority_urgent);
                } else if (GroceryItem.PRIORITY_HIGH.equals(item.priority)) {
                    detail += "  •  " + getString(R.string.grocery_priority_high);
                }
                boolean showLastPurchase = !GroceryItem.LIST_DAILY.equals(listType)
                        && item.purchasedAt > 0L;
                if (showLastPurchase) {
                    detail += "\n" + overlayLastPurchaseLabel(item.purchasedAt);
                    detail += "\n" + overlayNextDueLabel(item);
                }
                row.setText(detail);
                row.setTextSize(showLastPurchase ? 11.5f : 13f);
                row.setMaxLines(showLastPurchase ? 3 : 1);
                row.setTextColor(Color.rgb(36, 36, 36));
                row.setMinHeight(dp(38));
                row.setPadding(dp(6), 0, dp(6), 0);
                int rowFill = GroceryItem.PRIORITY_URGENT.equals(item.priority)
                        ? Color.argb(226, 255, 238, 240)
                        : GroceryItem.PRIORITY_HIGH.equals(item.priority)
                        ? Color.argb(226, 255, 247, 229)
                        : GroceryItem.LIST_MONTHLY.equals(listType)
                        ? Color.argb(226, 246, 241, 252)
                        : Color.argb(226, 237, 249, 243);
                row.setBackground(roundedFill(rowFill, 10));
                row.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        button.setChecked(false);
                        showInlinePurchaseEditor(item);
                    }
                });
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(showLastPurchase ? 70 : 40));
                rowParams.bottomMargin = dp(3);
                itemContainer.addView(row, rowParams);
                shownHere++;
            }
        }
        if (count == 0) {
            String emptyLabel;
            if (GroceryItem.LIST_THREE_MONTH.equals(listType)) {
                emptyLabel = "No Fortnightly items";
            } else if (GroceryItem.LIST_TWO_MONTH.equals(listType)) {
                emptyLabel = "No Weekly items";
            } else if (GroceryItem.LIST_MONTHLY.equals(listType)) {
                emptyLabel = getString(R.string.grocery_overlay_monthly_empty);
            } else {
                emptyLabel = getString(R.string.grocery_overlay_daily_empty);
            }
            TextView empty = text(emptyLabel, 11, false);
            empty.setTextColor(Color.rgb(110, 118, 128));
            empty.setPadding(dp(8), 0, 0, 0);
            itemContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        }
        return alreadyShown + shownHere;
    }

    private String overlayLastPurchaseLabel(long purchasedAt) {
        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0);
        today.set(java.util.Calendar.MILLISECOND, 0);

        java.util.Calendar purchaseDay = java.util.Calendar.getInstance();
        purchaseDay.setTimeInMillis(purchasedAt);
        purchaseDay.set(java.util.Calendar.HOUR_OF_DAY, 0);
        purchaseDay.set(java.util.Calendar.MINUTE, 0);
        purchaseDay.set(java.util.Calendar.SECOND, 0);
        purchaseDay.set(java.util.Calendar.MILLISECOND, 0);

        final long dayMillis = 24L * 60L * 60L * 1000L;
        long days = Math.max(0L, (today.getTimeInMillis()
                - purchaseDay.getTimeInMillis()) / dayMillis);
        String relative;
        if (days == 0L) {
            relative = "Today";
        } else if (days == 1L) {
            relative = "Yesterday";
        } else if (days < 30L) {
            relative = days + " days ago";
        } else {
            java.util.Calendar cursor = (java.util.Calendar) purchaseDay.clone();
            long months = 0L;
            while (true) {
                java.util.Calendar next = (java.util.Calendar) cursor.clone();
                next.add(java.util.Calendar.MONTH, 1);
                if (next.after(today)) break;
                cursor = next;
                months++;
            }
            long remainingDays = (today.getTimeInMillis()
                    - cursor.getTimeInMillis()) / dayMillis;
            String monthText = months == 1L ? "1 month" : months + " months";
            if (remainingDays == 0L) {
                relative = monthText + " ago";
            } else {
                relative = monthText + " "
                        + (remainingDays == 1L
                        ? "1 day" : remainingDays + " days") + " ago";
            }
        }
        CharSequence exactDate = android.text.format.DateFormat.format(
                "dd MMM yyyy", purchasedAt);
        return "Last purchase: " + relative + " • " + exactDate;
    }

    private String overlayNextDueLabel(@NonNull GroceryItem item) {
        long now = System.currentTimeMillis();
        long dueAt = GroceryRecurrenceEngine.nextDueAt(item);
        if (dueAt == Long.MAX_VALUE) return "";
        CharSequence date = android.text.format.DateFormat.format("dd MMM yyyy", dueAt);
        if (dueAt <= now) return "Due now • " + date;
        int days = GroceryRecurrenceEngine.daysUntilNextDue(item, now);
        return "Next due: " + (days == 1 ? "Tomorrow" : days + " days")
                + " • " + date;
    }

    private int priorityRank(GroceryItem item) {
        if (GroceryItem.PRIORITY_URGENT.equals(item.priority)) return 0;
        if (GroceryItem.PRIORITY_HIGH.equals(item.priority)) return 1;
        return 2;
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
                || overlayCategoryFilter.equalsIgnoreCase(item.category == null ? "" : item.category.trim());
        return categoryMatches && matchesOverlaySearch(item);
    }

    private void showInlinePurchaseEditor(GroceryItem item) {
        if (itemContainer == null) return;
        overlayInlinePurchaseEditorOpen = true;
        itemContainer.removeAllViews();
        setOverlayFormCollapsed(true);
        TextView title = text(getString(R.string.grocery_complete_title), 14, true);
        title.setTextColor(Color.rgb(15, 108, 89));
        itemContainer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        TextView itemName = text(item.name, 12, true);
        itemName.setPadding(dp(10), 0, dp(10), 0);
        itemName.setBackground(roundedFill(Color.argb(220, 226, 244, 238), 9));
        itemName.setElevation(dp(1));
        itemContainer.addView(itemName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        EditText price = compactInput(getString(R.string.grocery_overlay_price_hint));
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double savedPrice = item.actualCost > 0D ? item.actualCost : item.estimatedCost;
        if (savedPrice > 0D) price.setText(String.valueOf(savedPrice));
        EditText quantity = compactInput(getString(R.string.grocery_quantity_amount_hint));
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        String[] parts = item.quantity.trim().split("\\s+", 2);
        if (parts.length > 0) quantity.setText(parts[0]);
        String[] units = getResources().getStringArray(R.array.grocery_quantity_units);
        Spinner unit = compactSpinner(units);
        if (parts.length > 1) selectSpinner(unit, units, parts[1]);
        String[] categories = GroceryOptionCatalog.categoryLabelsWithAdd(this);
        Spinner category = compactSpinner(categories);
        selectSpinner(category, categories, item.category);
        enableCustomCategoryCreation(category, item.category);
        Spinner moneyAccount = moneyAccountSpinner();
        Spinner moneyCategory = moneyCategorySpinner();

        LinearLayout purchasePrimaryRow = new LinearLayout(this);
        purchasePrimaryRow.setOrientation(LinearLayout.HORIZONTAL);
        addCompactColumn(purchasePrimaryRow, labelledField(getString(R.string.grocery_actual_cost), price), false);
        addCompactColumn(purchasePrimaryRow, labelledField(getString(R.string.grocery_quantity_amount), quantity), true);
        addCompactColumn(purchasePrimaryRow, labelledField(getString(R.string.grocery_quantity_unit), unit), true);
        itemContainer.addView(purchasePrimaryRow);
        LinearLayout purchaseMoneyRow = new LinearLayout(this);
        purchaseMoneyRow.setOrientation(LinearLayout.HORIZONTAL);
        addCompactColumn(purchaseMoneyRow, labelledField(getString(R.string.grocery_category), category), false);
        addCompactColumn(purchaseMoneyRow, labelledField(getString(R.string.money_manager_paid_from), moneyAccount), true);
        addCompactColumn(purchaseMoneyRow, labelledField(getString(R.string.money_manager_expense_category), moneyCategory), true);
        itemContainer.addView(purchaseMoneyRow);
        attachLiveMoneyCatalog(moneyAccount, moneyCategory);

        String[] storeValues = storeChoices(item.storeName);
        Spinner store = compactSpinner(storeValues);
        String currentStore = item.storeName == null ? "" : item.storeName.trim();
        if (!currentStore.isEmpty()) {
            selectSpinner(store, storeValues, currentStore);
        }
        itemContainer.addView(labelledField(getString(R.string.grocery_store_name), store), fullEditorField());

        TextView historyInsight = text("", 10, false);
        historyInsight.setTextColor(Color.rgb(15, 108, 89));
        historyInsight.setPadding(dp(8), 0, dp(8), 0);
        historyInsight.setBackground(roundedFill(Color.argb(220, 226, 244, 238), 8));
        historyInsight.setVisibility(View.GONE);
        itemContainer.addView(historyInsight, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        repository.loadStoreComparison(item.name, item.quantity, (history, cheapest) -> {
            if (history == null || itemContainer == null) return;
            applyInlineHistory(history, cheapest, price, store, quantity,
                    unit, units, category, categories, historyInsight);
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = compactAction(getString(R.string.cancel), Color.rgb(91, 101, 114),
                Color.argb(220, 242, 244, 247));
        Button skip = compactAction(getString(R.string.grocery_skip_and_complete), Color.rgb(168, 93, 0),
                Color.argb(225, 255, 244, 222));
        Button save = compactAction(getString(R.string.save), Color.WHITE, Color.rgb(15, 108, 89));
        actions.addView(cancel, actionParams());
        actions.addView(skip, actionParams());
        actions.addView(save, actionParams());
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        actionsParams.topMargin = dp(8);
        itemContainer.addView(actions, actionsParams);
        cancel.setOnClickListener(v -> {
            overlayInlinePurchaseEditorOpen = false;
            refreshPanel();
        });
        skip.setOnClickListener(v -> {
            rememberPurchaseMoneySelections(item, moneyAccount, moneyCategory);
            overlayInlinePurchaseEditorOpen = false;
            repository.setPurchased(item, true, () -> showFloatingUndo(item));
        });
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
            item.quantity = amount.isEmpty() ? "" : amount + " " + unit.getSelectedItem();
            if (category.getSelectedItemPosition() <= 0) {
                android.widget.Toast.makeText(this, R.string.grocery_category_required,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            item.category = String.valueOf(category.getSelectedItem());
            Object selectedStore = store.getSelectedItem();
            item.storeName = selectedStore == null ? "" : selectedStore.toString().trim();
            rememberPurchaseMoneySelections(item, moneyAccount, moneyCategory);
            overlayInlinePurchaseEditorOpen = false;
            repository.setPurchased(item, true, () -> showFloatingUndo(item));
        });
    }

    private String[] storeChoices(@Nullable String currentStore) {
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String preset : GroceryOptionCatalog.storePresets(this)) {
            String clean = preset == null ? "" : preset.trim();
            if (clean.isEmpty()) continue;
            String key = clean.toLowerCase(java.util.Locale.ENGLISH);
            if (seen.add(key)) values.add(clean);
        }
        String current = currentStore == null ? "" : currentStore.trim();
        if (!current.isEmpty()) {
            String key = current.toLowerCase(java.util.Locale.ENGLISH);
            if (seen.add(key)) values.add(current);
        }
        return values.toArray(new String[0]);
    }

    private void bindStoreSpinner(Spinner spinner, @Nullable String selectedStore) {
        String[] values = storeChoices(selectedStore);
        spinner.setAdapter(compactSpinnerAdapter(values));
        spinner.setDropDownWidth(adaptivePopupWidth(values, 12.5f));
        String wanted = selectedStore == null ? "" : selectedStore.trim();
        if (wanted.isEmpty() && values.length > 0) wanted = values[0];
        if (!wanted.isEmpty()) selectSpinner(spinner, values, wanted);
    }

    private Spinner moneyAccountSpinner() {
        List<MoneyManagerMasterCatalogBridge.Choice> choices = moneyCatalog.accounts;
        String[] labels = new String[choices.size() + 1];
        labels[0] = getString(R.string.money_manager_choose_later);
        for (int i = 0; i < choices.size(); i++) labels[i + 1] = choices.get(i).label;
        Spinner spinner = compactSpinner(labels);
        String savedRef = MoneyManagerMasterCatalogBridge.groceryDefaultAccountRef(this);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).ref.equalsIgnoreCase(savedRef)) {
                spinner.setSelection(i + 1);
                break;
            }
        }
        return spinner;
    }

    private Spinner moneyCategorySpinner() {
        List<MoneyManagerMasterCatalogBridge.Choice> choices = moneyCatalog.expenseCategories;
        String[] labels = new String[choices.size() + 1];
        labels[0] = getString(R.string.money_manager_choose_later);
        for (int i = 0; i < choices.size(); i++) labels[i + 1] = choices.get(i).label;
        Spinner spinner = compactSpinner(labels);
        String savedRef = MoneyManagerMasterCatalogBridge.groceryDefaultCategoryRef(this);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).ref.equalsIgnoreCase(savedRef)) {
                spinner.setSelection(i + 1);
                break;
            }
        }
        return spinner;
    }

    private void attachLiveMoneyCatalog(Spinner account, Spinner category) {
        View.OnTouchListener refreshBeforeOpen = (view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                FamilyHubAppLockManager.noteTrustedOverlayInteraction();
                refreshMoneyCatalog(account, category, view::performClick);
                return true;
            }
            return true;
        };
        account.setOnTouchListener(refreshBeforeOpen);
        category.setOnTouchListener(refreshBeforeOpen);
        refreshMoneyCatalog(account, category, null);
    }

    private void refreshMoneyCatalog(Spinner account, Spinner category, @Nullable Runnable afterRefresh) {
        if (afterRefresh != null) pendingMoneyCatalogAction = afterRefresh;
        if (moneyCatalogRefreshing) return;
        moneyCatalogRefreshing = true;
        MoneyManagerMasterCatalogBridge.Catalog before = moneyCatalog;
        String accountRef = selectedMoneyRef(account, before.accounts);
        String categoryRef = selectedMoneyRef(category, before.expenseCategories);
        if (accountRef.isEmpty()) accountRef = MoneyManagerMasterCatalogBridge.groceryDefaultAccountRef(this);
        if (categoryRef.isEmpty()) categoryRef = MoneyManagerMasterCatalogBridge.groceryDefaultCategoryRef(this);
        final String wantedAccountRef = accountRef;
        final String wantedCategoryRef = categoryRef;
        new Thread(() -> {
            MoneyManagerMasterCatalogBridge.Catalog fresh = MoneyManagerMasterCatalogBridge.load(this);
            new android.os.Handler(getMainLooper()).post(() -> {
                moneyCatalogRefreshing = false;
                if (panelView != null && fresh.available) {
                    moneyCatalog = fresh;
                    bindMoneySpinner(account, fresh.accounts, wantedAccountRef);
                    bindMoneySpinner(category, fresh.expenseCategories, wantedCategoryRef);
                }
                Runnable pending = pendingMoneyCatalogAction;
                pendingMoneyCatalogAction = null;
                if (panelView != null && pending != null) pending.run();
            });
        }, "GroceryMoneyCatalogRefresh").start();
    }

    private String selectedMoneyRef(Spinner spinner,
                                    List<MoneyManagerMasterCatalogBridge.Choice> choices) {
        int index = spinner.getSelectedItemPosition() - 1;
        return index >= 0 && index < choices.size() ? choices.get(index).ref : "";
    }

    private void bindMoneySpinner(Spinner spinner,
                                  List<MoneyManagerMasterCatalogBridge.Choice> choices,
                                  String selectedRef) {
        String[] labels = new String[choices.size() + 1];
        labels[0] = getString(R.string.money_manager_choose_later);
        for (int i = 0; i < choices.size(); i++) labels[i + 1] = choices.get(i).label;
        spinner.setAdapter(compactSpinnerAdapter(labels));
        spinner.setDropDownWidth(adaptivePopupWidth(labels, 12.5f));
        spinner.setSelection(0);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).ref.equalsIgnoreCase(selectedRef)) {
                spinner.setSelection(i + 1);
                break;
            }
        }
    }

    private void rememberMoneyDefaults(Spinner account, Spinner category) {
        int accountIndex = account.getSelectedItemPosition() - 1;
        int categoryIndex = category.getSelectedItemPosition() - 1;
        String accountRef = accountIndex >= 0 && accountIndex < moneyCatalog.accounts.size()
                ? moneyCatalog.accounts.get(accountIndex).ref : "";
        String categoryRef = categoryIndex >= 0 && categoryIndex < moneyCatalog.expenseCategories.size()
                ? moneyCatalog.expenseCategories.get(categoryIndex).ref : "";
        MoneyManagerMasterCatalogBridge.rememberGroceryDefaultAccount(this, accountRef);
        MoneyManagerMasterCatalogBridge.rememberGroceryDefaultCategory(this, categoryRef);
    }

    private void rememberPurchaseMoneySelections(GroceryItem item, Spinner account, Spinner category) {
        int accountIndex = account.getSelectedItemPosition() - 1;
        int categoryIndex = category.getSelectedItemPosition() - 1;
        String accountRef = accountIndex >= 0 && accountIndex < moneyCatalog.accounts.size()
                ? moneyCatalog.accounts.get(accountIndex).ref : "";
        String categoryRef = categoryIndex >= 0 && categoryIndex < moneyCatalog.expenseCategories.size()
                ? moneyCatalog.expenseCategories.get(categoryIndex).ref : "";
        GroceryMoneyManagerBridge.rememberNextPurchaseSelections(this, item, accountRef, categoryRef);
    }

    private void showFloatingUndo(GroceryItem item) {
        if (itemContainer == null) return;
        overlayInlinePurchaseEditorOpen = false;
        itemContainer.removeAllViews();
        TextView message = text(getString(R.string.grocery_purchase_completed, item.name), 12, true);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.rgb(15, 108, 89));
        itemContainer.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        Button undo = compactAction(getString(R.string.grocery_undo), Color.WHITE, Color.rgb(15, 108, 89));
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        undoParams.leftMargin = dp(24);
        undoParams.rightMargin = dp(24);
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

    private void applySmartSuggestion(GroceryItem suggestion, EditText name,
                                      EditText quantity, Spinner unit, String[] units,
                                      Spinner category, String[] categories, EditText price) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();
        name.setError(null);
        price.setError(null);
        name.setText(suggestion.name);
        name.setSelection(name.length());
        String cleanQuantity = suggestion.quantity == null ? "" : suggestion.quantity.trim();
        if (!cleanQuantity.isEmpty()) {
            String[] parts = cleanQuantity.split("\\s+", 2);
            if (parts.length > 0) quantity.setText(parts[0]);
            if (parts.length > 1) selectSpinner(unit, units, parts[1]);
        }
        if (suggestion.category != null && !suggestion.category.trim().isEmpty()) {
            selectSpinner(category, categories, suggestion.category.trim());
        }
        double suggestedPrice = suggestion.actualCost > 0D
                ? suggestion.actualCost : suggestion.estimatedCost;
        if (suggestedPrice > 0D) price.setText(String.valueOf(suggestedPrice));
    }

    private void clearSmartFilledForm(EditText name, EditText quantity, Spinner unit,
                                      Spinner category, EditText price, Spinner quickPick) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();
        name.setError(null);
        price.setError(null);
        name.setText("");
        quantity.setText("");
        price.setText("");
        if (unit.getCount() > 0) unit.setSelection(0);
        if (category.getCount() > 0) category.setSelection(0);
        if (quickPick.getCount() > 0) quickPick.setSelection(0);
        name.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
    }

    private List<GroceryItem> rankSmartSuggestions(List<GroceryItem> allItems) {
        Map<String, GroceryItem> bestByName = new LinkedHashMap<>();
        for (GroceryItem item : allItems) {
            if (item == null || item.name == null || item.name.trim().isEmpty() || item.purchaseCount <= 0) continue;
            String key = item.name.trim().toLowerCase(java.util.Locale.ENGLISH);
            GroceryItem previous = bestByName.get(key);
            if (previous == null || item.purchaseCount > previous.purchaseCount
                    || (item.purchaseCount == previous.purchaseCount && item.purchasedAt > previous.purchasedAt)) {
                bestByName.put(key, item);
            }
        }
        List<GroceryItem> ranked = new ArrayList<>(bestByName.values());
        Map<String, Integer> categoryTotals = new java.util.HashMap<>();
        for (GroceryItem item : ranked) {
            String categoryName = smartSuggestionCategory(item);
            categoryTotals.put(categoryName, categoryTotals.getOrDefault(categoryName, 0)
                    + Math.max(0, item.purchaseCount));
        }
        ranked.sort((left, right) -> {
            String leftCategory = smartSuggestionCategory(left);
            String rightCategory = smartSuggestionCategory(right);
            int compareCategoryTotal = Integer.compare(categoryTotals.getOrDefault(rightCategory, 0),
                    categoryTotals.getOrDefault(leftCategory, 0));
            if (compareCategoryTotal != 0) return compareCategoryTotal;
            int compareCategoryName = leftCategory.compareToIgnoreCase(rightCategory);
            if (compareCategoryName != 0) return compareCategoryName;
            int compareCount = Integer.compare(right.purchaseCount, left.purchaseCount);
            if (compareCount != 0) return compareCount;
            int compareRecent = Long.compare(right.purchasedAt, left.purchasedAt);
            if (compareRecent != 0) return compareRecent;
            return left.name.compareToIgnoreCase(right.name);
        });
        return ranked;
    }

    private String smartSuggestionCategory(GroceryItem item) {
        if (item == null || item.category == null || item.category.trim().isEmpty()) {
            return getString(R.string.grocery_uncategorized);
        }
        return item.category.trim();
    }

    private void showSmartSuggestionOverflow(View anchor, List<GroceryItem> suggestions,
                                             EditText name, EditText quantity, Spinner unit,
                                             String[] units, Spinner category, String[] categories,
                                             EditText price) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int popupWidth = adaptiveSmartPopupWidth(suggestions);
        int categoryCount = 0;
        String lastCountedCategory = "";
        for (GroceryItem suggestion : suggestions) {
            String categoryName = smartSuggestionCategory(suggestion);
            if (!categoryName.equalsIgnoreCase(lastCountedCategory)) {
                categoryCount++;
                lastCountedCategory = categoryName;
            }
        }
        int desiredPopupHeight = dp(76) + suggestions.size() * dp(38) + categoryCount * dp(32);
        int maxPopupHeight = Math.min(dp(360), Math.round(screenHeight * 0.46f));
        int popupHeight = clamp(desiredPopupHeight, dp(180), maxPopupHeight);
        LinearLayout popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.VERTICAL);
        popupRoot.setPadding(dp(7), dp(7), dp(7), dp(7));
        popupRoot.setBackground(premiumDropdownBackground());
        popupRoot.setElevation(dp(10));
        TextView popupTitle = text("Smart suggestions", 14, true);
        popupTitle.setTextColor(Color.rgb(15, 90, 72));
        popupTitle.setPadding(dp(10), dp(4), dp(10), dp(2));
        popupRoot.addView(popupTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView popupHint = text("Category-wise • most purchased first", 11, false);
        popupHint.setTextColor(Color.rgb(94, 104, 114));
        popupHint.setPadding(dp(10), 0, dp(10), dp(5));
        popupRoot.addView(popupHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout groupedList = new LinearLayout(this);
        groupedList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(groupedList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        popupRoot.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final android.widget.PopupWindow popup = new android.widget.PopupWindow(this);
        popup.setContentView(popupRoot);
        popup.setWidth(Math.min(popupWidth, screenWidth - dp(24)));
        popup.setHeight(popupHeight);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        Map<String, List<GroceryItem>> categoryGroups = new LinkedHashMap<>();
        for (GroceryItem suggestion : suggestions) {
            String categoryName = smartSuggestionCategory(suggestion);
            categoryGroups.computeIfAbsent(categoryName, key -> new ArrayList<>()).add(suggestion);
        }

        final LinearLayout[] openItemsContainer = {null};
        final TextView[] openArrow = {null};

        for (Map.Entry<String, List<GroceryItem>> categoryEntry : categoryGroups.entrySet()) {
            String categoryName = categoryEntry.getKey();
            List<GroceryItem> categoryItems = categoryEntry.getValue();
            int categoryPurchaseTotal = 0;
            for (GroceryItem candidate : categoryItems) {
                categoryPurchaseTotal += Math.max(0, candidate.purchaseCount);
            }

            LinearLayout categoryBlock = new LinearLayout(this);
            categoryBlock.setOrientation(LinearLayout.VERTICAL);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(8), dp(4), dp(8), dp(4));
            header.setBackground(roundedFill(Color.argb(235, 225, 246, 238), 9));

            TextView arrow = text("▸", 13, true);
            arrow.setTextColor(Color.rgb(12, 99, 75));
            arrow.setGravity(Gravity.CENTER);
            header.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(30)));

            TextView headerText = text(categoryName + "  •  " + categoryItems.size() + "  •  "
                    + categoryPurchaseTotal + "×", 12, true);
            headerText.setTextColor(Color.rgb(12, 99, 75));
            headerText.setSingleLine(true);
            headerText.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(headerText, new LinearLayout.LayoutParams(0, dp(30), 1f));

            LinearLayout itemsContainer = new LinearLayout(this);
            itemsContainer.setOrientation(LinearLayout.VERTICAL);
            itemsContainer.setVisibility(View.GONE);
            itemsContainer.setPadding(dp(2), dp(3), dp(2), 0);

            for (GroceryItem suggestion : categoryItems) {
                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(10), dp(3), dp(8), dp(3));
                itemRow.setBackground(rounded(Color.argb(247, 255, 255, 255),
                        Color.argb(125, 212, 222, 226), 9));

                TextView itemText = text(suggestion.name, 13, false);
                itemText.setTextColor(Color.rgb(38, 45, 50));
                itemText.setSingleLine(true);
                itemText.setEllipsize(TextUtils.TruncateAt.END);
                itemText.setPadding(0, dp(1), dp(8), dp(1));
                itemRow.addView(itemText, new LinearLayout.LayoutParams(0, dp(34), 1f));

                TextView countBadge = text(suggestion.purchaseCount + "×", 11, true);
                countBadge.setTextColor(Color.rgb(15, 108, 89));
                countBadge.setGravity(Gravity.CENTER);
                countBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
                countBadge.setBackground(roundedFill(Color.argb(230, 235, 248, 241), 12));
                itemRow.addView(countBadge, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                itemRow.setOnClickListener(v -> {
                    applySmartSuggestion(suggestion, name, quantity, unit, units,
                            category, categories, price);
                    popup.dismiss();
                });

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                rowParams.bottomMargin = dp(3);
                itemsContainer.addView(itemRow, rowParams);
            }

            header.setOnClickListener(v -> {
                boolean shouldOpen = itemsContainer.getVisibility() != View.VISIBLE;

                if (openItemsContainer[0] != null && openItemsContainer[0] != itemsContainer) {
                    openItemsContainer[0].setVisibility(View.GONE);
                    if (openArrow[0] != null) openArrow[0].setText("▸");
                }

                if (shouldOpen) {
                    itemsContainer.setVisibility(View.VISIBLE);
                    arrow.setText("▾");
                    openItemsContainer[0] = itemsContainer;
                    openArrow[0] = arrow;
                } else {
                    itemsContainer.setVisibility(View.GONE);
                    arrow.setText("▸");
                    if (openItemsContainer[0] == itemsContainer) {
                        openItemsContainer[0] = null;
                        openArrow[0] = null;
                    }
                }
            });

            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            headerParams.topMargin = groupedList.getChildCount() == 0 ? 0 : dp(5);
            headerParams.bottomMargin = dp(2);
            categoryBlock.addView(header, headerParams);
            categoryBlock.addView(itemsContainer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            groupedList.addView(categoryBlock, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        int xOffset = -(popup.getWidth() - Math.max(anchor.getWidth(), dp(40)));
        popup.showAsDropDown(anchor, xOffset, dp(5));
    }

    private void applyInlineHistory(GroceryPurchase history, GroceryPurchase cheapest,
                                    EditText price, Spinner store, EditText quantity,
                                    Spinner unit, String[] units, Spinner category,
                                    String[] categories, TextView insight) {
        String[] previousQuantity = history.quantity.trim().split("\\s+", 2);
        if (previousQuantity.length > 0) quantity.setText(previousQuantity[0]);
        if (previousQuantity.length > 1) selectSpinner(unit, units, previousQuantity[1]);
        selectSpinner(category, categories, history.category);
        if (history.actualCost > 0D) price.setText(String.valueOf(history.actualCost));
        if (!history.storeName.isEmpty()) bindStoreSpinner(store, history.storeName);
        insight.setVisibility(View.VISIBLE);
        insight.setText(inlineComparisonText(history, cheapest, history.actualCost));
        price.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                double current = 0D;
                try { current = Double.parseDouble(s == null ? "" : s.toString().trim()); }
                catch (NumberFormatException ignored) { }
                insight.setText(inlineComparisonText(history, cheapest, current));
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private String inlineComparisonText(GroceryPurchase history, GroceryPurchase cheapest,
                                        double current) {
        StringBuilder value = new StringBuilder(getString(R.string.grocery_previous_purchase,
                history.quantity.isEmpty() ? getString(R.string.grocery_quantity_not_added) : history.quantity,
                history.category.isEmpty() ? getString(R.string.grocery_uncategorized) : history.category,
                String.format(java.util.Locale.ENGLISH, "₹%.2f", history.actualCost)));
        if (!history.storeName.isEmpty()) value.append('\n').append(
                getString(R.string.grocery_previous_store, history.storeName));
        if (cheapest != null && !cheapest.storeName.isEmpty()) {
            value.append('\n').append(getString(R.string.grocery_cheapest_store,
                    cheapest.storeName, cheapest.actualCost));
            if (current > cheapest.actualCost) value.append('\n').append(
                    getString(R.string.grocery_possible_saving, current - cheapest.actualCost));
        }
        if (current > 0D && history.actualCost > 0D) {
            double percent = (current - history.actualCost) / history.actualCost * 100D;
            value.append('\n').append(Math.abs(percent) < 0.05D
                    ? getString(R.string.grocery_price_same)
                    : getString(R.string.grocery_price_change, history.actualCost, current, percent));
        }
        return value.toString();
    }

    private LinearLayout.LayoutParams fullEditorField() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66));
        params.topMargin = dp(4);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.setMarginStart(dp(4));
        return params;
    }

    private void rememberStandardOverlayMode(String mode) {
        String resolved = MODE_MINI.equals(mode) ? MODE_MINI : MODE_NORMAL;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_STANDARD_MODE, resolved)
                .putString(KEY_OVERLAY_MODE, resolved).apply();
    }

    private void startOverlayConnectionStatus() {
        stopOverlayConnectionStatus();
        if (overlayLiveStatus == null) return;
        overlayConnectionReference = FirebaseDatabase.getInstance()
                .getReference(".info/connected");
        overlayConnectionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                TextView status = overlayLiveStatus;
                if (status == null) return;
                status.setText(connected ? "● Live" : "● Offline");
                status.setTextColor(connected
                        ? Color.rgb(15, 122, 90) : Color.rgb(176, 98, 34));
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                TextView status = overlayLiveStatus;
                if (status != null) {
                    status.setText("● Offline");
                    status.setTextColor(Color.rgb(176, 98, 34));
                }
            }
        };
        overlayConnectionReference.addValueEventListener(overlayConnectionListener);
    }

    private void stopOverlayConnectionStatus() {
        if (overlayConnectionReference != null && overlayConnectionListener != null) {
            overlayConnectionReference.removeEventListener(overlayConnectionListener);
        }
        overlayConnectionReference = null;
        overlayConnectionListener = null;
    }

    private void updateOverlayShoppingModeUi(Button shoppingDropdown, Button screenOn,
                                             TextView shoppingSubtitle) {
        if (shoppingDropdown != null) {
            shoppingDropdown.setText("Shopping Mode  ▾");
            shoppingDropdown.setTextColor(overlayShoppingMode ? Color.WHITE : Color.rgb(15, 108, 89));
            shoppingDropdown.setBackground(roundedFill(overlayShoppingMode
                    ? Color.rgb(15, 108, 89) : Color.argb(230, 226, 244, 238), 16));
        }
        if (screenOn != null) {
            screenOn.setVisibility(overlayShoppingMode ? View.VISIBLE : View.GONE);
            screenOn.setText(overlayShoppingScreenOn ? "Screen On ✓" : "Screen On");
            screenOn.setTextColor(overlayShoppingScreenOn ? Color.WHITE : Color.rgb(15, 108, 189));
            screenOn.setBackground(roundedFill(overlayShoppingScreenOn
                    ? Color.rgb(15, 108, 189) : Color.argb(220, 232, 243, 252), 14));
        }
        if (shoppingSubtitle != null) {
            if (overlayShoppingMode) {
                shoppingSubtitle.setText("Shopping • "
                        + shoppingSelectionLabel(overlayShoppingSelection));
            } else {
                shoppingSubtitle.setText(overlayFormCollapsed ? "Mini" : "Normal");
            }
        }
    }

    private String shoppingSelectionLabel(String selection) {
        if (GroceryItem.LIST_DAILY.equals(selection)) return "Daily";
        if (GroceryItem.LIST_TWO_MONTH.equals(selection)) return "Weekly";
        if (GroceryItem.LIST_THREE_MONTH.equals(selection)) return "Fortnightly";
        if (GroceryItem.LIST_MONTHLY.equals(selection)) return "Monthly";
        return "All";
    }

    private void applyOverlayScreenOn(boolean keepOn) {
        if (panelView != null) panelView.setKeepScreenOn(keepOn);
    }

    private boolean handlePanelCornerResizeGesture(View panel, MotionEvent event) {
        if (event == null || panelParams == null || panelView == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int cornerTouch = dp(24);
            float localX = event.getX();
            float localY = event.getY();
            boolean left = localX <= cornerTouch;
            boolean right = localX >= panel.getWidth() - cornerTouch;
            boolean top = localY <= cornerTouch;
            boolean bottom = localY >= panel.getHeight() - cornerTouch;
            if (!(left || right) || !(top || bottom)) return false;
            cornerResizeHorizontalDirection = left ? -1 : 1;
            cornerResizeVerticalDirection = top ? -1 : 1;
            cornerResizeStartWidth = panelParams.width;
            cornerResizeStartHeight = panelParams.height;
            cornerResizeStartX = panelParams.x;
            cornerResizeStartY = panelParams.y;
            cornerResizeDownRawX = event.getRawX();
            cornerResizeDownRawY = event.getRawY();
            cornerResizeActive = true;
            return true;
        }
        if (!cornerResizeActive) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int dx = Math.round(event.getRawX() - cornerResizeDownRawX);
            int dy = Math.round(event.getRawY() - cornerResizeDownRawY);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int maxWidth = screenWidth - dp(24);
            int maxHeight = screenHeight - dp(98);
            int minWidth = Math.min(maxWidth, dp(280));
            int minHeight = Math.min(maxHeight, dp(360));
            int newWidth = cornerResizeHorizontalDirection < 0
                    ? clamp(cornerResizeStartWidth - dx, minWidth, maxWidth)
                    : clamp(cornerResizeStartWidth + dx, minWidth, maxWidth);
            int newHeight = cornerResizeVerticalDirection < 0
                    ? clamp(cornerResizeStartHeight - dy, minHeight, maxHeight)
                    : clamp(cornerResizeStartHeight + dy, minHeight, maxHeight);
            int newX = cornerResizeHorizontalDirection < 0
                    ? cornerResizeStartX + (cornerResizeStartWidth - newWidth) : cornerResizeStartX;
            int newY = cornerResizeVerticalDirection < 0
                    ? cornerResizeStartY + (cornerResizeStartHeight - newHeight) : cornerResizeStartY;
            panelParams.width = newWidth;
            panelParams.height = newHeight;
            panelParams.x = clamp(newX, 0, Math.max(0, screenWidth - newWidth));
            panelParams.y = clamp(newY, 0, Math.max(0, screenHeight - newHeight));
            windowManager.updateViewLayout(panelView, panelParams);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("panel_width", panelParams.width)
                    .putInt("panel_height", panelParams.height)
                    .putInt("panel_x", panelParams.x)
                    .putInt("panel_y", panelParams.y).apply();
            cornerResizeActive = false;
            return true;
        }
        return true;
    }

    private void drawResizeCornerGuides(Canvas canvas, int width, int height, Paint paint) {
        if (canvas == null || width <= 0 || height <= 0 || paint == null) return;
        float edge = dp(3);
        float length = dp(11);
        canvas.drawLine(edge, edge, edge + length, edge, paint);
        canvas.drawLine(edge, edge, edge, edge + length, paint);
        canvas.drawLine(width - edge, edge, width - edge - length, edge, paint);
        canvas.drawLine(width - edge, edge, width - edge, edge + length, paint);
        canvas.drawLine(edge, height - edge, edge + length, height - edge, paint);
        canvas.drawLine(edge, height - edge, edge, height - edge - length, paint);
        canvas.drawLine(width - edge, height - edge, width - edge - length, height - edge, paint);
        canvas.drawLine(width - edge, height - edge, width - edge, height - edge - length, paint);
    }

    private void suspendOverlayForVoice() {
        if (voicePanelDetached) return;
        voiceStripWasVisible = stripView != null
                && stripView.getVisibility() == View.VISIBLE;
        if (stripView != null) stripView.setVisibility(View.GONE);
        if (panelView != null && panelView.isAttachedToWindow()) {
            windowManager.removeView(panelView);
            voicePanelDetached = true;
        }
    }

    private void resumeOverlayAfterVoice() {
        if (stripView != null && voiceStripWasVisible) {
            stripView.setVisibility(View.VISIBLE);
        }
        voiceStripWasVisible = false;
        if (!voicePanelDetached || panelView == null || panelParams == null) {
            voicePanelDetached = false;
            return;
        }
        try {
            windowManager.addView(panelView, panelParams);
        } catch (RuntimeException ignored) {
            panelView = null;
            itemContainer = null;
            overlayFormDetails = null;
            overlayItemScroll = null;
            overlayFormToggle = null;
            panelParams = null;
        }
        voicePanelDetached = false;
    }

    private void closePanel() {
        stopOverlayConnectionStatus();
        overlayLiveStatus = null;
        if (panelView != null) {
            applyOverlayScreenOn(false);
            if (panelView.isAttachedToWindow()) windowManager.removeView(panelView);
            panelView = null;
            itemContainer = null;
            overlayFormDetails = null;
            overlayItemScroll = null;
            overlayFormToggle = null;
            panelParams = null;
            pendingMoneyCatalogAction = null;
            overlayFormCollapsed = false;
            overlayShoppingMode = false;
            overlayShoppingScreenOn = false;
            overlayShoppingSelection = SHOPPING_ALL;
            overlayInlinePurchaseEditorOpen = false;
            cornerResizeActive = false;
            voicePanelDetached = false;
        }
    }

    private void setOverlayFormCollapsed(boolean collapsed) {
        if (overlayFormDetails == null || overlayOptionalDetails == null
                || overlayItemScroll == null || overlayFormToggle == null) return;
        overlayFormCollapsed = collapsed;
        overlayFormDetails.setVisibility(View.VISIBLE);
        overlayOptionalDetails.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        overlayFormToggle.setText(collapsed ? "More details  ▾" : "Less details  ▴");
        overlayFormToggle.setContentDescription(collapsed
                ? "Show more Grocery details"
                : "Hide extra Grocery details");
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) overlayItemScroll.getLayoutParams();
        params.height = 0;
        params.weight = 1f;
        overlayItemScroll.setLayoutParams(params);
    }

    private WindowManager.LayoutParams overlayParams(int width, int height, boolean passive) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (passive) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        return new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(36, 36, 36));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private EditText compactInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(11.5f);
        input.setHint(hint);
        input.setPadding(dp(9), 0, dp(9), 0);
        input.setBackground(glassFieldBackground());
        input.setElevation(dp(1));
        return input;
    }

    private AutoCompleteTextView compactAutoComplete(String hint, String[] values) {
        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setSingleLine(true);
        input.setTextSize(11.5f);
        input.setHint(hint);
        input.setPadding(dp(9), 0, dp(9), 0);
        input.setBackground(glassFieldBackground());
        input.setElevation(dp(1));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return premiumDropDownText(getItem(position));
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return premiumDropDownText(getItem(position));
            }
        };
        input.setAdapter(adapter);
        input.setThreshold(0);
        input.setDropDownWidth(adaptivePopupWidth(values, 12.5f));
        input.setDropDownBackgroundDrawable(premiumDropdownBackground());
        input.setOnClickListener(v -> input.showDropDown());
        return input;
    }

    private ArrayAdapter<String> compactSpinnerAdapter(String[] values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return spinnerSelectedText(getItem(position));
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return premiumDropDownText(getItem(position));
            }
        };
    }

    private TextView spinnerSelectedText(@Nullable String value) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextColor(Color.rgb(36, 36, 36));
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setIncludeFontPadding(false);
        text.setMaxLines(2);
        text.setSingleLine(false);
        text.setHorizontallyScrolling(false);
        text.setEllipsize(null);
        text.setPadding(dp(7), dp(2), dp(18), dp(2));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                text, 9, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        return text;
    }

    private TextView premiumDropDownText(@Nullable String value) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextSize(12.5f);
        text.setTextColor(Color.rgb(31, 42, 49));
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setHorizontallyScrolling(false);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(dp(12), dp(2), dp(12), dp(2));
        text.setMinHeight(dp(36));
        text.setMinimumHeight(dp(36));
        text.setBackground(premiumDropdownRowBackground());
        text.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        return text;
    }

    private void enableCustomCategoryCreation(
            @NonNull Spinner spinner, @NonNull String initialSelection) {
        final int[] previous = {Math.max(0, spinner.getSelectedItemPosition())};
        spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int position, long id) {
                Object selected = spinner.getSelectedItem();
                if (!GroceryOptionCatalog.ADD_CATEGORY_LABEL.equals(
                        selected == null ? "" : selected.toString())) {
                    previous[0] = position;
                    return;
                }
                EditText input = compactInput("New category name");
                input.setSingleLine(true);
                android.app.AlertDialog categoryDialog =
                        new android.app.AlertDialog.Builder(
                                GroceryOverlayService.this,
                                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle("Add grocery category")
                        .setView(input)
                        .setNegativeButton(R.string.cancel, (dialog, which) ->
                                spinner.setSelection(previous[0]))
                        .setPositiveButton("Add", (dialog, which) -> {
                            String value = input.getText() == null ? ""
                                    : input.getText().toString().trim();
                            if (!GroceryOptionCatalog.addCustomCategory(
                                    GroceryOverlayService.this, value)) {
                                spinner.setSelection(previous[0]);
                                return;
                            }
                            String[] updated =
                                    GroceryOptionCatalog.categoryLabelsWithAdd(
                                            GroceryOverlayService.this);
                            spinner.setAdapter(compactSpinnerAdapter(updated));
                            spinner.setDropDownWidth(adaptivePopupWidth(updated, 12.5f));
                            selectSpinner(spinner, updated, value);
                            previous[0] = spinner.getSelectedItemPosition();
                        }).create();
                if (categoryDialog.getWindow() != null) {
                    categoryDialog.getWindow().setType(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                categoryDialog.setOnCancelListener(dialog ->
                        spinner.setSelection(previous[0]));
                categoryDialog.show();
            }

            @Override public void onNothingSelected(
                    android.widget.AdapterView<?> parent) { }
        });
        if (!initialSelection.trim().isEmpty()) {
            selectSpinner(spinner,
                    GroceryOptionCatalog.categoryLabelsWithAdd(this),
                    initialSelection);
            previous[0] = spinner.getSelectedItemPosition();
        }
    }

    private Spinner compactSpinner(String[] values) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setAdapter(compactSpinnerAdapter(values));
        spinner.setBackground(glassFieldBackground());
        spinner.setElevation(dp(1));
        spinner.setDropDownWidth(adaptivePopupWidth(values, 12.5f));
        spinner.setPopupBackgroundDrawable(premiumDropdownBackground());
        spinner.setDropDownVerticalOffset(dp(4));
        return spinner;
    }

    private int adaptivePopupWidth(String[] values, float textSizeSp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                textSizeSp, getResources().getDisplayMetrics()));
        float longest = 0f;
        if (values != null) {
            for (String value : values) {
                if (value != null) longest = Math.max(longest, paint.measureText(value.trim()));
            }
        }
        int desired = Math.round(longest) + dp(42);
        int max = getResources().getDisplayMetrics().widthPixels - dp(40);
        return clamp(desired, dp(120), Math.max(dp(120), max));
    }

    private int adaptiveSmartPopupWidth(List<GroceryItem> suggestions) {
        Paint normal = new Paint(Paint.ANTI_ALIAS_FLAG);
        normal.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                13f, getResources().getDisplayMetrics()));
        Paint bold = new Paint(normal);
        bold.setFakeBoldText(true);
        float longest = normal.measureText("Category-wise • most purchased first");
        for (GroceryItem suggestion : suggestions) {
            if (suggestion == null) continue;
            longest = Math.max(longest,
                    normal.measureText(suggestion.name == null ? "" : suggestion.name) + dp(58));
            String category = smartSuggestionCategory(suggestion);
            longest = Math.max(longest, bold.measureText(category + " • 99 • 999×"));
        }
        int desired = Math.round(longest) + dp(34);
        int screenMax = getResources().getDisplayMetrics().widthPixels - dp(28);
        int practicalMax = Math.min(screenMax, dp(360));
        return clamp(desired, dp(220), Math.max(dp(220), practicalMax));
    }

    private LinearLayout.LayoutParams weightedField() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1f);
        params.topMargin = dp(4);
        return params;
    }

    private void addCompactColumn(LinearLayout row, View field, boolean withStartMargin) {
        LinearLayout.LayoutParams params = weightedField();
        if (withStartMargin) params.setMarginStart(dp(6));
        row.addView(field, params);
    }

    private LinearLayout labelledField(String label, View field) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView fieldLabel = text(label, 9, true);
        fieldLabel.setTextColor(Color.rgb(84, 93, 105));
        fieldLabel.setSingleLine(true);
        fieldLabel.setEllipsize(null);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                fieldLabel, 7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        block.addView(fieldLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        block.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
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

    private GradientDrawable glassFieldBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(235, 255, 255, 255), Color.argb(218, 244, 249, 252)});
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), Color.argb(190, 204, 214, 222));
        return drawable;
    }

    private GradientDrawable premiumDropdownBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(250, 255, 255, 255), Color.argb(246, 243, 249, 252)});
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.argb(210, 199, 211, 221));
        drawable.setPadding(dp(5), dp(5), dp(5), dp(5));
        return drawable;
    }

    private GradientDrawable premiumDropdownRowBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(246, 255, 255, 255), Color.argb(238, 239, 248, 246)});
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), Color.argb(130, 213, 224, 228));
        return drawable;
    }

    private GradientDrawable glassTopHighlight() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(185, 255, 255, 255), Color.argb(55, 255, 255, 255), Color.TRANSPARENT});
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private GradientDrawable panelGradient() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(252, 239, 250, 243), Color.argb(250, 255, 244, 245),
                        Color.argb(252, 238, 246, 253)});
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), Color.argb(225, 199, 213, 220));
        return drawable;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BackAwareEditText extends androidx.appcompat.widget.AppCompatEditText {
        BackAwareEditText() { super(GroceryOverlayService.this); }
        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.grocery_overlay_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent stop = new Intent(this, GroceryOverlayService.class);
        stop.setAction(ACTION_STOP);
        android.app.PendingIntent stopIntent = android.app.PendingIntent.getService(this, 0, stop,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_grocery)
                .setContentTitle(getString(R.string.grocery_overlay_title))
                .setContentText(getString(R.string.grocery_overlay_notification))
                .setOngoing(true)
                .addAction(0, getString(R.string.grocery_floating_disable), stopIntent)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (repository != null) repository.stopRealtimeSync();
        closePanel();
        if (stripView != null) {
            windowManager.removeView(stripView);
            stripView = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
        unregisterReceiver(voiceResultReceiver);
        unregisterReceiver(screenStateReceiver);
        super.onDestroy();
    }
}
