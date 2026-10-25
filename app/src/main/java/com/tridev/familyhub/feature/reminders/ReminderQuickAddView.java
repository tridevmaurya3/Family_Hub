package com.tridev.familyhub.feature.reminders;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.reminders.ReminderTiming;
import com.tridev.familyhub.core.ui.ScheduleDropdownView;
import com.tridev.familyhub.data.local.entity.Reminder;
import java.util.*;
import java.util.function.Consumer;

/** Same reminder choices on the main and floating surfaces; existing repository owns all writes. */
final class ReminderQuickAddView extends LinearLayout {
    private final EditText title, note;
    private final TextInputLayout titleField;
    private final ScheduleDropdownView repeat, category, priority, sharing;
    private final MaterialButton date, time, add, cancelEdit;
    private final ReminderOptionsView reminderOptions;
    private final Calendar when = Calendar.getInstance();
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private final List<PopupWindow> popups = new ArrayList<>();
    private final boolean floating;
    private boolean saving;
    private Reminder editing;
    private Runnable pendingOptionsSave;
    private int dateMode, timeMode = 2, customHour = -1, customMinute = -1;
    private long customDate;

    ReminderQuickAddView(Context c, boolean floating, Consumer<Reminder> save, Runnable details) {
        super(c); this.floating = floating; setOrientation(VERTICAL);
        setPadding(0,0,0,ReminderPanels.dp(c,4));
        LinearLayout top = row();
        titleField = field("Reminder title",true); title = titleField.getEditText();
        top.addView(titleField,new LayoutParams(0,-2,1));
        repeat = dropdown("Repeat",new String[]{"Once","Daily","Weekly","Monthly","Yearly"});
        top.addView(repeat,new LayoutParams(ReminderPanels.dp(c,104),-2));
        add = button("+", () -> { });
        top.addView(add,new LayoutParams(ReminderPanels.dp(c,48),ReminderPanels.dp(c,56)));
        ScheduleDropdownView.alignQuickRow(titleField,repeat,add,"Reminder title");
        cancelEdit = button("Cancel edit", this::resetEdit); cancelEdit.setVisibility(GONE);
        addView(cancelEdit,new LayoutParams(-1,-2));
        TextInputLayout noteField = field("Notes / details",false); note = noteField.getEditText();
        addView(noteField,new LayoutParams(-1,-2));
        LinearLayout timing = row();
        date = button("",this::chooseDate); time = button("",this::chooseTime);
        timing.addView(date,new LayoutParams(0,ReminderPanels.dp(c,40),1));
        timing.addView(time,new LayoutParams(0,ReminderPanels.dp(c,40),1));
        timing.addView(button("Details ▾",() -> { if (!saving) details.run(); }),new LayoutParams(0,ReminderPanels.dp(c,40),1));
        LinearLayout options = row();
        category = dropdown("Category",new String[]{"GENERAL","FAMILY","FINANCE","HEALTH","GROCERY","VEHICLE","DOCUMENT","PROPERTY"});
        priority = dropdown("Priority",new String[]{"MEDIUM","LOW","HIGH","URGENT"});
        sharing = dropdown("Visibility",new String[]{"Private","Shared"});
        options.addView(category,new LayoutParams(0,-2,1));
        options.addView(priority,new LayoutParams(0,-2,1));
        options.addView(sharing,new LayoutParams(0,-2,1));
        reminderOptions = new ReminderOptionsView(c,null,true);
        addView(reminderOptions,new LayoutParams(-1,-2));
        recalculateTime();
        add.setContentDescription("Quick add reminder");
        add.setOnClickListener(v -> {
            if (saving) return;
            String text = title.getText().toString().trim();
            if (text.isEmpty()) {
                if (editing == null) details.run(); else titleField.setError("Enter a reminder title");
                return;
            }
            titleField.setError(null);
            if (reminderOptions.isReminderEnabled() && !ReminderTiming.validReminderDue(
                    when.getTimeInMillis(),editing == null ? 0 : editing.reminderAt,editing != null,System.currentTimeMillis())) {
                titleField.setError(c.getString(R.string.reminder_future_time_required)); return;
            }
            Reminder reminder = editing == null ? new Reminder() : editing;
            fill(reminder);
            pendingOptionsSave = reminderOptions.captureSave(reminder);
            saving = true; add.setEnabled(false); cancelEdit.setEnabled(false); save.accept(reminder);
        });
    }
    private void chooseDate() {
        String[] values = {"Today","Tomorrow","Custom date"};
        ReminderPanels.choices(getContext(),date,floating,values,values[dateMode],value -> {
            if ("Custom date".equals(value)) {
                android.app.DatePickerDialog picker = new android.app.DatePickerDialog(getContext(),(view,y,m,d) -> {
                    Calendar selected = Calendar.getInstance(); selected.set(y,m,d);
                    dateMode = 2; customDate = selected.getTimeInMillis(); recalculateTime();
                },when.get(Calendar.YEAR),when.get(Calendar.MONTH),when.get(Calendar.DAY_OF_MONTH));
                show(picker);
            } else { dateMode = "Tomorrow".equals(value) ? 1 : 0; recalculateTime(); }
        },popups);
    }
    private void chooseTime() {
        String[] values = {"Now","30 minutes later","1 hour later","Custom time"};
        ReminderPanels.choices(getContext(),time,floating,values,values[timeMode],value -> {
            if ("Custom time".equals(value)) {
                android.app.TimePickerDialog picker = new android.app.TimePickerDialog(getContext(),(view,h,m) -> {
                    timeMode = 3; customHour = h; customMinute = m; recalculateTime();
                },when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),false);
                show(picker);
            } else {
                timeMode = "Now".equals(value) ? 0 : "30 minutes later".equals(value) ? 1 : 2;
                recalculateTime();
            }
        },popups);
    }
    private void recalculateTime() {
        when.setTimeInMillis(ReminderTiming.quickDue(System.currentTimeMillis(),dateMode,customDate,timeMode,customHour,customMinute));
        refreshTime();
    }
    private void refreshTime() {
        date.setText((dateMode == 0 ? "Today" : dateMode == 1 ? "Tomorrow" : android.text.format.DateFormat.format("dd MMM",when)) + " ▾");
        time.setText(android.text.format.DateFormat.format("h:mm a ▾",when));
    }
    void edit(Reminder reminder) {
        if (saving) return;
        editing = reminder; title.setText(reminder.title); note.setText(reminder.note);
        dateMode = 2; timeMode = 3; customDate = reminder.reminderAt; when.setTimeInMillis(reminder.reminderAt);
        customHour = when.get(Calendar.HOUR_OF_DAY); customMinute = when.get(Calendar.MINUTE);
        repeat.setText("ONCE".equals(reminder.repeatType) ? "Once" : reminder.repeatType.substring(0,1) + reminder.repeatType.substring(1).toLowerCase(Locale.US));
        category.setText(reminder.category); priority.setText(reminder.priority); sharing.setText(reminder.isShared ? "Shared" : "Private");
        reminderOptions.bindQuick(reminder); refreshTime(); setDetailsCollapsed(false);
        cancelEdit.setVisibility(VISIBLE); add.setText("Update"); add.setContentDescription("Update reminder");
        title.requestFocus();
    }
    private void resetEdit() {
        if (saving) return;
        editing = null; title.setText(""); note.setText(""); titleField.setError(null);
        dateMode = 0; timeMode = 2; customDate = 0; customHour = customMinute = -1;
        repeat.setText("Once"); category.setText("GENERAL"); priority.setText("MEDIUM"); sharing.setText("Private");
        reminderOptions.bindQuick(null); cancelEdit.setVisibility(GONE); add.setText("+");
        add.setContentDescription("Quick add reminder"); recalculateTime();
    }
    private void fill(Reminder reminder) {
        reminder.title = title.getText().toString().trim(); reminder.note = note.getText().toString().trim();
        reminder.reminderAt = when.getTimeInMillis(); reminder.repeatType = repeat.getText().toString().toUpperCase(Locale.US);
        reminder.category = category.getText().toString(); reminder.priority = priority.getText().toString();
        reminder.isShared = "Shared".equals(sharing.getText().toString()); reminderOptions.applyQuick(reminder);
    }
    Reminder draft() {
        Reminder reminder = new Reminder();
        if (editing != null) {
            reminder.id=editing.id; reminder.cloudId=editing.cloudId; reminder.familyId=editing.familyId;
            reminder.assignedMemberId=editing.assignedMemberId; reminder.assignedMemberName=editing.assignedMemberName;
            reminder.collaborationStatus=editing.collaborationStatus; reminder.createdAt=editing.createdAt;
            reminder.updatedAt=editing.updatedAt; reminder.updatedByUid=editing.updatedByUid;
            reminder.seenAt=editing.seenAt; reminder.acceptedAt=editing.acceptedAt; reminder.startedAt=editing.startedAt;
            reminder.completedAt=editing.completedAt; reminder.completedByName=editing.completedByName;
            reminder.relatedModule=editing.relatedModule; reminder.relatedItemTitle=editing.relatedItemTitle;
        }
        fill(reminder); return reminder;
    }
    private void show(android.app.Dialog dialog) {
        if (floating && dialog.getWindow()!=null) dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show();
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); addView(row,new LayoutParams(-1,-2)); return row; }
    private TextInputLayout field(String label,boolean single) {
        TextInputLayout field = new TextInputLayout(getContext()); field.setHint(label);
        TextInputEditText input = new TextInputEditText(getContext());
        input.setInputType(single ? android.text.InputType.TYPE_CLASS_TEXT : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(single); field.addView(input,new LayoutParams(-1,-2));
        com.tridev.familyhub.core.ui.CompactFormStyle.apply(field);
        field.setEndIconMode(TextInputLayout.END_ICON_CUSTOM); field.setEndIconDrawable(R.drawable.ic_mic);
        field.setEndIconContentDescription("Voice input"); return field;
    }
    private ScheduleDropdownView dropdown(String label,String[] values) { return new ScheduleDropdownView(getContext(),floating,label,values); }
    private MaterialButton button(String label,Runnable action) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(label); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0,0,0,0); button.setTextSize(11);
        button.setCornerRadius(ReminderPanels.dp(getContext(),16)); button.setOnClickListener(v -> action.run()); return button;
    }
    void setDetailsCollapsed(boolean collapsed) {
        for (int i=1;i<getChildCount();i++) getChildAt(i).setVisibility(collapsed ? GONE : VISIBLE);
        cancelEdit.setVisibility(!collapsed && editing != null ? VISIBLE : GONE);
    }
    void persistOptions() { if (pendingOptionsSave != null) { pendingOptionsSave.run(); pendingOptionsSave = null; } }
    void copyOptionsTo(ReminderOptionsView target) { target.copySelectionFrom(reminderOptions); }
    void saved() {
        saving = false; add.setEnabled(true); cancelEdit.setEnabled(true);
        if (editing != null) resetEdit();
        else { title.setText(""); note.setText(""); reminderOptions.bindQuick(null); recalculateTime(); }
    }
    void dispose() {
        for (android.app.Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        for (PopupWindow popup : new ArrayList<>(popups)) popup.dismiss();
    }
}
