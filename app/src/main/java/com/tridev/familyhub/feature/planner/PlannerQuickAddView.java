package com.tridev.familyhub.feature.planner;

import android.content.*;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.ScheduleDropdownView;
import com.tridev.familyhub.data.local.entity.PlannerItem;
import java.util.*;
import java.util.function.Consumer;

/** Local quick draft; all writes are delegated to the existing PlannerRepository. */
final class PlannerQuickAddView extends LinearLayout {
    private EditText title, note;
    private TextInputLayout titleField;
    private ScheduleDropdownView repeat, category, priority, sharing;
    private MaterialButton date, time, add;
    private final Calendar when = Calendar.getInstance();
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private final boolean floating;
    private boolean saving;
    private com.google.android.material.checkbox.MaterialCheckBox alert;
    PlannerQuickAddView(Context c, boolean floating, Consumer<PlannerItem> save, Runnable details) {
        super(c); this.floating=floating; setOrientation(VERTICAL); when.add(Calendar.HOUR_OF_DAY,1); when.set(Calendar.SECOND,0); when.set(Calendar.MILLISECOND,0);
        setPadding(0,0,0,PlannerPanels.dp(c,4));
        LinearLayout top=row(); titleField=field("Plan title",true); title=titleField.getEditText(); top.addView(titleField,new LayoutParams(0,-2,1));
        ScheduleDropdownView repeatField=dropdown("Type",new String[]{"Event","Task"}); repeat=repeatField; top.addView(repeatField,new LayoutParams(PlannerPanels.dp(c,104),-2));
        add=button("+",()->{
            if(saving)return;
            String text=title.getText().toString().trim(); if(text.isEmpty()){titleField.setError("Enter a plan title");return;} titleField.setError(null);
            PlannerItem reminder=draft();
            saving=true;add.setEnabled(false);save.accept(reminder);
        }); add.setContentDescription("Quick add planner item"); top.addView(add,new LayoutParams(PlannerPanels.dp(c,48),PlannerPanels.dp(c,56)));
        ScheduleDropdownView.alignQuickRow(titleField, repeatField, add, "Plan title");
        TextInputLayout noteField=field("Notes / details",false);note=noteField.getEditText();addView(noteField,new LayoutParams(-1,-2));
        LinearLayout timing=row(); date=button("",()->{
            android.app.DatePickerDialog picker=new android.app.DatePickerDialog(c,(view,y,m,d)->{when.set(y,m,d);refreshTime();},when.get(Calendar.YEAR),when.get(Calendar.MONTH),when.get(Calendar.DAY_OF_MONTH));show(picker);
        }); time=button("",()->{android.app.TimePickerDialog picker=new android.app.TimePickerDialog(c,(view,h,m)->{when.set(Calendar.HOUR_OF_DAY,h);when.set(Calendar.MINUTE,m);refreshTime();},when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),false);show(picker);});
        timing.addView(date,new LayoutParams(0,PlannerPanels.dp(c,48),1));timing.addView(time,new LayoutParams(0,PlannerPanels.dp(c,48),1));
        MaterialButton more=button("Details ▾",details);timing.addView(more,new LayoutParams(0,PlannerPanels.dp(c,48),1));
        LinearLayout options=row();ScheduleDropdownView cat=dropdown("Repeat",new String[]{"NONE","DAILY","WEEKLY","MONTHLY","YEARLY"});category=cat;options.addView(cat,new LayoutParams(0,-2,1));
        ScheduleDropdownView pr=dropdown("Priority",new String[]{"NORMAL","HIGH","URGENT"});priority=pr;options.addView(pr,new LayoutParams(0,-2,1));
        ScheduleDropdownView sh=dropdown("Visibility",new String[]{"Private","Shared"});sharing=sh;options.addView(sh,new LayoutParams(0,-2,1));refreshTime();
        alert=new com.google.android.material.checkbox.MaterialCheckBox(c);alert.setText("Reminder at start time");alert.setTextSize(11);alert.setChecked(true);addView(alert,new LayoutParams(-1,-2));
    }
    private void show(android.app.Dialog dialog){if(floating&&dialog.getWindow()!=null)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();}
    private LinearLayout row(){LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);addView(row,new LayoutParams(-1,-2));return row;}
    private TextInputLayout field(String label,boolean single){TextInputLayout f=new TextInputLayout(getContext());f.setHint(label);TextInputEditText input=new TextInputEditText(getContext());input.setInputType(single?android.text.InputType.TYPE_CLASS_TEXT:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setSingleLine(single);f.addView(input,new LayoutParams(-1,-2));com.tridev.familyhub.core.ui.CompactFormStyle.apply(f);return f;}
    private ScheduleDropdownView dropdown(String label,String[] values){return new ScheduleDropdownView(getContext(),floating,label,values);}

    private MaterialButton button(String label,Runnable action){MaterialButton b=new MaterialButton(getContext());b.setText(label);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setTextSize(11);b.setCornerRadius(PlannerPanels.dp(getContext(),16));b.setOnClickListener(v->action.run());return b;}
    private void refreshTime(){date.setText(android.text.format.DateFormat.format("dd MMM ▾",when));time.setText(android.text.format.DateFormat.format("h:mm a ▾",when));}
    PlannerItem draft(){PlannerItem r=new PlannerItem();r.title=title.getText().toString().trim();r.notes=note.getText().toString().trim();r.startAt=when.getTimeInMillis();r.itemType=repeat.getText().toString().toUpperCase(Locale.US);r.repeatType=category.getText().toString();r.priority=priority.getText().toString();r.isShared="Shared".equals(sharing.getText().toString());r.assignedMemberName=getResources().getString(R.string.planner_whole_family);r.isReminderEnabled=alert.isChecked();return r;}
    void saved(){saving=false;add.setEnabled(true);title.setText("");note.setText("");}
    void dispose(){for(android.app.Dialog dialog:new ArrayList<>(dialogs))dialog.dismiss();}
}
