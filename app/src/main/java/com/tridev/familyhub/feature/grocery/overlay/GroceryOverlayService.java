package com.tridev.familyhub.feature.grocery.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;

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

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).apply();
        repository = new GroceryRepository(this);
        repository.startRealtimeSync(this::refreshPanel);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
        strip.setText("＋  Grocery");
        strip.setTextColor(Color.rgb(11, 79, 59));
        strip.setTextSize(13f);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(dp(14), 0, dp(14), 0);
        strip.setBackground(rounded(Color.rgb(231, 246, 240),
                Color.rgb(15, 122, 90), 22));
        strip.setElevation(dp(8));
        strip.setAlpha(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat("alpha", 0.88f));
        stripView = strip;

        stripParams = overlayParams(dp(132), dp(46), true);
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
        root.setBackground(rounded(Color.WHITE, Color.rgb(214, 220, 227), 20));
        root.setElevation(dp(12));

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

        LinearLayout quickAdd = new LinearLayout(this);
        quickAdd.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13f);
        input.setHint(R.string.grocery_overlay_add_hint);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(rounded(Color.rgb(248, 249, 250),
                Color.rgb(214, 220, 227), 12));
        quickAdd.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));
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

        add.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError(getString(R.string.grocery_name_required));
                return;
            }
            GroceryItem item = new GroceryItem();
            item.name = name;
            item.listType = GroceryItem.LIST_DAILY;
            repository.save(item, () -> {
                input.setText("");
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
        int shown = 0;
        for (GroceryItem item : items) {
            if (item.isPurchased || shown >= 6) {
                continue;
            }
            CheckBox row = new CheckBox(this);
            String detail = item.quantity.isEmpty()
                    ? item.name : item.name + "  •  " + item.quantity;
            if (!item.assignedMemberName.isEmpty()) {
                detail += "  •  " + item.assignedMemberName;
            }
            row.setText(detail);
            row.setTextSize(13f);
            row.setTextColor(Color.rgb(36, 36, 36));
            row.setMinHeight(dp(42));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    repository.setPurchased(item, true, this::refreshPanel);
                }
            });
            itemContainer.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(getString(R.string.grocery_widget_empty), 12, false);
            empty.setGravity(Gravity.CENTER);
            itemContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        }
    }

    private void closePanel() {
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

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        super.onDestroy();
    }
}
