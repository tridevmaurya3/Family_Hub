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
public final class FamilyTaskAlarmActivity extends AppCompatActivity {
    private long id;
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent.getLongExtra(FamilyTaskReceiver.EXTRA_ID, 0) == id) finish();
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ContextCompat.registerReceiver(this, close, new IntentFilter(FamilyTaskAlarmService.CLOSED), ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(); }
    private void render() {
        id = getIntent().getLongExtra(FamilyTaskReceiver.EXTRA_ID, 0);
        if (id <= 0) { finish(); return; }
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
        button(root, R.string.task_alarm_snooze10, () -> action(FamilyTaskReceiver.ACTION_SNOOZE, 10));
        LinearLayout row = new LinearLayout(this);
        button(row, R.string.task_alarm_snooze30, () -> action(FamilyTaskReceiver.ACTION_SNOOZE, 30));
        button(row, R.string.task_alarm_snooze60, () -> action(FamilyTaskReceiver.ACTION_SNOOZE, 60)); root.addView(row);
        button(root, R.string.task_alarm_dismiss, () -> action(FamilyTaskReceiver.ACTION_DISMISS, 0));
        button(root, R.string.family_tasks_notification_complete, () -> action(FamilyTaskReceiver.ACTION_COMPLETE, 0));
        text(root, getString(R.string.task_alarm_dismiss_detail), 12);
        ScrollView scroll = (ScrollView) getLayoutInflater().inflate(R.layout.task_alarm_scroll, null, false); scroll.addView(root);
        setContentView(scroll);
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
    @Override public void onBackPressed() { action(FamilyTaskReceiver.ACTION_DISMISS, 0); }
    @Override protected void onDestroy() { unregisterReceiver(close); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
