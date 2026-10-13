package com.tridev.familyhub.core.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import com.tridev.familyhub.feature.grocery.GroceryRecurrenceEngine;
import com.tridev.familyhub.feature.tasks.FamilyTaskSubtasks;
import com.tridev.familyhub.feature.tasks.FamilyTaskSubtaskEditor;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;

/** Floating list editors use existing repository actions and preserve untouched record fields. */
public final class FloatingItemEditors {
    private FloatingItemEditors() { }
    private static Context themed(View anchor) {
        return new android.view.ContextThemeWrapper(anchor.getContext(), R.style.Theme_FamilyHub);
    }
    private static LinearLayout form(Context c) {
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8)); return box;
    }
    private static com.google.android.material.textfield.TextInputLayout outlinedField(EditText input) {
        com.google.android.material.textfield.TextInputLayout layout =
                new com.google.android.material.textfield.TextInputLayout(input.getContext());
        layout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setHint(input.getHint());input.setHint(null);
        layout.addView(input,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=dp(input.getContext(),6);layout.setLayoutParams(params);
        CompactFormStyle.apply(layout);return layout;
    }
    private static EditText field(LinearLayout box,String title,String value) {
        com.google.android.material.textfield.TextInputEditText text =
                new com.google.android.material.textfield.TextInputEditText(box.getContext());
        text.setHint(title); text.setText(value);
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setMinLines(1); text.setMaxLines(5);
        box.addView(outlinedField(text)); return text;
    }
    private static AutoCompleteTextView choice(LinearLayout box,String title,String value,String[] values) {
        com.google.android.material.textfield.MaterialAutoCompleteTextView text =
                new com.google.android.material.textfield.MaterialAutoCompleteTextView(box.getContext());
        text.setHint(title); text.setInputType(0);
        text.setThreshold(0);
        text.setAdapter(new ArrayAdapter<String>(box.getContext(),R.layout.item_form_dropdown,values) {
            private final android.widget.Filter allOptions = new android.widget.Filter() {
                @Override protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults result = new FilterResults();
                    result.values = values; result.count = values.length; return result;
                }
                @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                    notifyDataSetChanged();
                }
            };
            @Override public android.widget.Filter getFilter() { return allOptions; }
        });
        text.setText(value,false);
        com.google.android.material.textfield.TextInputLayout layout=outlinedField(text);
        layout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU);
        layout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        text.setOnClickListener(v -> text.showDropDown());
        box.addView(layout); return text;
    }
    private static void pair(LinearLayout box,View left,View right) {
        View l=formChild(box,left),r=formChild(box,right);
        if(l==null||r==null||l==r)return;
        box.removeView(l);box.removeView(r);
        LinearLayout row=new LinearLayout(box.getContext());row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);lp.setMarginEnd(dp(box.getContext(),4));
        row.addView(l,lp);row.addView(r,new LinearLayout.LayoutParams(0,-2,1f));
        box.addView(row);
    }
    // TextInputLayout puts the input inside an internal frame; move the whole wrapper.
    private static View formChild(LinearLayout box,View input) {
        View child=input;
        while(child.getParent()!=box) {
            if(!(child.getParent() instanceof View))return null;
            child=(View)child.getParent();
        }
        return child;
    }
    // Item rows are rebuilt by realtime refresh. Only the whole panel owns editor lifetime.
    private static void followPanel(View anchor,android.app.Dialog dialog) {
        View panel=anchor.getRootView();
        View.OnAttachStateChangeListener detach=new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){dialog.dismiss();}
        };
        panel.addOnAttachStateChangeListener(detach);
        dialog.setOnDismissListener(d->panel.removeOnAttachStateChangeListener(detach));
    }
    private static AlertDialog show(View anchor,String title,LinearLayout box,Runnable save) {
        Context c=box.getContext();
        LinearLayout shell=form(c);shell.setBackgroundResource(R.drawable.bg_form_floating_shell);
        TextView heading=new TextView(c);heading.setText(title);heading.setTextSize(15);
        heading.setTypeface(null,android.graphics.Typeface.BOLD);
        heading.setTextColor(androidx.core.content.ContextCompat.getColor(c,R.color.fh_form_accent));
        heading.setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,8));
        shell.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        androidx.core.widget.NestedScrollView scroll=new androidx.core.widget.NestedScrollView(c);
        scroll.setFillViewport(false);scroll.addView(box);shell.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(c);actions.setGravity(android.view.Gravity.END);
        com.google.android.material.button.MaterialButton cancel=new com.google.android.material.button.MaterialButton(c,null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(R.string.cancel);
        com.google.android.material.button.MaterialButton update=new com.google.android.material.button.MaterialButton(c);
        update.setText(R.string.save);
        actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(c,44),1f));
        LinearLayout.LayoutParams updateParams=new LinearLayout.LayoutParams(0,dp(c,44),1f);
        updateParams.setMarginStart(dp(c,6));actions.addView(update,updateParams);
        shell.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        CompactFormStyle.apply(shell);
        AlertDialog dialog=new MaterialAlertDialogBuilder(c).setView(shell).create();
        dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        int width=Math.min(dp(c,380),c.getResources().getDisplayMetrics().widthPixels-dp(c,32));
        int maxHeight=c.getResources().getDisplayMetrics().heightPixels-dp(c,100);
        shell.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int chrome=shell.getMeasuredHeight()-scroll.getMeasuredHeight();
        scroll.getLayoutParams().height=Math.min(scroll.getMeasuredHeight(),Math.max(dp(c,100),maxHeight-chrome));
        scroll.requestLayout();
        box.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int height=Math.min(b-t,Math.max(dp(c,100),maxHeight-chrome));
            if(scroll.getLayoutParams().height!=height){
                scroll.getLayoutParams().height=height;scroll.requestLayout();
            }
        });
        dialog.getWindow().setLayout(width,android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v->dialog.dismiss());update.setOnClickListener(v->save.run());
        followPanel(anchor,dialog);
        return dialog;
    }
    public static void grocery(View anchor,GroceryItem item,GroceryRepository repository,Runnable refresh) {
        Context c=themed(anchor); LinearLayout box=form(c);
        EditText name=field(box,"Item name",item.name);
        String[] parts=item.quantity.trim().split("\\s+",2);
        EditText quantity=field(box,"Amount",parts.length>0?parts[0]:"");
        quantity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        String originalUnit=parts.length>1?parts[1]:"";
        java.util.LinkedHashSet<String> units=new java.util.LinkedHashSet<>();
        units.add(originalUnit);
        java.util.Collections.addAll(units,c.getResources().getStringArray(R.array.grocery_quantity_units));
        AutoCompleteTextView unit=choice(box,"Unit",originalUnit,units.toArray(new String[0]));
        pair(box,quantity,unit);
        java.util.LinkedHashSet<String> categories=new java.util.LinkedHashSet<>();
        if(!item.category.isEmpty())categories.add(item.category);
        java.util.Collections.addAll(categories,com.tridev.familyhub.feature.grocery.GroceryOptionCatalog.categoryLabels(c));
        AutoCompleteTextView category=choice(box,"Category",item.category,categories.toArray(new String[0]));
        EditText notes=field(box,"Notes",item.notes);
        AutoCompleteTextView priority=choice(box,"Priority",item.priority,
                new String[]{GroceryItem.PRIORITY_NORMAL,GroceryItem.PRIORITY_HIGH,GroceryItem.PRIORITY_URGENT});
        final AlertDialog[] dialog={null};
        final String originalName=item.name;
        dialog[0]=show(anchor,"Edit grocery",box,()->{
            String title=name.getText().toString().trim();
            if(title.isEmpty()){name.setError("Required");return;}
            item.name=title;
            String amount=quantity.getText().toString().trim(),selectedUnit=unit.getText().toString().trim();
            item.quantity=amount.isEmpty()?"":amount+(selectedUnit.isEmpty()?"":" "+selectedUnit);
            item.category=category.getText().toString().trim();item.notes=notes.getText().toString().trim();
            item.priority=priority.getText().toString();
            repository.saveEdit(item,originalName,()->{dialog[0].dismiss();refresh.run();});
        });
    }
    public static void task(View anchor,FamilyTask task,FamilyTaskRepository repository,Runnable refresh) {
        final View owner=anchor.getRootView();
        Context c=themed(anchor); LinearLayout box=form(c);
        EditText title=field(box,"Task title",task.title);
        FamilyTaskSubtasks.Content content=FamilyTaskSubtasks.decode(task.notes);
        EditText notes=field(box,"Notes",content.notes);
        FamilyTaskSubtaskEditor subtasks=new FamilyTaskSubtaskEditor(c);subtasks.setContent(content);box.addView(subtasks);
        AutoCompleteTextView priority=choice(box,"Priority",task.priority,
                new String[]{FamilyTask.PRIORITY_NORMAL,FamilyTask.PRIORITY_HIGH,FamilyTask.PRIORITY_URGENT});
        AutoCompleteTextView repeat=choice(box,"Repeat",task.repeatType,
                new String[]{FamilyTask.REPEAT_NONE,FamilyTask.REPEAT_DAILY,FamilyTask.REPEAT_WEEKLY,FamilyTask.REPEAT_MONTHLY});
        pair(box,priority,repeat);
        com.google.android.material.checkbox.MaterialCheckBox reminder=new com.google.android.material.checkbox.MaterialCheckBox(c);
        reminder.setText("Reminder enabled");reminder.setChecked(task.reminderEnabled);box.addView(reminder);
        java.util.LinkedHashSet<String> leadValues=new java.util.LinkedHashSet<>();
        leadValues.add(String.valueOf(task.reminderMinutesBefore));
        java.util.Collections.addAll(leadValues,"0","5","10","15","30","60","1440");
        AutoCompleteTextView lead=choice(box,"Remind minutes before",String.valueOf(task.reminderMinutesBefore),leadValues.toArray(new String[0]));
        final long[] due={task.dueAt};
        com.google.android.material.button.MaterialButton date=new com.google.android.material.button.MaterialButton(c);
        Runnable update=()->date.setText(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(due[0])));
        update.run();box.addView(date);
        date.setOnClickListener(v->{
            java.util.Calendar calendar=java.util.Calendar.getInstance();calendar.setTimeInMillis(due[0]);
            android.app.DatePickerDialog picker=new android.app.DatePickerDialog(c,(d,year,month,day)->{
                calendar.set(year,month,day);
                android.app.TimePickerDialog time=new android.app.TimePickerDialog(c,(t,h,m)->{
                    calendar.set(java.util.Calendar.HOUR_OF_DAY,h);calendar.set(java.util.Calendar.MINUTE,m);
                    calendar.set(java.util.Calendar.SECOND,0);calendar.set(java.util.Calendar.MILLISECOND,0);
                    due[0]=calendar.getTimeInMillis();update.run();
                },calendar.get(java.util.Calendar.HOUR_OF_DAY),calendar.get(java.util.Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(c));
                time.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);time.show();followPanel(owner,time);
            },calendar.get(java.util.Calendar.YEAR),calendar.get(java.util.Calendar.MONTH),calendar.get(java.util.Calendar.DAY_OF_MONTH));
            picker.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);picker.show();followPanel(owner,picker);
        });
        final AlertDialog[] dialog={null};
        dialog[0]=show(anchor,"Edit To-Do",box,()->{
            String name=title.getText().toString().trim();
            if(name.isEmpty()){title.setError("Required");return;}
            if(!subtasks.validate())return;
            task.title=name;task.notes=FamilyTaskSubtasks.encode(notes.getText().toString().trim(),subtasks.getItems(),subtasks.getCompleted());
            task.priority=priority.getText().toString();task.repeatType=repeat.getText().toString();task.dueAt=due[0];
            task.reminderEnabled=reminder.isChecked();task.reminderMinutesBefore=Integer.parseInt(lead.getText().toString());
            repository.save(task,()->{FamilyTaskScheduler.schedule(c,task);dialog[0].dismiss();refresh.run();});
        });
    }
    public static void remove(View anchor,String title,String message,Runnable action) {
        Context c=themed(anchor);
        AlertDialog dialog=new MaterialAlertDialogBuilder(c).setTitle(title).setMessage(message)
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.remove,(d,w)->action.run()).create();
        dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
        followPanel(anchor,dialog);
    }
    public static void removeGrocery(View anchor,GroceryItem item,GroceryRepository repository,Runnable refresh) {
        boolean recurring=GroceryRecurrenceEngine.isRecurringType(GroceryRecurrenceEngine.originalCycle(item));
        remove(anchor,"Remove grocery?",item.name+(recurring
                ?"\nStops future cycles; completed purchase history is kept.":""),()->{
            if(recurring)repository.deleteRecurringMasterAndFuture(item,refresh::run);
            else repository.delete(item,refresh::run);
        });
    }
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
}
