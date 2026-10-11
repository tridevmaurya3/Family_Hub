package com.tridev.familyhub.feature.reminders;

import android.content.*;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.ScheduleDropdownView;
import com.tridev.familyhub.data.local.entity.Reminder;
import java.util.*;
import java.util.function.Consumer;

/** Local quick draft; all writes are delegated to the existing ReminderRepository. */
final class ReminderQuickAddView extends LinearLayout {
    private EditText title, note;
    private TextInputLayout titleField;
    private ScheduleDropdownView repeat, category, priority, sharing;
    private MaterialButton date, time, add;
    private final Calendar when = Calendar.getInstance();
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private final boolean floating;
    private boolean saving;
    ReminderQuickAddView(Context c, boolean floating, Consumer<Reminder> save, Runnable details) {
        super(c); this.floating=floating; setOrientation(VERTICAL); when.add(Calendar.HOUR_OF_DAY,1); when.set(Calendar.SECOND,0); when.set(Calendar.MILLISECOND,0);
        setPadding(0,0,0,ReminderPanels.dp(c,4));
        LinearLayout top=row(); titleField=field("Reminder title",true); title=titleField.getEditText(); top.addView(titleField,new LayoutParams(0,-2,1));
        ScheduleDropdownView repeatField=dropdown("Repeat",new String[]{"Once","Daily","Weekly","Monthly","Yearly"}); repeat=repeatField; top.addView(repeatField,new LayoutParams(ReminderPanels.dp(c,104),-2));
        add=button("+",()->{
            if(saving)return;
            String text=title.getText().toString().trim(); if(text.isEmpty()){titleField.setError("Enter a reminder title");return;} titleField.setError(null);
            if("Once".equals(repeat.getText().toString())&&when.getTimeInMillis()<=System.currentTimeMillis()){Toast.makeText(c,R.string.reminder_future_time_required,Toast.LENGTH_SHORT).show();return;}
            Reminder reminder=new Reminder(); reminder.title=text;reminder.note=note.getText().toString().trim();reminder.reminderAt=when.getTimeInMillis();
            reminder.repeatType=repeat.getText().toString().toUpperCase(Locale.US);if("ONCE".equals(reminder.repeatType))reminder.repeatType=Reminder.REPEAT_ONCE;
            reminder.category=category.getText().toString();reminder.priority=priority.getText().toString();reminder.isShared="Shared".equals(sharing.getText().toString());
            saving=true;add.setEnabled(false);save.accept(reminder);
        }); add.setContentDescription("Quick add reminder"); top.addView(add,new LayoutParams(ReminderPanels.dp(c,48),ReminderPanels.dp(c,56)));
        ScheduleDropdownView.alignQuickRow(titleField, repeatField, add, "Reminder title");
        TextInputLayout noteField=field("Notes / details",false);note=noteField.getEditText();addView(noteField,new LayoutParams(-1,-2));
        LinearLayout timing=row(); date=button("",()->{
            android.app.DatePickerDialog picker=new android.app.DatePickerDialog(c,(view,y,m,d)->{when.set(y,m,d);refreshTime();},when.get(Calendar.YEAR),when.get(Calendar.MONTH),when.get(Calendar.DAY_OF_MONTH));show(picker);
        }); time=button("",()->{android.app.TimePickerDialog picker=new android.app.TimePickerDialog(c,(view,h,m)->{when.set(Calendar.HOUR_OF_DAY,h);when.set(Calendar.MINUTE,m);refreshTime();},when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),false);show(picker);});
        timing.addView(date,new LayoutParams(0,ReminderPanels.dp(c,48),1));timing.addView(time,new LayoutParams(0,ReminderPanels.dp(c,48),1));
        MaterialButton more=button("Details ▾",details);timing.addView(more,new LayoutParams(0,ReminderPanels.dp(c,48),1));
        LinearLayout options=row();ScheduleDropdownView cat=dropdown("Category",new String[]{"GENERAL","FAMILY","FINANCE","HEALTH","GROCERY","VEHICLE","DOCUMENT","PROPERTY"});category=cat;options.addView(cat,new LayoutParams(0,-2,1));
        ScheduleDropdownView pr=dropdown("Priority",new String[]{"MEDIUM","LOW","HIGH","URGENT"});priority=pr;options.addView(pr,new LayoutParams(0,-2,1));
        ScheduleDropdownView sh=dropdown("Visibility",new String[]{"Private","Shared"});sharing=sh;options.addView(sh,new LayoutParams(0,-2,1));refreshTime();
    }
    private void show(android.app.Dialog dialog){if(floating&&dialog.getWindow()!=null)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private LinearLayout row(){LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);addView(row,new LayoutParams(-1,-2));return row;}
    private TextInputLayout field(String label,boolean single){TextInputLayout f=new TextInputLayout(getContext());f.setHint(label);TextInputEditText input=new TextInputEditText(getContext());input.setInputType(single?android.text.InputType.TYPE_CLASS_TEXT:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setSingleLine(single);f.addView(input,new LayoutParams(-1,-2));com.tridev.familyhub.core.ui.CompactFormStyle.apply(f);return f;}
    private ScheduleDropdownView dropdown(String label,String[] values){return new ScheduleDropdownView(getContext(),floating,label,values);}

    private MaterialButton button(String label,Runnable action){MaterialButton b=new MaterialButton(getContext());b.setText(label);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setTextSize(11);b.setCornerRadius(ReminderPanels.dp(getContext(),16));b.setOnClickListener(v->action.run());return b;}
    private void refreshTime(){date.setText(android.text.format.DateFormat.format("dd MMM ▾",when));time.setText(android.text.format.DateFormat.format("h:mm a ▾",when));}
    Reminder draft(){Reminder r=new Reminder();r.title=title.getText().toString().trim();r.note=note.getText().toString().trim();r.reminderAt=when.getTimeInMillis();r.repeatType=repeat.getText().toString().toUpperCase(Locale.US);r.category=category.getText().toString();r.priority=priority.getText().toString();r.isShared="Shared".equals(sharing.getText().toString());return r;}
    void saved(){saving=false;add.setEnabled(true);title.setText("");note.setText("");}
    void dispose(){for(android.app.Dialog dialog:new ArrayList<>(dialogs))dialog.dismiss();}
}
