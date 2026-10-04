package com.tridev.familyhub.feature.tasks;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.NotificationManagerCompat;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskReminderPreferences;
import com.tridev.familyhub.data.local.entity.FamilyTask;

/** Compact, expandable controls using platform widgets, including in the overlay service. */
public final class FamilyTaskReminderOptionsView extends LinearLayout {
    private final Spinner lead;
    private final Spinner mode;
    private final CheckBox follow;
    private final TextView notificationStatus;
    private static final int[] MINUTES = {-1, 0, 5, 15, 30, 60, 1440};
    public FamilyTaskReminderOptionsView(Context context, FamilyTask task, boolean quick) {
        super(context);
        setOrientation(VERTICAL);
        Button toggle = new Button(context);
        toggle.setText(quick ? context.getString(R.string.task_reminder_quick_default) : context.getString(R.string.task_reminder_options));
        toggle.setTextSize(11);
        toggle.setMinHeight(0);
        toggle.setMinimumHeight(0);
        addView(toggle, new LayoutParams(-1, (int)(40 * getResources().getDisplayMetrics().density)));
        notificationStatus = new TextView(context);
        notificationStatus.setText(R.string.task_reminder_notification_access);
        notificationStatus.setTextSize(11);
        notificationStatus.setVisibility(NotificationManagerCompat.from(context).areNotificationsEnabled() ? GONE : VISIBLE);
        notificationStatus.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())));
        addView(notificationStatus, new LayoutParams(-1, -2));
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        ScrollView optionsScroll = new ScrollView(context);
        optionsScroll.addView(details);
        optionsScroll.setVisibility(GONE);
        addView(optionsScroll, new LayoutParams(-1, (int)(180 * getResources().getDisplayMetrics().density)));
        toggle.setOnClickListener(v -> optionsScroll.setVisibility(optionsScroll.getVisibility() == GONE ? VISIBLE : GONE));
        lead = new Spinner(context, Spinner.MODE_DROPDOWN);
        String[] labels = {context.getString(R.string.task_reminder_off), context.getString(R.string.task_reminder_at_time),
                context.getString(R.string.task_reminder_5_minutes), context.getString(R.string.task_reminder_15_minutes),
                context.getString(R.string.task_reminder_30_minutes), context.getString(R.string.task_reminder_1_hour),
                context.getString(R.string.task_reminder_1_day)};
        lead.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        lead.setContentDescription(context.getString(R.string.family_tasks_reminder_time));
        lead.setSelection(quick ? 3 : 0);
        if (quick) lead.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                toggle.setText(context.getString(R.string.task_reminder_quick_selection, labels[position]));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        if (quick) details.addView(lead, new LayoutParams(-1, -2));
        mode = new Spinner(context, Spinner.MODE_DROPDOWN);
        mode.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                new String[]{context.getString(R.string.task_reminder_normal), context.getString(R.string.task_reminder_important)}));
        mode.setSelection(task != null && FamilyTaskReminderPreferences.important(context, task) ? 1 : 0);
        details.addView(mode, new LayoutParams(-1, -2));
        follow = new CheckBox(context);
        follow.setText(R.string.task_reminder_followup);
        follow.setTextSize(11);
        follow.setChecked(task != null && FamilyTaskReminderPreferences.followUp(context, task));
        details.addView(follow, new LayoutParams(-1, -2));
        TextView note = new TextView(context);
        note.setText(R.string.task_reminder_device_options);
        note.setTextSize(10);
        details.addView(note);
        Button access = new Button(context);
        access.setText(R.string.task_reminder_permissions);
        access.setTextSize(11);
        details.addView(access, new LayoutParams(-1, -2));
        LinearLayout permissionRows = new LinearLayout(context);
        permissionRows.setOrientation(VERTICAL);
        permissionRows.setVisibility(GONE);
        details.addView(permissionRows);
        access.setOnClickListener(v -> {
            permissionRows.removeAllViews();
            permissionRows.setVisibility(VISIBLE);
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Button notification = new Button(context);
                notification.setText(R.string.task_reminder_notification_access);
                permissionRows.addView(notification);
                notification.setOnClickListener(w -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())));
            }
            AlarmManager manager = context.getSystemService(AlarmManager.class);
            if (Build.VERSION.SDK_INT >= 31 && manager != null && !manager.canScheduleExactAlarms()) {
                Button exact = new Button(context);
                exact.setText(R.string.task_reminder_exact_access);
                permissionRows.addView(exact);
                exact.setOnClickListener(w -> openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + context.getPackageName()))));
            }
            TextView explanation = new TextView(context);
            explanation.setText(R.string.task_reminder_exact_detail);
            explanation.setTextSize(11);
            permissionRows.addView(explanation);
        });
    }
    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            notificationStatus.setVisibility(NotificationManagerCompat.from(getContext()).areNotificationsEnabled() ? GONE : VISIBLE);
            com.tridev.familyhub.core.tasks.FamilyTaskScheduler.rescheduleAll(getContext(), () -> { });
        }
    }
    private void openSettings(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getContext().getPackageManager()) != null) getContext().startActivity(intent);
    }
    public void applyQuick(FamilyTask task) {
        int minutes = MINUTES[lead.getSelectedItemPosition()];
        task.reminderEnabled = minutes >= 0;
        task.reminderMinutesBefore = Math.max(0, minutes);
    }
    public void saveOptions(FamilyTask task) {
        FamilyTaskReminderPreferences.save(getContext(), task, mode.getSelectedItemPosition() == 1, follow.isChecked());
    }
}
