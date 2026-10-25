package com.tridev.familyhub.core.reminders;

import com.tridev.familyhub.core.security.AccountDataScope;

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
public class ReminderAlarmActivity extends AppCompatActivity {
    private long id;
    private boolean occurrenceValid;
    private boolean observing;
    private com.tridev.familyhub.data.local.FamilyHubDatabase observedDatabase;
    private final com.google.firebase.auth.FirebaseAuth.AuthStateListener accountListener = auth -> validateOccurrence();
    private final androidx.room.InvalidationTracker.Observer taskObserver =
            new androidx.room.InvalidationTracker.Observer("reminders") {
                @Override public void onInvalidated(java.util.Set<String> tables) { validateOccurrence(); }
            };
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0) == id) finish();
        }
    };
    @Override protected void onCreate(Bundle state) {
        if (getIntent().getBooleanExtra("snooze_only", false)) setTheme(R.style.Theme_FamilyHub_AlarmSnooze);
        super.onCreate(state);
        // The compact Snooze chooser must also work over the device lock screen.
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        // Android screen timeout remains active while the user leaves controls open.
        ContextCompat.registerReceiver(this, close, new IntentFilter(ReminderAlarmService.CLOSED), ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(); }
    private void render() {
        if (!AccountDataScope.canDeliver(this,getIntent().getStringExtra(AccountDataScope.EXTRA_UID))) { finish(); return; }
        occurrenceValid = false;
        id = getIntent().getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0);
        if (id <= 0) { finish(); return; }
        validateOccurrence();
        if (getIntent().getBooleanExtra("snooze_only", false)) {
            LinearLayout chooser = new LinearLayout(this); chooser.setOrientation(LinearLayout.VERTICAL);
            chooser.setPadding(dp(20), dp(16), dp(20), dp(16));
            text(chooser, getIntent().getStringExtra("title"), 18);
            snoozeControls(chooser); button(chooser, android.R.string.cancel, this::finish); setContentView(chooser);
            getWindow().setLayout(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            return;
        }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL); root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(android.graphics.Color.rgb(19, 40, 61));
        TextView heading = text(root, getString(R.string.reminder_alarm_heading), 16);
        heading.setGravity(Gravity.CENTER); heading.setTextColor(android.graphics.Color.rgb(167, 210, 240));
        long time = getIntent().getLongExtra("time", System.currentTimeMillis());
        TextView clock = text(root, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(time)), 48);
        clock.setGravity(Gravity.CENTER); clock.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView date = text(root, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(time)), 15);
        date.setGravity(Gravity.CENTER);
        LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(18), dp(14), dp(18), dp(14));
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(android.graphics.Color.rgb(32, 58, 81)); card.setCornerRadius(dp(24)); details.setBackground(card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, dp(18), 0, dp(18)); root.addView(details, cardParams);
        TextView title = text(details, getIntent().getStringExtra("title"), 24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        int stage = getIntent().getIntExtra("stage", 1);
        String notice = stage == 0 || stage == 2
                ? getString(stage == 0 ? R.string.task_reminder_upcoming : R.string.task_reminder_followup_notice,
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(time)))
                : getString(stage == 3 ? R.string.task_alarm_snoozed : R.string.task_alarm_due);
        text(details, notice, 14);
        String notes = getIntent().getStringExtra("notes");
        if (notes != null && !notes.trim().isEmpty()) text(details, notes, 16);
        text(root, getString(R.string.task_alarm_snooze_tap), 16);
        snoozeButtons(root);
        MaterialButton complete = alarmButton(getString(R.string.family_tasks_notification_complete),
                () -> action(ReminderReceiver.ACTION_COMPLETE, 0), true);
        LinearLayout.LayoutParams completeParams = new LinearLayout.LayoutParams(-1, -2);
        completeParams.setMargins(0, dp(14), 0, dp(4)); root.addView(complete, completeParams);
        root.addView(alarmButton(getString(R.string.task_alarm_dismiss),
                () -> action(ReminderReceiver.ACTION_DISMISS, 0), false), new LinearLayout.LayoutParams(-1, -2));
        text(root, getString(R.string.task_alarm_dismiss_detail), 12);
        ScrollView scroll = (ScrollView) getLayoutInflater().inflate(R.layout.task_alarm_scroll, null, false);
        scroll.setBackgroundColor(android.graphics.Color.rgb(19, 40, 61)); scroll.addView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        androidx.core.view.WindowInsetsControllerCompat bars = new androidx.core.view.WindowInsetsControllerCompat(getWindow(), scroll);
        bars.setAppearanceLightStatusBars(false); bars.setAppearanceLightNavigationBars(false);
        androidx.core.view.ViewCompat.requestApplyInsets(scroll);
    }
    private void snoozeButtons(LinearLayout root) {
        int[] minutes = {5, 10, 15, 30, 60, 300, 1440};
        String[] labels = getResources().getStringArray(R.array.task_alarm_snooze_buttons);
        for (int i = 0; i < minutes.length; i += 2) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 2, minutes.length); j++) {
                final int delay = minutes[j];
                MaterialButton button = alarmButton(labels[j], () -> action(ReminderReceiver.ACTION_SNOOZE, delay), false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(j == i ? 0 : dp(4), dp(2), j == i && i + 1 < minutes.length ? dp(4) : 0, dp(2));
                row.addView(button, params);
            }
            root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private MaterialButton alarmButton(String label, Runnable action, boolean primary) {
        MaterialButton button = new MaterialButton(this); button.setText(label); button.setAllCaps(false);
        button.setTextSize(16); button.setMinHeight(dp(56)); button.setCornerRadius(dp(18));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary
                ? android.graphics.Color.rgb(118, 218, 188) : android.graphics.Color.rgb(39, 68, 94)));
        button.setTextColor(primary ? android.graphics.Color.rgb(19, 40, 61) : android.graphics.Color.WHITE);
        button.setOnClickListener(v -> { button.setEnabled(false); action.run(); }); return button;
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
        button(root, R.string.task_alarm_snooze_apply, () -> action(ReminderReceiver.ACTION_SNOOZE, minutes[choice[0]]));
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(getIntent().getBooleanExtra("snooze_only", false)
                ? android.graphics.Color.rgb(37, 66, 91) : android.graphics.Color.rgb(229, 239, 248)); view.setPadding(0, dp(6), 0, dp(6));
        root.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void button(LinearLayout root, int title, Runnable action) {
        MaterialButton view = new MaterialButton(this); view.setText(title); view.setAllCaps(false);
        view.setCornerRadius(dp(18)); view.setMinHeight(dp(52)); view.setOnClickListener(v -> action.run());
        root.addView(view, root.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    }
    private void action(String action, int minutes) {
        if (!AccountDataScope.canDeliver(this, getIntent().getStringExtra(AccountDataScope.EXTRA_UID))) {
            finish(); return;
        }
        Intent command = AccountDataScope.stamp(this, new Intent(this, ReminderReceiver.class)).setAction(action)
                .putExtra(AccountDataScope.EXTRA_UID, getIntent().getStringExtra(AccountDataScope.EXTRA_UID))
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id).putExtra(ReminderReceiver.EXTRA_SNOOZE_MINUTES, minutes);
        if (getIntent().hasExtra("due")) command.putExtra("due", getIntent().getLongExtra("due", -1));
        sendBroadcast(command); finish();
    }
    private void validateOccurrence() {
        // Database work stays off the UI thread; account changes also invalidate visible details.
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Intent delivered = getIntent();
            String uid = delivered.getStringExtra(AccountDataScope.EXTRA_UID);
            if (!AccountDataScope.canDeliver(this, uid)) { occurrenceValid = false; finish(); return; }
            occurrenceValid = false;
            long expectedId = id;
            com.tridev.familyhub.data.local.FamilyHubDatabase database =
                    com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(this);
            new Thread(() -> {
                com.tridev.familyhub.data.local.entity.Reminder task =
                        database.reminderDao().getById(expectedId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || delivered != getIntent()) return;
                    occurrenceValid = AccountDataScope.canDeliver(this, uid) && task != null && task.isEnabled
                            && !"COMPLETED".equalsIgnoreCase(task.collaborationStatus)
                            && (!delivered.hasExtra("due") || delivered.getLongExtra("due", -1) == task.reminderAt);
                    if (!occurrenceValid) finish();
                });
            }, "ReminderAlarmValidation").start();
        });
    }
    @Override protected void onStart() {
        super.onStart();
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(accountListener);
        observedDatabase = com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(this);
        observedDatabase.getInvalidationTracker().addObserver(taskObserver);
        observing = true;
        validateOccurrence();
    }
    @Override protected void onStop() {
        if (observing) {
            com.google.firebase.auth.FirebaseAuth.getInstance().removeAuthStateListener(accountListener);
            observedDatabase.getInvalidationTracker().removeObserver(taskObserver);
            observedDatabase = null;
            observing = false;
        }
        occurrenceValid = false;
        super.onStop();
    }
    @Override public void onBackPressed() { if (getIntent().getBooleanExtra("snooze_only", false)) finish(); else action(ReminderReceiver.ACTION_DISMISS, 0); }
    @Override protected void onDestroy() { unregisterReceiver(close); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
