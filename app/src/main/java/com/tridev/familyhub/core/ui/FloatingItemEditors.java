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
    private static void label(LinearLayout box, String label) {
        TextView text = new TextView(box.getContext()); text.setText(label); text.setTextSize(12);
        box.addView(text);
    }
    private static EditText field(LinearLayout box,String title,String value) {
        label(box,title);
        EditText text = new androidx.appcompat.widget.AppCompatEditText(box.getContext());
        text.setText(value); text.setTextSize(14); text.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(text,new LinearLayout.LayoutParams(-1,-2)); return text;
    }
    private static AutoCompleteTextView choice(LinearLayout box,String title,String value,String[] values) {
        label(box,title);
        AutoCompleteTextView text = new AutoCompleteTextView(box.getContext());
        text.setInputType(0); text.setTextSize(14);
        text.setAdapter(new ArrayAdapter<>(box.getContext(),R.layout.item_form_dropdown,values));
        text.setText(value,false); text.setOnClickListener(v->text.showDropDown());
        box.addView(text,new LinearLayout.LayoutParams(-1,dp(box.getContext(),48))); return text;
    }
    private static AlertDialog show(View anchor,String title,LinearLayout box,Runnable save) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(box.getContext());
        scroll.addView(box);
        AlertDialog dialog = new MaterialAlertDialogBuilder(box.getContext()).setTitle(title).setView(scroll)
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.save,null).create();
        dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
        View.OnAttachStateChangeListener detach = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { dialog.dismiss(); }
        };
        anchor.addOnAttachStateChangeListener(detach);
        dialog.setOnDismissListener(d->anchor.removeOnAttachStateChangeListener(detach));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save.run());
        return dialog;
    }
    public static void grocery(View anchor,GroceryItem item,GroceryRepository repository,Runnable refresh) {
        Context c=themed(anchor); LinearLayout box=form(c);
        EditText name=field(box,"Item name",item.name);
        EditText quantity=field(box,"Quantity",item.quantity);
        EditText category=field(box,"Category",item.category);
        EditText notes=field(box,"Notes",item.notes);
        AutoCompleteTextView priority=choice(box,"Priority",item.priority,
                new String[]{GroceryItem.PRIORITY_NORMAL,GroceryItem.PRIORITY_HIGH,GroceryItem.PRIORITY_URGENT});
        final AlertDialog[] dialog={null};
        final String originalName=item.name;
        dialog[0]=show(anchor,"Edit grocery",box,()->{
            String title=name.getText().toString().trim();
            if(title.isEmpty()){name.setError("Required");return;}
            item.name=title;item.quantity=quantity.getText().toString().trim();
            item.category=category.getText().toString().trim();item.notes=notes.getText().toString().trim();
            item.priority=priority.getText().toString();
            repository.saveEdit(item,originalName,()->{dialog[0].dismiss();refresh.run();});
        });
    }
    public static void task(View anchor,FamilyTask task,FamilyTaskRepository repository,Runnable refresh) {
        Context c=themed(anchor); LinearLayout box=form(c);
        EditText title=field(box,"Task title",task.title);
        FamilyTaskSubtasks.Content content=FamilyTaskSubtasks.decode(task.notes);
        EditText notes=field(box,"Notes",content.notes);
        FamilyTaskSubtaskEditor subtasks=new FamilyTaskSubtaskEditor(c);subtasks.setContent(content);box.addView(subtasks);
        AutoCompleteTextView priority=choice(box,"Priority",task.priority,
                new String[]{FamilyTask.PRIORITY_NORMAL,FamilyTask.PRIORITY_HIGH,FamilyTask.PRIORITY_URGENT});
        AutoCompleteTextView repeat=choice(box,"Repeat",task.repeatType,
                new String[]{FamilyTask.REPEAT_NONE,FamilyTask.REPEAT_DAILY,FamilyTask.REPEAT_WEEKLY,FamilyTask.REPEAT_MONTHLY});
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
                time.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);time.show();
            },calendar.get(java.util.Calendar.YEAR),calendar.get(java.util.Calendar.MONTH),calendar.get(java.util.Calendar.DAY_OF_MONTH));
            picker.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);picker.show();
        });
        final AlertDialog[] dialog={null};
        dialog[0]=show(anchor,"Edit To-Do",box,()->{
            String name=title.getText().toString().trim();
            if(name.isEmpty()){title.setError("Required");return;}
            if(!subtasks.validate())return;
            task.title=name;task.notes=FamilyTaskSubtasks.encode(notes.getText().toString().trim(),subtasks.getItems(),subtasks.getCompleted());
            task.priority=priority.getText().toString();task.repeatType=repeat.getText().toString();task.dueAt=due[0];
            repository.save(task,()->{FamilyTaskScheduler.schedule(c,task);dialog[0].dismiss();refresh.run();});
        });
    }
    public static void remove(View anchor,String title,String message,Runnable action) {
        Context c=themed(anchor);
        AlertDialog dialog=new MaterialAlertDialogBuilder(c).setTitle(title).setMessage(message)
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.remove,(d,w)->action.run()).create();
        dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
        View.OnAttachStateChangeListener detach=new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){dialog.dismiss();}
        };
        anchor.addOnAttachStateChangeListener(detach);
        dialog.setOnDismissListener(d->anchor.removeOnAttachStateChangeListener(detach));
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
