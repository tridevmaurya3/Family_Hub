package com.tridev.familyhub.feature.notes.overlay;

import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.google.android.material.button.MaterialButton;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.FamilyHubAppLockManager;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.data.repository.NotesRepository;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.notes.NotesEditor;
import com.tridev.familyhub.feature.notes.NotesWorkspaceView;

/** Notes-only overlay; the universal hub owns the floating launcher button. */
public final class NotesOverlayService extends Service {
    public static final String ACTION_OPEN_PANEL = "com.tridev.familyhub.action.OPEN_NOTES_PANEL";
    private static final String CHANNEL = "family_notes_overlay";
    private static final String PREFS = "notes_overlay_geometry";
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private FrameLayout panel, body;
    private Context themed;
    private NotesRepository repository;
    private NotesWorkspaceView workspace;
    private int inputActivities;
    private boolean collapsed;
    private int expandedHeight;
    private final android.app.Application.ActivityLifecycleCallbacks inputLifecycle =
            new android.app.Application.ActivityLifecycleCallbacks() {
        private boolean inputActivity(android.app.Activity activity) {
            return activity instanceof com.tridev.familyhub.core.ui.FormVoiceCaptureActivity
                    || activity instanceof NotesNotificationPermissionActivity;
        }
        public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {
            if (inputActivity(activity)) {
                inputActivities++;
                if (panel != null) panel.setVisibility(View.INVISIBLE);
            }
        }
        public void onActivityDestroyed(android.app.Activity activity) {
            if (inputActivity(activity)) {
                inputActivities = Math.max(0, inputActivities - 1);
                if (inputActivities == 0 && panel != null) panel.setVisibility(View.VISIBLE);
            }
        }
        public void onActivityStarted(android.app.Activity activity) { }
        public void onActivityResumed(android.app.Activity activity) { }
        public void onActivityPaused(android.app.Activity activity) { }
        public void onActivityStopped(android.app.Activity activity) { }
        public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
    };

