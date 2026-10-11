package com.tridev.familyhub.core.tasks;

import android.content.*;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.tridev.familyhub.R;

/** Alarm controls for this occurrence; dismissing never marks a task completed. */
public class FamilyTaskAlarmActivity extends AppCompatActivity {
    private long id;
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent.getLongExtra(FamilyTaskReceiver.EXTRA_ID, 0) == id) finish();
        }
    };
    @Override protected void onCreate(Bundle state) {
        if (getIntent().getBooleanExtra("snooze_only", false)) setTheme(R.style.Theme_FamilyHub_AlarmSnooze);
        super.onCreate(state);
        if (!getIntent().getBooleanExtra("snooze_only", false)) {
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        ContextCompat.registerReceiver(this, close, new IntentFilter(FamilyTaskAlarmService.CLOSED), ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(); }
    private void render() {
        id = getIntent().getLongExtra(FamilyTaskReceiver.EXTRA_ID, 0);
        if (id <= 0) { finish(); return; }
        if (getIntent().getBooleanExtra("snooze_only", false)) {
            LinearLayout chooser = new LinearLayout(this); chooser.setOrientation(LinearLayout.VERTICAL);
            chooser.setPadding(dp(20), dp(16), dp(20), dp(16));
            text(chooser, getIntent().getStringExtra("title"), 18);
            snoozeControls(chooser); button(chooser, android.R.string.cancel, this::finish); setContentView(chooser);
            getWindow().setLayout(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            return;
        }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL); root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(android.graphics.Color.rgb(247, 250, 255));
        text(root, getString(R.string.task_alarm_heading), 20);
        long time = getIntent().getLongExtra("time", System.currentTimeMillis());
        text(root, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(time)), 40);
        text(root, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(time)), 14);
        text(root, getIntent().getStringExtra("title"), 24);
        text(root, getString(getIntent().getIntExtra("stage", 1) == 3 ? R.string.task_alarm_snoozed : R.string.task_alarm_due), 14);
        String notes = getIntent().getStringExtra("notes");
        if (notes != null && !notes.trim().isEmpty()) text(root, notes, 14);
        snoozeControls(root);
        button(root, R.string.task_alarm_dismiss, () -> action(FamilyTaskReceiver.ACTION_DISMISS, 0));
        button(root, R.string.family_tasks_notification_complete, () -> action(FamilyTaskReceiver.ACTION_COMPLETE, 0));
        text(root, getString(R.string.task_alarm_dismiss_detail), 12);
        ScrollView scroll = (ScrollView) getLayoutInflater().inflate(R.layout.task_alarm_scroll, null, false); scroll.addView(root);
        setContentView(scroll);
    }
    private void snoozeControls(LinearLayout root) {
        final int[] minutes = {5, 10, 15, 30, 60, 300, 1440};
        com.google.android.material.textfield.TextInputLayout field = new com.google.android.material.textfield.TextInputLayout(this);
        field.setHint(R.string.task_alarm_snooze_label); field.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
        com.google.android.material.textfield.MaterialAutoCompleteTextView duration = new com.google.android.material.textfield.MaterialAutoCompleteTextView(this);
        duration.setInputType(0); field.addView(duration, new LinearLayout.LayoutParams(-1, -2));
        field.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU);
        String[] labels = getResources().getStringArray(R.array.task_alarm_snooze_times);
        duration.setAdapter(new ArrayAdapter<>(this, R.layout.item_form_dropdown, labels));
        final int[] choice = {1}; duration.setText(labels[1], false);
        duration.setOnItemClickListener((parent, view, position, rowId) -> choice[0] = position);
        duration.setOnClickListener(v -> duration.showDropDown());
        root.addView(field, new LinearLayout.LayoutParams(-1, -2));
        button(root, R.string.task_alarm_snooze_apply, () -> action(FamilyTaskReceiver.ACTION_SNOOZE, minutes[choice[0]]));
    }
    private void text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(android.graphics.Color.rgb(37, 66, 91)); view.setPadding(0, dp(6), 0, dp(6));
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private void button(LinearLayout root, int title, Runnable action) {
        MaterialButton view = new MaterialButton(this); view.setText(title); view.setAllCaps(false);
        view.setCornerRadius(dp(18)); view.setMinHeight(dp(52)); view.setOnClickListener(v -> action.run());
        root.addView(view, root.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    }
    private void action(String action, int minutes) {
        sendBroadcast(new Intent(this, FamilyTaskReceiver.class).setAction(action)
                .putExtra(FamilyTaskReceiver.EXTRA_ID, id).putExtra("minutes", minutes)); finish();
    }
    @Override public void onBackPressed() { if (getIntent().getBooleanExtra("snooze_only", false)) finish(); else action(FamilyTaskReceiver.ACTION_DISMISS, 0); }
    @Override protected void onDestroy() { unregisterReceiver(close); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
