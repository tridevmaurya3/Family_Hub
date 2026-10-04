package com.tridev.familyhub.feature.tasks;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.AbsListView;
import androidx.core.content.ContextCompat;
import com.google.android.material.checkbox.MaterialCheckBox;
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
        int green = ContextCompat.getColor(context, R.color.fh_module_grocery);
        int greenFill = ContextCompat.getColor(context, R.color.fh_module_grocery_container);
        int blue = ContextCompat.getColor(context, R.color.fh_primary);
        int blueFill = ContextCompat.getColor(context, R.color.fh_primary_container);
        int gold = ContextCompat.getColor(context, R.color.fh_warning);
        int goldFill = ContextCompat.getColor(context, R.color.fh_warning_container);
        Button toggle = new Button(context);
        toggle.setText(quick ? context.getString(R.string.task_reminder_quick_default) : context.getString(R.string.task_reminder_options));
        styleButton(toggle, greenFill, green);
        toggle.setMinHeight(0);
        toggle.setMinimumHeight(0);
        addView(toggle, new LayoutParams(-1, dp(40)));
        notificationStatus = new TextView(context);
        notificationStatus.setText(R.string.task_reminder_notification_access);
        styleText(notificationStatus, gold);
        notificationStatus.setGravity(Gravity.CENTER_VERTICAL);
        notificationStatus.setPadding(dp(10), dp(4), dp(10), dp(4));
        notificationStatus.setBackground(surface(goldFill, gold, 12));
        notificationStatus.setVisibility(NotificationManagerCompat.from(context).areNotificationsEnabled() ? GONE : VISIBLE);
        notificationStatus.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())));
        LayoutParams statusParams = new LayoutParams(-1, -2);
        statusParams.topMargin = dp(4);
        addView(notificationStatus, statusParams);
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        details.setPadding(dp(7), dp(7), dp(7), dp(7));
        details.setBackground(surface(ContextCompat.getColor(context, R.color.fh_surface), green, 12));
        ScrollView optionsScroll = new ScrollView(context);
        optionsScroll.addView(details);
        optionsScroll.setClipToPadding(false);
        optionsScroll.setVisibility(GONE);
        LayoutParams detailParams = new LayoutParams(-1, dp(180));
        detailParams.topMargin = dp(4);
        addView(optionsScroll, detailParams);
        toggle.setOnClickListener(v -> optionsScroll.setVisibility(optionsScroll.getVisibility() == GONE ? VISIBLE : GONE));
        lead = new Spinner(context, Spinner.MODE_DROPDOWN);
        String[] labels = {context.getString(R.string.task_reminder_off), context.getString(R.string.task_reminder_at_time),
                context.getString(R.string.task_reminder_5_minutes), context.getString(R.string.task_reminder_15_minutes),
                context.getString(R.string.task_reminder_30_minutes), context.getString(R.string.task_reminder_1_hour),
                context.getString(R.string.task_reminder_1_day)};
        styleDropdown(lead, labels, goldFill, gold);
        lead.setContentDescription(context.getString(R.string.family_tasks_reminder_time));
        lead.setSelection(quick ? 3 : 0);
        if (quick) lead.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                toggle.setText(context.getString(R.string.task_reminder_quick_selection, labels[position]));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        if (quick) details.addView(lead, optionParams());
        mode = new Spinner(context, Spinner.MODE_DROPDOWN);
        styleDropdown(mode, new String[]{context.getString(R.string.task_reminder_normal),
                context.getString(R.string.task_reminder_important)}, Color.rgb(245, 238, 251), Color.rgb(106, 75, 150));
        mode.setSelection(task != null && FamilyTaskReminderPreferences.important(context, task) ? 1 : 0);
        details.addView(mode, optionParams());
        follow = new MaterialCheckBox(new android.view.ContextThemeWrapper(context, R.style.Theme_FamilyHub));
        follow.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{green, ContextCompat.getColor(context, R.color.fh_text_secondary)}));
        follow.setTextColor(ContextCompat.getColor(context, R.color.fh_text_primary));
        follow.setBackground(surface(greenFill, green, 12));
        follow.setPadding(dp(4), dp(3), dp(7), dp(3));
        follow.setText(R.string.task_reminder_followup);
        follow.setTextSize(11);
        follow.setChecked(task != null && FamilyTaskReminderPreferences.followUp(context, task));
        LayoutParams followParams = new LayoutParams(-1, -2);
        followParams.topMargin = dp(4);
        details.addView(follow, followParams);
        TextView note = new TextView(context);
        note.setText(R.string.task_reminder_device_options);
        note.setTextSize(10);
        note.setTextColor(ContextCompat.getColor(context, R.color.fh_text_secondary));
        note.setPadding(dp(4), dp(5), dp(4), dp(5));
        details.addView(note);
        Button access = new Button(context);
        access.setText(R.string.task_reminder_permissions);
        styleButton(access, blueFill, blue);
        details.addView(access, optionParams());
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
                styleButton(notification, greenFill, green);
                permissionRows.addView(notification, optionParams());
                notification.setOnClickListener(w -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())));
            }
            AlarmManager manager = context.getSystemService(AlarmManager.class);
            if (Build.VERSION.SDK_INT >= 31 && manager != null && !manager.canScheduleExactAlarms()) {
                Button exact = new Button(context);
                exact.setText(R.string.task_reminder_exact_access);
                styleButton(exact, goldFill, gold);
                permissionRows.addView(exact, optionParams());
                exact.setOnClickListener(w -> openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + context.getPackageName()))));
            }
            TextView explanation = new TextView(context);
            explanation.setText(R.string.task_reminder_exact_detail);
            explanation.setTextSize(11);
            explanation.setTextColor(ContextCompat.getColor(context, R.color.fh_text_secondary));
            explanation.setPadding(dp(4), dp(6), dp(4), dp(4));
            permissionRows.addView(explanation);
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LayoutParams optionParams() {
        LayoutParams params = new LayoutParams(-1, dp(44));
        params.topMargin = dp(4);
        return params;
    }
    private GradientDrawable surface(int fill, int accent, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent)));
        return background;
    }
    private void styleText(TextView text, int accent) {
        text.setTextSize(11.5f);
        text.setTextColor(accent);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
    }
    private void styleButton(Button button, int fill, int accent) {
        styleText(button, accent);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))),
                surface(fill, accent, 14), surface(Color.WHITE, accent, 14)));
    }
    private void styleDropdown(Spinner spinner, String[] labels, int fill, int accent) {
        spinner.setBackgroundTintList(null);
        spinner.setBackground(surface(fill, accent, 12));
        spinner.setPadding(0, 0, 0, 0);
        spinner.setPopupBackgroundDrawable(surface(ContextCompat.getColor(getContext(), R.color.fh_surface), accent, 14));
        spinner.setDropDownVerticalOffset(dp(4));
        spinner.setDropDownWidth(LayoutParams.MATCH_PARENT);
        spinner.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView label = new TextView(getContext());
                styleText(label, accent);
                label.setText(labels[position] + "  ▾");
                label.setGravity(Gravity.CENTER_VERTICAL);
                label.setPadding(dp(10), dp(4), dp(10), dp(4));
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                return label;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView row = new TextView(getContext());
                boolean selected = spinner.getSelectedItemPosition() == position;
                styleText(row, selected ? accent : ContextCompat.getColor(getContext(), R.color.fh_text_primary));
                row.setText((selected ? "✓  " : "   ") + labels[position]);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(11), dp(5), dp(10), dp(5));
                row.setMinHeight(dp(40));
                row.setBackground(surface(selected ? fill : Color.WHITE, accent, 8));
                row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                return row;
            }
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
