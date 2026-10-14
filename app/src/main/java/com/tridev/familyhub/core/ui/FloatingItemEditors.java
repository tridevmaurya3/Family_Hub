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
    private static void row(LinearLayout box, View... inputs) {
        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < inputs.length; i++) {
            View child = formChild(box, inputs[i]);
            if (child == null) child = inputs[i];
            if (child.getParent() == box) box.removeView(child);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) params.setMarginStart(dp(box.getContext(), 6));
            row.addView(child, params);
        }
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private static com.google.android.material.button.MaterialButton chip(Context c, String text) {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(c, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPaddingRelative(dp(c, 8), 0, dp(c, 8), 0);
        button.setSingleLine(false); button.setMaxLines(2);
        return button;
    }
    private static AlertDialog show(View anchor, String title, LinearLayout box,
                                    LinearLayout details, Runnable save) {
        Context c = box.getContext();
        android.widget.FrameLayout root = new android.widget.FrameLayout(c);
        root.setBackgroundResource(R.drawable.bg_form_floating_shell);
        LinearLayout shell = new LinearLayout(c); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        root.addView(shell, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(c); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(c); heading.setText(title); heading.setTextSize(17);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.fh_form_accent));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        com.google.android.material.button.MaterialButton more = chip(c, "Show more ▾");
        header.addView(more, new LinearLayout.LayoutParams(-2, dp(c, 44)));
        android.widget.ImageButton close = new android.widget.ImageButton(c);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setContentDescription("Close editor");
        header.addView(close, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 44)));
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));
        details.setVisibility(View.GONE);
        details.setTag(more);
        box.addView(details, new LinearLayout.LayoutParams(-1, -2));
        box.setPadding(0, 0, 0, 0);
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(c);
        scroll.setFillViewport(false); scroll.addView(box);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = new LinearLayout(c);
        actions.setPadding(0, dp(c, 8), 0, 0);
        com.google.android.material.button.MaterialButton cancel = chip(c, c.getString(R.string.cancel));
        com.google.android.material.button.MaterialButton update = new com.google.android.material.button.MaterialButton(c);
        update.setText("Update"); update.setMinWidth(0); update.setMinimumWidth(0);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(c, 44), 1f));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(0, dp(c, 44), 1f);
        updateParams.setMarginStart(dp(c, 8)); actions.addView(update, updateParams);
        shell.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        CompactFormStyle.apply(shell);
        AlertDialog dialog = new MaterialAlertDialogBuilder(c).setView(root).create();
        android.view.Window window = dialog.getWindow();
        window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
        // Remove AlertDialog custom-panel padding: the form supplies its own safe inset.
        android.view.ViewParent parent = root.getParent();
        if (parent instanceof View) ((View) parent).setPadding(0, 0, 0, 0);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        android.util.DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        final int maxWidth = metrics.widthPixels - dp(c, 24);
        final int maxHeight = metrics.heightPixels - dp(c, 100);
        final boolean[] resized = {false};
        Runnable fit = () -> {
            if (resized[0] || !dialog.isShowing()) return;
            int width = Math.min(dp(c, 380), maxWidth);
            int inner = Math.max(1, width - dp(c, 36));
            int exact = View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY);
            int natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            header.measure(exact, natural); actions.measure(exact, natural); box.measure(exact, natural);
            int height = box.getMeasuredHeight() + header.getMeasuredHeight()
                    + actions.getMeasuredHeight() + dp(c, 36);
            window.setLayout(width, Math.min(maxHeight, height));
        };
        fit.run();
        more.setOnClickListener(v -> {
            boolean expanded = details.getVisibility() != View.VISIBLE;
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            more.setText(expanded ? "Show less ▴" : "Show more ▾");
            fit.run();
        });
        box.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (!resized[0] && (b-t != ob-ot || r-l != or-ol)) root.post(fit);
        });
        for (int corner = 0; corner < 4; corner++) {
            final boolean left = corner % 2 == 0, top = corner < 2;
            View handle = new View(c) {
                private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(android.graphics.Canvas canvas) {
                    super.onDraw(canvas);
                    paint.setColor(androidx.core.content.ContextCompat.getColor(c, R.color.fh_form_accent));
                    paint.setStrokeWidth(dp(c, 2));
                    canvas.save();
                    if (!left) canvas.scale(-1, 1, getWidth()/2f, getHeight()/2f);
                    if (!top) canvas.scale(1, -1, getWidth()/2f, getHeight()/2f);
                    canvas.drawLine(dp(c, 5), dp(c, 14), dp(c, 14), dp(c, 5), paint);
                    canvas.drawLine(dp(c, 5), dp(c, 19), dp(c, 19), dp(c, 5), paint);
                    canvas.restore();
                }
            };
            handle.setContentDescription("Resize editor " + (top ? "top " : "bottom ") + (left ? "left" : "right"));
            android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(dp(c, 24), dp(c, 24),
                    (top ? android.view.Gravity.TOP : android.view.Gravity.BOTTOM)
                            | (left ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT));
            root.addView(handle, hp);
            handle.setOnTouchListener(new View.OnTouchListener() {
                float startX, startY; int startLeft, startTop, startWidth, startHeight;
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        int[] location = new int[2]; root.getLocationOnScreen(location);
                        startLeft = location[0]; startTop = location[1];
                        startWidth = root.getWidth(); startHeight = root.getHeight();
                        startX = event.getRawX(); startY = event.getRawY(); return true;
                    }
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        root.getWindowVisibleDisplayFrame(bounds);
                        int dx = Math.round(event.getRawX() - startX), dy = Math.round(event.getRawY() - startY);
                        int availableWidth = left ? startLeft + startWidth - bounds.left : bounds.right - startLeft;
                        int availableHeight = top ? startTop + startHeight - bounds.top : bounds.bottom - startTop;
                        int width = Math.max(Math.min(dp(c, 280), availableWidth),
                                Math.min(availableWidth, startWidth + (left ? -dx : dx)));
                        int chrome = header.getHeight() + actions.getHeight() + dp(c, 36);
                        int height = Math.max(Math.min(chrome + dp(c, 64), availableHeight),
                                Math.min(availableHeight, startHeight + (top ? -dy : dy)));
                        android.view.WindowManager.LayoutParams params = window.getAttributes();
                        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                        params.x = (left ? startLeft + startWidth - width : startLeft) - bounds.left;
                        params.y = (top ? startTop + startHeight - height : startTop) - bounds.top;
                        params.width = width; params.height = height;
                        resized[0] = true; window.setAttributes(params); return true;
                    }
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) { v.performClick(); return true; }
                    return event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL;
                }
            });
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v -> dialog.dismiss()); close.setOnClickListener(v -> dialog.dismiss());
        update.setOnClickListener(v -> save.run());
        followPanel(anchor, dialog);
        return dialog;
    }
    public static void grocery(View anchor,GroceryItem item,GroceryRepository repository,Runnable refresh) {
        Context c=themed(anchor); LinearLayout box=form(c); LinearLayout details=form(c);
        EditText name=field(box,"Item name",item.name);
        String[] parts=item.quantity.trim().split("\\s+",2);
        EditText quantity=field(box,"Amount",parts.length>0?parts[0]:"");
        quantity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        String originalUnit=parts.length>1?parts[1]:"";
        java.util.LinkedHashSet<String> units=new java.util.LinkedHashSet<>();
        units.add(originalUnit);
        java.util.Collections.addAll(units,c.getResources().getStringArray(R.array.grocery_quantity_units));
        AutoCompleteTextView unit=choice(box,"Unit",originalUnit,units.toArray(new String[0]));
        String cycle = GroceryRecurrenceEngine.originalCycle(item);
        String cycleLabel = GroceryItem.LIST_MONTHLY.equals(cycle) ? "Monthly"
                : GroceryItem.LIST_WEEKLY.equals(cycle) ? "Weekly"
                : GroceryItem.LIST_FORTNIGHTLY.equals(cycle) ? "Fortnightly" : "Daily";
        // Cycle is a summary only: this layout update must not change recurrence/history.
        TextView cycleSummary = new TextView(c); cycleSummary.setText(cycleLabel);
        cycleSummary.setGravity(android.view.Gravity.CENTER); cycleSummary.setMinHeight(dp(c, 48));
        cycleSummary.setTextSize(12); cycleSummary.setBackgroundResource(R.drawable.bg_form_floating_shell);
        row(box, quantity, unit, cycleSummary);
        java.util.LinkedHashSet<String> categories=new java.util.LinkedHashSet<>();
        if(!item.category.isEmpty())categories.add(item.category);
        java.util.Collections.addAll(categories,com.tridev.familyhub.feature.grocery.GroceryOptionCatalog.categoryLabels(c));
        AutoCompleteTextView category=choice(details,"Category",item.category,categories.toArray(new String[0]));
        EditText notes=field(details,"Notes",item.notes);
        AutoCompleteTextView priority=choice(details,"Priority",item.priority,
                new String[]{GroceryItem.PRIORITY_NORMAL,GroceryItem.PRIORITY_HIGH,GroceryItem.PRIORITY_URGENT});
        final AlertDialog[] dialog={null};
        final String originalName=item.name;
        dialog[0]=show(anchor,"Edit Grocery",box,details,()->{
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
        Context c=themed(anchor); LinearLayout box=form(c); LinearLayout details=form(c);
        EditText title=field(box,"Task title",task.title);
        FamilyTaskSubtasks.Content content=FamilyTaskSubtasks.decode(task.notes);
        EditText notes=field(details,"Notes",content.notes);
        FamilyTaskSubtaskEditor subtasks=new FamilyTaskSubtaskEditor(c);subtasks.setContent(content);subtasks.useExternalModeControl();details.addView(subtasks);
        AutoCompleteTextView mode=choice(box,"Type",subtasks.isMultiple()?"Subtasks":"Single",new String[]{"Single","Subtasks"});
        mode.setOnItemClickListener((parent,view,position,id)->{
            subtasks.setMultiple(position==1);
            if(position==1 && details.getVisibility()!=View.VISIBLE && details.getTag() instanceof View)
                ((View)details.getTag()).performClick();
        });
        AutoCompleteTextView priority=choice(details,"Priority",task.priority,
                new String[]{FamilyTask.PRIORITY_NORMAL,FamilyTask.PRIORITY_HIGH,FamilyTask.PRIORITY_URGENT});
        AutoCompleteTextView repeat=choice(details,"Repeat",task.repeatType,
                new String[]{FamilyTask.REPEAT_NONE,FamilyTask.REPEAT_DAILY,FamilyTask.REPEAT_WEEKLY,FamilyTask.REPEAT_MONTHLY});
        pair(details,priority,repeat);
        com.google.android.material.checkbox.MaterialCheckBox reminder=new com.google.android.material.checkbox.MaterialCheckBox(c);
        reminder.setText("Reminder enabled");reminder.setChecked(task.reminderEnabled);details.addView(reminder);
        java.util.LinkedHashSet<String> leadValues=new java.util.LinkedHashSet<>();
        leadValues.add(String.valueOf(task.reminderMinutesBefore));
        java.util.Collections.addAll(leadValues,"0","5","10","15","30","60","1440");
        AutoCompleteTextView lead=choice(details,"Remind minutes before",String.valueOf(task.reminderMinutesBefore),leadValues.toArray(new String[0]));
        final long[] due={task.dueAt};
        com.google.android.material.button.MaterialButton date=chip(c, "");
        com.google.android.material.button.MaterialButton time=chip(c, "");
        Runnable update=()->{
            java.util.Calendar day=java.util.Calendar.getInstance();day.setTimeInMillis(due[0]);
            java.util.Calendar today=java.util.Calendar.getInstance();
            boolean isToday=day.get(java.util.Calendar.YEAR)==today.get(java.util.Calendar.YEAR)
                    && day.get(java.util.Calendar.DAY_OF_YEAR)==today.get(java.util.Calendar.DAY_OF_YEAR);
            date.setText((isToday?"Today":android.text.format.DateFormat.format("dd MMM yyyy",due[0]))+" ▾");
            time.setText(android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(due[0]))+" ▾");
        };
        update.run();row(box,mode,date,time);
        date.setOnClickListener(v->{
            java.util.Calendar calendar=java.util.Calendar.getInstance();calendar.setTimeInMillis(due[0]);
            android.app.DatePickerDialog picker=new android.app.DatePickerDialog(c,(d,year,month,day)->{
                calendar.set(year,month,day);due[0]=calendar.getTimeInMillis();update.run();
            },calendar.get(java.util.Calendar.YEAR),calendar.get(java.util.Calendar.MONTH),calendar.get(java.util.Calendar.DAY_OF_MONTH));
            picker.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);picker.show();followPanel(owner,picker);
        });
        time.setOnClickListener(v->{
            java.util.Calendar calendar=java.util.Calendar.getInstance();calendar.setTimeInMillis(due[0]);
            android.app.TimePickerDialog picker=new android.app.TimePickerDialog(c,(t,h,m)->{
                calendar.set(java.util.Calendar.HOUR_OF_DAY,h);calendar.set(java.util.Calendar.MINUTE,m);
                calendar.set(java.util.Calendar.SECOND,0);calendar.set(java.util.Calendar.MILLISECOND,0);
                due[0]=calendar.getTimeInMillis();update.run();
            },calendar.get(java.util.Calendar.HOUR_OF_DAY),calendar.get(java.util.Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(c));
            picker.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);picker.show();followPanel(owner,picker);
        });
        final AlertDialog[] dialog={null};
        dialog[0]=show(anchor,"Edit To-Do",box,details,()->{
            String name=title.getText().toString().trim();
            if(name.isEmpty()){title.setError("Required");return;}
            if(!subtasks.validate()) {
                if(details.getVisibility()!=View.VISIBLE) ((View)details.getTag()).performClick();
                return;
            }
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
