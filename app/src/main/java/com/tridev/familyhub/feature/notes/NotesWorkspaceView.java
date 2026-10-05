package com.tridev.familyhub.feature.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.CompactFormStyle;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.data.repository.NotesRepository;
import java.util.ArrayList;
import java.util.List;

/** One smart workspace for the normal Notes page and the floating Notes panel. */
public final class NotesWorkspaceView extends com.tridev.familyhub.core.ui.ScrollableWorkspaceLayout {
    public interface EditorHost { void edit(NoteEntry note); }
    private final NotesRepository repository;
    private final EditorHost editor;
    private final boolean overlay;
    private final NotesAdapter adapter;
    private final TextView summary;
    private final TextView empty;
    private final TextInputEditText search;
    private final TextInputEditText quick;
    private List<NoteEntry> notes = new ArrayList<>();
    private int status, type, sort, quickType, generation;
    private String category = "";
    private boolean active;

    public NotesWorkspaceView(Context context, NotesRepository repository, boolean overlay, EditorHost editor) {
        super(context);
        this.repository = repository; this.overlay = overlay; this.editor = editor;
        setOrientation(VERTICAL);
        LinearLayout filters = row();
        dropdown(filters, new String[]{"All active", "Pending", "Completed", "Pinned", "Archived"},
                position -> { status = position; reload(); });
        LinearLayout typeFilters = row();
        dropdown(typeFilters, new String[]{"All types", "Text note", "Checklist"},
                position -> { type = position; render(); });
        dropdown(filters, new String[]{"Pinned / Latest", "Title A–Z", "Reminder first"},
                position -> { sort = position; render(); });
        addView(filters, new LayoutParams(-1, -2));
        LinearLayout tools = row();
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint(getResources().getString(R.string.notes_search_hint));
        search = new TextInputEditText(context);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setSingleLine(true);
        searchLayout.addView(search, new LayoutParams(-1, -2));
        tools.addView(searchLayout, new LayoutParams(0, -2, 1f));
        String[] categories = getResources().getStringArray(R.array.notes_category_labels);
        String[] categoryChoices = new String[categories.length + 1];
        categoryChoices[0] = "All categories";
        System.arraycopy(categories, 0, categoryChoices, 1, categories.length);
        dropdown(typeFilters, categoryChoices, position -> { category = position == 0 ? "" : categories[position - 1]; render(); });
        addView(typeFilters, new LayoutParams(-1, -2));
        addView(tools, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable text) { }
        });
        LinearLayout quickRow = row();
        TextInputLayout quickLayout = new TextInputLayout(context);
        quickLayout.setHint(getResources().getString(R.string.notes_quick_add));
        quick = new TextInputEditText(context);
        quick.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        quick.setSingleLine(true);
        quickLayout.addView(quick, new LayoutParams(-1, -2));
        quickRow.addView(quickLayout, new LayoutParams(0, -2, 1.5f));
        dropdown(quickRow, new String[]{"Text note", "Checklist"}, position -> quickType = position);
        MaterialButton add = new MaterialButton(context);
        add.setText("+"); add.setContentDescription(getResources().getString(R.string.notes_add));
        add.setMinWidth(0); add.setMinimumWidth(0); add.setPadding(0, 0, 0, 0);
        add.setTextSize(24);
        LayoutParams addParams = new LayoutParams(dp(44), dp(44));
        addParams.setMarginStart(dp(4)); quickRow.addView(add, addParams);
        addView(quickRow, new LayoutParams(-1, -2));
        add.setOnClickListener(v -> {
            String title = value(quick);
            if (title.isEmpty()) { quickLayout.setError(getResources().getString(R.string.notes_title_required)); return; }
            quickLayout.setError(null);
            NoteEntry note = new NoteEntry(); note.title = title;
            note.noteType = quickType == 1 ? NoteEntry.TYPE_CHECKLIST : NoteEntry.TYPE_TEXT;
            note.category = categories[0]; note.collaborationStatus = "PENDING";
            if (quickType == 1) editor.edit(note);
            else {
                add.setEnabled(false);
                repository.save(note, () -> { add.setEnabled(true); if (active) { quick.setText(""); reload(); } });
            }
        });
        MaterialButton detail = new MaterialButton(context);
        detail.setText(R.string.notes_full_editor);
        detail.setOnClickListener(v -> editor.edit(null));
        addView(detail, new LayoutParams(-1, dp(40)));
        summary = label("", 11); addView(summary, new LayoutParams(-1, -2));
        empty = label(getResources().getString(R.string.notes_no_matching), 12);
        addView(empty, new LayoutParams(-1, -2));
        adapter = new NotesAdapter(new NotesAdapter.NoteActionListener() {
            public void onEdit(NoteEntry note) { editor.edit(note); }
            public void onPinnedChanged(NoteEntry note, boolean pinned) { repository.setPinned(note, pinned, NotesWorkspaceView.this::reload); }
            public void onArchivedChanged(NoteEntry note, boolean archived) { repository.setArchived(note, archived, NotesWorkspaceView.this::reload); }
            public void onDelete(NoteEntry note) {
                androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                        .setTitle(R.string.notes_delete_title)
                        .setMessage(getResources().getString(R.string.notes_delete_message, note.title))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.remove, (d, which) -> repository.delete(note, NotesWorkspaceView.this::reload)).create();
                NotesEditor.present(dialog, overlay);
            }
            public void onStatusChanged(NoteEntry note, boolean completed) {
                note.collaborationStatus = completed ? "COMPLETED" : "PENDING";
                if (NoteEntry.TYPE_CHECKLIST.equals(note.noteType))
                    note.content = NotesChecklist.setAll(note.content, completed);
                repository.save(note, NotesWorkspaceView.this::reload);
            }
            public void onChecklistChanged(NoteEntry note, int index, boolean checked) {
                note.content = NotesChecklist.toggle(note.content, index, checked);
                int total = NotesChecklist.parse(note.content).size();
                if (total > 0 && NotesChecklist.completed(note.content) == total) note.collaborationStatus = "COMPLETED";
                else if (NotesSmartFilter.completed(note)) note.collaborationStatus = "PENDING";
                repository.save(note, NotesWorkspaceView.this::reload);
            }
        });
        RecyclerView list = (RecyclerView) android.view.LayoutInflater.from(context)
                .inflate(R.layout.notes_workspace_list, this, false);
        list.setLayoutManager(new LinearLayoutManager(context)); list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(16));
        addView(list, new LayoutParams(-1, 0, 1f));
        makeControlsScrollable(0, indexOfChild(list));
        CompactFormStyle.applyInputs(this);
        detail.setCornerRadius(dp(14)); add.setCornerRadius(dp(14));
    }
    private interface Selection { void selected(int position); }
    private void dropdown(LinearLayout parent, String[] labels, Selection callback) {
        TextInputLayout layout = new TextInputLayout(getContext());
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        layout.setHint(labels[0]);
        MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(getContext());
        input.setInputType(InputType.TYPE_NULL);
        input.setAdapter(new android.widget.ArrayAdapter<>(getContext(), R.layout.item_form_dropdown, labels));
        input.setText(labels[0], false);
        input.setOnItemClickListener((p, view, position, id) -> callback.selected(position));
        layout.addView(input, new LayoutParams(-1, -2));
        LayoutParams params = new LayoutParams(0, -2, 1f);
        params.setMarginStart(parent.getChildCount() == 0 ? 0 : dp(4));
        parent.addView(layout, params);
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView label(String text, int size) { TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private static String value(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public void clearQuickAdd() { quick.setText(""); }
    public void activate() { if (active) return; active = true; repository.startRealtimeSync(this::reload); reload(); }
    public void deactivate() { active = false; generation++; repository.stopRealtimeSync(); }
    public void reload() {
        if (!active) return;
        final int request = ++generation;
        NotesRepository.NotesCallback callback = loaded -> { if (active && request == generation) { notes = loaded; render(); } };
        if (status == 4) repository.loadArchived(callback); else repository.loadActive("", callback);
    }
    private void render() {
        if (adapter == null) return;
        List<NoteEntry> visible = NotesSmartFilter.apply(notes, status, type, category, sort, value(search));
        adapter.submitList(visible);
        int pending = 0, pinned = 0;
        for (NoteEntry note : notes) { if (!note.isArchived && !NotesSmartFilter.completed(note)) pending++; if (note.isPinned) pinned++; }
        summary.setText(visible.size() + " shown • " + pending + " pending • " + pinned + " pinned");
        empty.setVisibility(visible.isEmpty() ? VISIBLE : GONE);
    }
}
