package com.tridev.familyhub.feature.tasks;

import android.graphics.Paint;
import android.widget.CheckBox;
import android.widget.LinearLayout;

/** Checkbox rows shared by the To-Do cards and floating task list. */
public final class FamilyTaskSubtaskList {
    public interface Listener { void onChecked(int index, boolean checked); }

    public static void bind(LinearLayout container, FamilyTaskSubtasks.Content content,
                            Listener listener) {
        container.removeAllViews();
        for (int i = 0; i < content.items.size(); i++) {
            final int index = i;
            CheckBox check = new CheckBox(container.getContext());
            check.setText(content.items.get(i));
            check.setTextSize(12);
            check.setChecked(content.completed.get(i));
            strike(check, check.isChecked());
            container.addView(check, new LinearLayout.LayoutParams(-1, -2));
            check.setOnCheckedChangeListener((button, checked) -> {
                strike(check, checked);
                listener.onChecked(index, checked);
            });
        }
    }

    public static void strike(android.widget.TextView view, boolean completed) {
        int flags = view.getPaintFlags();
        view.setPaintFlags(completed ? flags | Paint.STRIKE_THRU_TEXT_FLAG
                : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    private FamilyTaskSubtaskList() { }
}
