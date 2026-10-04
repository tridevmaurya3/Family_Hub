package com.tridev.familyhub.feature.tasks;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;
import com.tridev.familyhub.R;

/** Shared compact editor used by the task dialog and floating quick-add form. */
public final class FamilyTaskSubtaskEditor extends LinearLayout {
    private final RadioGroup modes;
    private final RadioButton single;
    private final RadioButton multiple;
    private final LinearLayout details;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final List<EditText> inputs = new ArrayList<>();
    private final List<CheckBox> checks = new ArrayList<>();

    public FamilyTaskSubtaskEditor(Context context) {
        super(context);
        setOrientation(VERTICAL);
        modes = new RadioGroup(context);
        modes.setOrientation(HORIZONTAL);
        single = option(R.string.task_structure_single);
        multiple = option(R.string.task_structure_multiple);
        modes.addView(single, new RadioGroup.LayoutParams(0, -2, 1f));
        modes.addView(multiple, new RadioGroup.LayoutParams(0, -2, 1f));
        addView(modes, new LayoutParams(-1, -2));

        details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        details.addView(scroll, new LayoutParams(-1, -2));
        Button add = new Button(context);
        add.setText(R.string.task_subtask_add);
        add.setTextSize(12);
        add.setAllCaps(false);
        details.addView(add, new LayoutParams(-1, dp(44)));
        addView(details, new LayoutParams(-1, -2));
        add.setOnClickListener(v -> { addRow(""); inputs.get(inputs.size() - 1).requestFocus(); });
        modes.setOnCheckedChangeListener((group, id) -> {
            boolean multi = id == multiple.getId();
            details.setVisibility(multi ? VISIBLE : GONE);
            // Switching modes retains draft rows until the form is saved.
            if (multi && inputs.isEmpty()) addRow("");
        });
        single.setChecked(true);
    }

    private RadioButton option(int label) {
        RadioButton button = new RadioButton(getContext());
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(12);
        button.setMinHeight(dp(44));
        return button;
    }

    public void setItems(List<String> items) {
        rows.removeAllViews();
        inputs.clear();
        checks.clear();
        for (String item : items) addRow(item);
        if (items.isEmpty()) single.setChecked(true); else multiple.setChecked(true);
        updateHeight();
    }

    private void addRow(String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        EditText input = new EditText(getContext());
        input.setTextSize(12);
        input.setIncludeFontPadding(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setHint(R.string.task_subtask_hint);
        input.setText(value);
        CheckBox check = new CheckBox(getContext());
        check.setContentDescription("Complete subtask");
        row.addView(check, new LayoutParams(dp(42), dp(48)));
        row.addView(input, new LayoutParams(0, dp(48), 1f));
        checks.add(check);
        check.setOnCheckedChangeListener((button, checked) -> FamilyTaskSubtaskList.strike(input, checked));
        Button remove = new Button(getContext());
        remove.setText("×");
        remove.setContentDescription(getContext().getString(R.string.task_subtask_remove));
        remove.setMinWidth(0);
        remove.setPadding(0, 0, 0, 0);
        row.addView(remove, new LayoutParams(dp(48), dp(48)));
        rows.addView(row, new LayoutParams(-1, -2));
        inputs.add(input);
        remove.setOnClickListener(v -> {
            checks.remove(check);
            inputs.remove(input);
            rows.removeView(row);
            updateHeight();
        });
        updateHeight();
    }

    private void updateHeight() {
        LayoutParams params = (LayoutParams) scroll.getLayoutParams();
        params.height = dp(Math.min(3, Math.max(1, inputs.size())) * 48);
        scroll.setLayoutParams(params);
    }

    public boolean validate() {
        if (single.isChecked()) return true;
        if (inputs.isEmpty()) addRow("");
        for (EditText input : inputs) {
            if (input.getText().toString().trim().isEmpty()) {
                input.setError(getContext().getString(R.string.task_subtask_required));
                input.requestFocus();
                return false;
            }
        }
        return true;
    }

    public List<String> getItems() {
        List<String> items = new ArrayList<>();
        if (multiple.isChecked()) {
            for (EditText input : inputs) items.add(input.getText().toString().trim());
        }
        return items;
    }

    public void setContent(FamilyTaskSubtasks.Content content) {
        setItems(content.items);
        for (int i = 0; i < checks.size(); i++) checks.get(i).setChecked(content.completed.get(i));
    }

    public List<Boolean> getCompleted() {
        List<Boolean> completed = new ArrayList<>();
        if (multiple.isChecked()) for (CheckBox check : checks) completed.add(check.isChecked());
        return completed;
    }

    public void useExternalModeControl() { modes.setVisibility(GONE); }

    public void setMultiple(boolean value) {
        if (value) multiple.setChecked(true); else single.setChecked(true);
    }

    public boolean isMultiple() { return multiple.isChecked(); }

    public void reset() { setItems(new ArrayList<>()); }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
