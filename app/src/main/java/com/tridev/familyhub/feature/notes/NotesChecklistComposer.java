package com.tridev.familyhub.feature.notes;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.core.ui.CompactFormStyle;
import com.tridev.familyhub.R;
import java.util.ArrayList;
import java.util.List;

/** An item editor backed by the existing newline checklist content, with no new storage path. */
final class NotesChecklistComposer extends LinearLayout {
    private final TextInputEditText source, draft;
    private final View textField;
    private final LinearLayout rows;
    private final TextView progress;
    private List<NotesChecklist.Item> items = new ArrayList<>();
    private boolean writing;

    NotesChecklistComposer(Context context, TextInputEditText source, View textField) {
        super(context);
        this.source = source; this.textField = textField;
        setOrientation(VERTICAL);
        LinearLayout entry = new LinearLayout(context); entry.setGravity(Gravity.CENTER_VERTICAL);
        TextInputLayout field = new TextInputLayout(context);
        draft = new TextInputEditText(context);
        draft.setHint(R.string.notes_checklist_add_hint);
        draft.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draft.setMinLines(1); draft.setMaxLines(3);
        field.addView(draft, new LayoutParams(-1, -2));
        field.setHintEnabled(false);
        entry.addView(field, new LayoutParams(0, -2, 1));
        MaterialButton add = button("+", getResources().getString(R.string.notes_checklist_add_hint));
        entry.addView(add, new LayoutParams(dp(48), dp(48)));
        add.setOnClickListener(v -> {
            List<NotesChecklist.Item> added = NotesChecklist.parse(text(draft));
            if (added.isEmpty()) { showEmptyError(); return; }
            draft.setError(null); items.addAll(added); write(); draft.setText(""); render();
        });
        addView(entry);
        LinearLayout tools = new LinearLayout(context); tools.setGravity(Gravity.CENTER_VERTICAL);
        progress = new TextView(context); progress.setTextSize(11);
        tools.addView(progress, new LayoutParams(0, -2, 1));
        MaterialButton all = button("All", getResources().getString(R.string.notes_checklist_toggle_all));
        MaterialButton clear = button("Clear", getResources().getString(R.string.notes_checklist_clear_done));
        tools.addView(all, new LayoutParams(dp(48), dp(48)));
        tools.addView(clear, new LayoutParams(dp(48), dp(48)));
        all.setOnClickListener(v -> {
            boolean checked = completed() < items.size();
            for (int i = 0; i < items.size(); i++) items.set(i, new NotesChecklist.Item(items.get(i).text, checked));
            write(); render();
        });
        clear.setOnClickListener(v -> { items.removeIf(item -> item.checked); write(); render(); });
        addView(tools);
        rows = new LinearLayout(context); rows.setOrientation(VERTICAL); addView(rows);
        source.addTextChangedListener(watcher(() -> { if (!writing) { items = NotesChecklist.parse(text(source)); render(); } }));
        items = NotesChecklist.parse(text(source)); render();
        CompactFormStyle.applyInputs(this);
        setChecklist(false);
    }

    void setChecklist(boolean checklist) {
        if (!checklist) commitPending();
        textField.setVisibility(checklist ? GONE : VISIBLE);
        setVisibility(checklist ? VISIBLE : GONE);
    }
    void clearDraft() { draft.setText(""); draft.setError(null); }
    void commitPending() {
        List<NotesChecklist.Item> added = NotesChecklist.parse(text(draft));
        if (!added.isEmpty()) { items.addAll(added); write(); draft.setText(""); render(); }
    }
    void showEmptyError() {
        draft.setError(getResources().getString(R.string.notes_quick_items_required)); draft.requestFocus();
    }
    private void render() {
        rows.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            NotesChecklist.Item item = items.get(i);
            LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(getContext()); check.setChecked(item.checked);
            check.setContentDescription(item.text);
            row.addView(check, new LayoutParams(dp(44), dp(48)));
            TextInputEditText title = new TextInputEditText(getContext());
            title.setText(item.text); title.setTextSize(12); title.setSingleLine(true);
            title.setContentDescription(getResources().getString(R.string.notes_checklist_item));
            if (item.checked) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            row.addView(title, new LayoutParams(0, -2, 1));
            title.addTextChangedListener(watcher(() -> {
                items.set(index, new NotesChecklist.Item(text(title).replace('\n', ' ').replace('\r', ' '), items.get(index).checked)); write();
                check.setContentDescription(text(title));
            }));
            check.setOnCheckedChangeListener((button, checked) -> {
                items.set(index, new NotesChecklist.Item(items.get(index).text, checked)); write();
                int flags = title.getPaintFlags();
                title.setPaintFlags(checked ? flags | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        : flags & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            });
            MaterialButton up = button("↑", getResources().getString(R.string.notes_checklist_move_up));
            up.setEnabled(index > 0); row.addView(up, new LayoutParams(dp(44), dp(48)));
            up.setOnClickListener(v -> { java.util.Collections.swap(items, index, index - 1); write(); render(); });
            MaterialButton remove = button("×", getResources().getString(R.string.remove));
            row.addView(remove, new LayoutParams(dp(44), dp(48)));
            remove.setOnClickListener(v -> { items.remove(index); write(); render(); });
            rows.addView(row);
        }
        updateProgress();
    }
    private void write() {
        StringBuilder content = new StringBuilder();
        for (NotesChecklist.Item item : items) {
            if (item.text.trim().isEmpty()) continue;
            if (content.length() > 0) content.append('\n');
            content.append(item.checked ? "[x] " : "[ ] ").append(item.text.trim());
        }
        writing = true;
        try { source.setText(content); } finally { writing = false; }
        updateProgress();
    }
    private int completed() { int n = 0; for (NotesChecklist.Item item : items) if (item.checked) n++; return n; }
    private void updateProgress() {
        String content = text(source);
        progress.setText(getResources().getString(R.string.notes_checklist_progress,
                NotesChecklist.completed(content), NotesChecklist.parse(content).size()));
    }
    private MaterialButton button(String text, String description) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(text); button.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, description);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0); button.setInsetBottom(0); return button;
    }
    private static String text(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private static TextWatcher watcher(Runnable change) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { change.run(); }
            public void afterTextChanged(Editable text) { }
        };
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
