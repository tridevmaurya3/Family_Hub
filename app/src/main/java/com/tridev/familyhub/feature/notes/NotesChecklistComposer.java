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
    private final android.widget.ProgressBar progressBar;
    private Runnable draftChanged;
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
        MaterialButton clear = button("Clear done", getResources().getString(R.string.notes_checklist_clear_done));
        tools.addView(all, new LayoutParams(dp(48), dp(48)));
        clear.setTextSize(10); tools.addView(clear, new LayoutParams(dp(80), dp(48)));
        all.setOnClickListener(v -> {
            boolean checked = completed() < items.size();
            for (int i = 0; i < items.size(); i++) items.set(i, new NotesChecklist.Item(items.get(i).text, checked));
            write(); render();
        });
        clear.setOnClickListener(v -> { items.removeIf(item -> item.checked); write(); render(); });
        rows = new LinearLayout(context); rows.setOrientation(VERTICAL); addView(rows);
        addView(tools);
        progressBar = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(148, 118, 212)));
        addView(progressBar, new LayoutParams(-1, dp(4)));
        draft.addTextChangedListener(watcher(() -> { if (draftChanged != null) draftChanged.run(); }));
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
    String pendingDraft() { return text(draft); }
    void setPendingDraft(String text) { draft.setText(text); }
    void setDraftChangedListener(Runnable listener) { draftChanged = listener; }
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
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(140, 106, 204)));
            row.addView(check, new LayoutParams(dp(44), dp(48)));
            TextInputEditText title = new TextInputEditText(getContext());
            title.setText(item.text); title.setTextSize(12); title.setSingleLine(true);
            title.setContentDescription(getResources().getString(R.string.notes_checklist_item));
            if (item.checked) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            title.setBackground(null); row.addView(title, new LayoutParams(0, -2, 1));
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
            MaterialButton up = button("⠿", getResources().getString(R.string.notes_checklist_move_up));
            up.setStrokeWidth(0); up.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            row.addView(up, 0, new LayoutParams(dp(32), dp(48)));
            up.setOnClickListener(v -> { if (index > 0) { java.util.Collections.swap(items, index, index - 1); write(); render(); } });
            MaterialButton remove = button("×", getResources().getString(R.string.remove));
            remove.setStrokeWidth(0); remove.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            row.addView(remove, new LayoutParams(dp(44), dp(48)));
            remove.setOnClickListener(v -> { items.remove(index); write(); render(); });
            up.setOnLongClickListener(v -> v.startDragAndDrop(android.content.ClipData.newPlainText("note-item", ""),
                    new View.DragShadowBuilder(row), Integer.valueOf(index), 0));
            row.setOnDragListener((v, event) -> {
                if (!(event.getLocalState() instanceof Integer)) return false;
                if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                    int from = (Integer) event.getLocalState();
                    if (from >= 0 && from < items.size() && index < items.size()) {
                        NotesChecklist.Item moved = items.remove(from); items.add(index, moved); write(); render();
                    }
                }
                return true;
            });
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(android.graphics.Color.rgb(250, 248, 255)); background.setCornerRadius(dp(10));
            background.setStroke(dp(1), android.graphics.Color.rgb(232, 224, 245)); row.setBackground(background);
            LayoutParams rowParams = new LayoutParams(-1, -2); rowParams.topMargin = dp(4);
            rows.addView(row, rowParams);
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
        progressBar.setMax(Math.max(1, NotesChecklist.parse(content).size()));
        progressBar.setProgress(NotesChecklist.completed(content));
        progress.setText(getResources().getString(R.string.notes_checklist_progress,
                NotesChecklist.completed(content), NotesChecklist.parse(content).size()));
    }
    private MaterialButton button(String text, String description) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(text); button.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, description);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0); button.setInsetBottom(0); button.setCornerRadius(dp(12));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(248, 245, 253)));
        button.setTextColor(android.graphics.Color.rgb(124, 88, 180)); button.setStrokeWidth(dp(1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(226, 217, 241)));
        return button;
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
