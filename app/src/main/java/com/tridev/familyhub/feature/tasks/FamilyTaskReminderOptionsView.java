package com.tridev.familyhub.feature.tasks;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskReceiver;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.core.tasks.FamilyTaskReminderPermissionActivity;
import com.tridev.familyhub.core.tasks.FamilyTaskReminderPreferences;
import com.tridev.familyhub.data.local.entity.FamilyTask;

/** Uses the same XML Material fields, cards and controls as the normal To-Do form. */
public final class FamilyTaskReminderOptionsView extends LinearLayout {
    private final MaterialSwitch follow;
    private final MaterialButton notificationAccess;
    private final MaterialButton exactAccess;
    private int leadIndex = 3;
    private int modeIndex;
    private static final int[] MINUTES = {-1, 0, 5, 15, 30, 60, 1440};
    public FamilyTaskReminderOptionsView(Context context, FamilyTask task, boolean quick) {
        super(new ContextThemeWrapper(context, R.style.Theme_FamilyHub));
        setOrientation(VERTICAL);
        Context themed = getContext();
        LayoutInflater.from(themed).inflate(R.layout.view_family_task_reminder_options, this, true);
        MaterialButton toggle = findViewById(R.id.reminder_toggle);
        View details = findViewById(R.id.reminder_details);
        notificationAccess = findViewById(R.id.reminder_notification_access);
        exactAccess = findViewById(R.id.reminder_exact_access);
        MaterialAutoCompleteTextView lead = findViewById(R.id.reminder_lead_input);
        MaterialAutoCompleteTextView mode = findViewById(R.id.reminder_mode_input);
        follow = findViewById(R.id.reminder_followup);
        toggle.setText(quick ? R.string.task_reminder_quick_default : R.string.task_reminder_options);
        toggle.setOnClickListener(v -> { details.setVisibility(details.getVisibility() == GONE ? VISIBLE : GONE); refreshAccess(); });
        String[] labels = {themed.getString(R.string.task_reminder_off), themed.getString(R.string.task_reminder_short_at),
                themed.getString(R.string.task_reminder_short_5), themed.getString(R.string.task_reminder_short_15),
                themed.getString(R.string.task_reminder_short_30), themed.getString(R.string.task_reminder_short_hour), themed.getString(R.string.task_reminder_short_day)};
        lead.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_list_item_1, labels));
        lead.setText(labels[leadIndex], false);
        lead.setOnItemClickListener((parent, view, position, id) -> {
            leadIndex = position;
            toggle.setText(themed.getString(R.string.task_reminder_quick_selection, labels[position]));
        });
        findViewById(R.id.reminder_lead_layout).setVisibility(quick ? VISIBLE : GONE);
        modeIndex = task != null && FamilyTaskReminderPreferences.important(themed, task) ? 1 : 0;
        String[] modes = {themed.getString(R.string.task_reminder_mode_normal), themed.getString(R.string.task_reminder_mode_important)};
        mode.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_list_item_1, modes));
        mode.setText(modes[modeIndex], false);
        mode.setOnItemClickListener((parent, view, position, id) -> { modeIndex = position; refreshAccess(); });
        follow.setChecked(task != null && FamilyTaskReminderPreferences.followUp(themed, task));
        notificationAccess.setOnClickListener(v -> notificationAccess(false));
        exactAccess.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 31) openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + themed.getPackageName())));
        });
        findViewById(R.id.reminder_permissions).setOnClickListener(v -> {
            refreshAccess();
            View explanation = findViewById(R.id.reminder_permission_detail);
            explanation.setVisibility(explanation.getVisibility() == GONE ? VISIBLE : GONE);
        });
        findViewById(R.id.reminder_test).setOnClickListener(v -> {
            if (FamilyTaskReceiver.testNotification(themed, modeIndex == 1))
                Toast.makeText(themed, R.string.task_reminder_test_sent, Toast.LENGTH_SHORT).show();
            else {
                Toast.makeText(themed, R.string.task_reminder_permission_required, Toast.LENGTH_LONG).show();
                notificationAccess(true);
            }
        });
        refreshAccess();
    }
    private void refreshAccess() {
        notificationAccess.setVisibility(FamilyTaskReceiver.canNotify(getContext(), modeIndex == 1) ? GONE : VISIBLE);
        AlarmManager manager = getContext().getSystemService(AlarmManager.class);
        exactAccess.setVisibility(Build.VERSION.SDK_INT >= 31 && manager != null && !manager.canScheduleExactAlarms() ? VISIBLE : GONE);
    }
    private void notificationAccess(boolean test) {
        if (FamilyTaskReminderPermissionActivity.needsPermission(getContext())) {
            FamilyTaskReminderPermissionActivity.request(getContext(), test, modeIndex == 1);
        } else openSettings(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, FamilyTaskReceiver.channelId(modeIndex == 1)));
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) { refreshAccess(); FamilyTaskScheduler.rescheduleAll(getContext(), () -> { }); }
    }
    private void openSettings(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getContext().getPackageManager()) != null) getContext().startActivity(intent);
    }
    public void applyQuick(FamilyTask task) {
        int minutes = MINUTES[leadIndex];
        task.reminderEnabled = minutes >= 0;
        task.reminderMinutesBefore = Math.max(0, minutes);
    }
    public void saveOptions(FamilyTask task) {
        FamilyTaskReminderPreferences.save(getContext(), task, modeIndex == 1, follow.isChecked());
        if (task.reminderEnabled) FamilyTaskReminderPermissionActivity.requestOnce(getContext());
    }
}