    @Override public void onCreate() {
        super.onCreate();
        getApplication().registerActivityLifecycleCallbacks(inputLifecycle);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating Notes", NotificationManager.IMPORTANCE_LOW));
        startForeground(4219, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note).setContentTitle("Family Notes")
                .setContentText("Notes and checklists quick access").setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build());
        manager = getSystemService(WindowManager.class);
        themed = new ContextThemeWrapper(this, R.style.Theme_FamilyHub);
        repository = new NotesRepository(this);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (panel == null) showPanel();
        return START_NOT_STICKY;
    }
    private void showPanel() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        SharedPreferences saved = getSharedPreferences(PREFS, MODE_PRIVATE);
        params = new WindowManager.LayoutParams(clamp(saved.getInt("w", (int)(width * .94f)), dp(300), width - dp(16)),
                clamp(saved.getInt("h", (int)(height * .72f)), dp(360), height - dp(80)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(saved.getInt("x", dp(8)), 0, Math.max(0, width - params.width));
        params.y = clamp(saved.getInt("y", dp(60)), 0, Math.max(0, height - params.height));
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        panel = new FrameLayout(themed) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) FamilyHubAppLockManager.noteTrustedOverlayInteraction();
                return super.dispatchTouchEvent(event);
            }
        };
        panel.setAlpha(1f);
        panel.setFocusableInTouchMode(true);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(240, 248, 255), Color.rgb(250, 245, 255), Color.WHITE});
        background.setCornerRadius(dp(22)); background.setStroke(dp(1), Color.rgb(190, 208, 224));
        panel.setBackground(background);
        LinearLayout root = new LinearLayout(themed); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(22));
        panel.addView(root, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(themed); header.setGravity(Gravity.TOP);
        LinearLayout titleStack = new LinearLayout(themed); titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(themed); title.setText("Family Notes");
        title.setTextSize(15); title.setTextColor(Color.rgb(32, 87, 140));
        title.setTypeface(null, android.graphics.Typeface.BOLD); title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setIncludeFontPadding(false);
        TextView state = new TextView(themed); state.setText("● Notes • Quick access"); state.setTextSize(10);
        state.setSingleLine(true); state.setEllipsize(android.text.TextUtils.TruncateAt.END); state.setIncludeFontPadding(false);
        titleStack.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));
        titleStack.addView(state, new LinearLayout.LayoutParams(-1, dp(18)));
        header.addView(titleStack, new LinearLayout.LayoutParams(0, dp(44), 1f));
        MaterialButton collapse = button("Collapse"); header.addView(collapse, new LinearLayout.LayoutParams(dp(72), dp(44)));
        MaterialButton close = button("×"); close.setContentDescription("Close floating Notes");
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(44)));
        collapse.setOnClickListener(v -> {
            collapsed = !collapsed;
            if (collapsed) {
                expandedHeight = params.height;
                body.setVisibility(View.GONE);
                params.height = dp(76);
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(panel.getWindowToken(), 0);
                panel.requestFocus();
            } else {
                params.height = expandedHeight;
                body.setVisibility(View.VISIBLE);
            }
            collapse.setText(collapsed ? "Expand" : "Collapse");
            manager.updateViewLayout(panel, params);
        });
        close.setOnClickListener(v -> stopSelf());
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(46)));
        body = new FrameLayout(themed); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        workspace = new NotesWorkspaceView(themed, repository, true, this::edit);
        restoreWorkspace(); workspace.activate();
        drag(titleStack, false);
        TextView grip = new TextView(themed); grip.setText("◢"); grip.setTextColor(Color.rgb(32, 87, 140));
        grip.setContentDescription("Resize floating Notes"); grip.setGravity(Gravity.BOTTOM | Gravity.END);
        panel.addView(grip, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.END));
        drag(grip, true);
        panel.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (body.getChildCount() > 0 && body.getChildAt(0) != workspace) restoreWorkspace();
                else stopSelf();
                return true;
            }
            return false;
        });
        manager.addView(panel, params);
        panel.requestFocus();
    }
    private void edit(NoteEntry note) {
        final boolean quickDraft = note != null && note.id == 0;
        View form = NotesEditor.create(themed, LayoutInflater.from(themed), repository, note, true,
                this::restoreWorkspace, () -> {
                    if (quickDraft) workspace.clearQuickAdd();
                    restoreWorkspace(); workspace.reload();
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        startActivity(new Intent(this, NotesNotificationPermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                });
        disposeEditor();
        body.removeAllViews(); body.addView(form, new FrameLayout.LayoutParams(-1, -1));
    }
    private void restoreWorkspace() { if (body == null) return; disposeEditor(); body.removeAllViews(); body.addView(workspace, new FrameLayout.LayoutParams(-1, -1)); }
    private void disposeEditor() {
        if (body != null && body.getChildCount() > 0 && body.getChildAt(0) != workspace)
            NotesEditor.dispose(body.getChildAt(0));
    }
    private MaterialButton button(String label) { MaterialButton button = new MaterialButton(themed); button.setText(label); button.setAllCaps(false); button.setTextSize(10); button.setCornerRadius(dp(14)); button.setMinWidth(0); button.setPadding(0, 0, 0, 0); return button; }
    private void drag(View handle, boolean resize) {
        final float[] initial = new float[2]; final int[] original = new int[2];
        handle.setOnTouchListener((view, event) -> {
            if (resize && collapsed) return true;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                initial[0] = event.getRawX(); initial[1] = event.getRawY();
                original[0] = resize ? params.width : params.x; original[1] = resize ? params.height : params.y;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int dx = Math.round(event.getRawX() - initial[0]), dy = Math.round(event.getRawY() - initial[1]);
                int width = getResources().getDisplayMetrics().widthPixels, height = getResources().getDisplayMetrics().heightPixels;
                if (resize) { params.width = clamp(original[0] + dx, dp(300), width - dp(16)); params.height = clamp(original[1] + dy, dp(360), height - dp(80)); }
                else { params.x = clamp(original[0] + dx, 0, Math.max(0, width - params.width)); params.y = clamp(original[1] + dy, 0, Math.max(0, height - params.height)); }
                try { manager.updateViewLayout(panel, params); } catch (IllegalArgumentException ignored) { }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y)
                        .putInt("w", params.width).putInt("h", collapsed ? expandedHeight : params.height).apply(); return true;
            }
            return false;
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int clamp(int value, int min, int max) { return Math.max(Math.min(min, max), Math.min(max, value)); }
    @Override public void onDestroy() { getApplication().unregisterActivityLifecycleCallbacks(inputLifecycle); disposeEditor(); if (workspace != null) workspace.deactivate(); if (panel != null) try { manager.removeView(panel); } catch (IllegalArgumentException ignored) { } panel = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
