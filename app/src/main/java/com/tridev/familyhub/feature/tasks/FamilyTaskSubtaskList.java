package com.tridev.familyhub.feature.tasks;

import android.graphics.Paint;
import android.widget.TextView;
import android.view.Gravity;
import com.google.android.material.checkbox.MaterialCheckBox;
import android.widget.LinearLayout;

/** Checkbox rows shared by the To-Do cards and floating task list. */
public final class FamilyTaskSubtaskList {
    public interface Listener { void onChecked(int index, boolean checked); }

    public static void bind(LinearLayout container, FamilyTaskSubtasks.Content content,
                            Listener listener) {
        container.removeAllViews();
        for (int i = 0; i < content.items.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(container.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int columnWidth = Math.round(42 * container.getResources().getDisplayMetrics().density);
            MaterialCheckBox check = new MaterialCheckBox(new android.view.ContextThemeWrapper(
                    container.getContext(), com.tridev.familyhub.R.style.Theme_FamilyHub));
            int rowHeight = Math.round(32 * container.getResources().getDisplayMetrics().density);
            check.setMinHeight(0);
            check.setMinimumHeight(0);
            check.setPadding(0, 0, 0, 0);
            TextView label = new TextView(container.getContext());
            label.setText(content.items.get(i));
            label.setTextSize(12);
            check.setChecked(content.completed.get(i));
            check.setContentDescription(content.items.get(i));
            strike(label, check.isChecked());
            row.addView(check, new LinearLayout.LayoutParams(columnWidth, rowHeight));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
            labelParams.setMarginStart(Math.round(4 * container.getResources().getDisplayMetrics().density));
            row.addView(label, labelParams);
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
            check.setOnCheckedChangeListener((button, checked) -> {
                strike(label, checked);
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
