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
    private TextInputEditText quickContent;
    private int quickCategory, quickSharing;
    private NotesRepository.SyncStatusCallback syncStatusListener;
    private boolean liveSync, connectingSync;
    private boolean quickSaving;
    private List<NoteEntry> notes = new ArrayList<>();
    private int status, type, sort, quickType, generation;
    private String category = "";
    private boolean active;
    private final LinearLayout filterRow, searchRow, categoryFilter;
    private final int[] filterWidths = new int[4];
    private int filterLayoutWidth = -1;

    public NotesWorkspaceView(Context context, NotesRepository repository, boolean overlay, EditorHost editor) {
        super(context);
        this.repository = repository; this.overlay = overlay; this.editor = editor;
        setOrientation(VERTICAL);
        LinearLayout filters = row();
        filterRow = filters;
        dropdown(filters, "Status", new String[]{"All active", "Pending", "Completed", "Pinned", "Archived"},
                position -> { status = position; reload(); });
        dropdown(filters, "Sort", new String[]{"Pinned / Latest", "Title A–Z", "Reminder first"},
                position -> { sort = position; render(); });
        dropdown(filters, "Type", new String[]{"All types", "Text note", "Checklist"},
                position -> { type = position; render(); });
        addView(filters, new LayoutParams(-1, -2));
        LinearLayout tools = row();
        searchRow = tools;
        tools.setGravity(Gravity.BOTTOM);
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint(getResources().getString(R.string.notes_search_hint));
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        search = new TextInputEditText(context);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setSingleLine(true);
        search.setHint(R.string.notes_search_hint);
        searchLayout.addView(search, new LayoutParams(-1, -2));
        // Material requires its EditText to be attached before disabling hints.
        searchLayout.setHintEnabled(false);
        tools.addView(searchLayout, new LayoutParams(0, -2, 1f));
        String[] categories = getResources().getStringArray(R.array.notes_category_labels);
        String[] categoryChoices = new String[categories.length + 1];
        categoryChoices[0] = "All categories";
        System.arraycopy(categories, 0, categoryChoices, 1, categories.length);
        categoryFilter = dropdown(filters, "Category", categoryChoices, position -> { category = position == 0 ? "" : categories[position - 1]; render(); });
        String[][] filterChoices = {
                {"All active", "Pending", "Completed", "Pinned", "Archived"},
                {"Pinned / Latest", "Title A–Z", "Reminder first"},
                {"All types", "Text note", "Checklist"}, categoryChoices};
        String[] filterNames = {"Status", "Sort", "Type", "Category"};
        for (int i = 0; i < filterWidths.length; i++) {
            String[] compactChoices = new String[filterChoices[i].length];
            for (int j = 0; j < compactChoices.length; j++)
                compactChoices[j] = compactFilterLabel(filterNames[i], filterChoices[i][j]);
            filterWidths[i] = dropdownWidth(context, compactChoices);
        }
        addView(tools, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable text) { }
        });
        LinearLayout quickRow = row();
        TextInputLayout quickLayout = new TextInputLayout(context);
        quickLayout.setHint(getResources().getString(R.string.notes_quick_add));
        quickLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        quick = new TextInputEditText(context);
        quick.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        quick.setSingleLine(true);
        quick.setHint(R.string.notes_quick_add);
        quickLayout.addView(quick, new LayoutParams(-1, -2));
        // Material requires its EditText to be attached before disabling hints.
        quickLayout.setHintEnabled(false);
        LinearLayout quickBlock = new LinearLayout(context);
        quickBlock.setOrientation(VERTICAL);
        quickBlock.addView(label("Note title", 10));
        quickBlock.addView(quickLayout, new LayoutParams(-1, dp(48)));
        quickRow.addView(quickBlock, new LayoutParams(0, -2, 1.7f));
        dropdown(quickRow, "Note type", new String[]{"Text note", "Checklist"}, position -> {
            quickType = position;
            if (quickContent != null) quickContent.setHint(position == 1
                    ? R.string.notes_quick_checklist_hint : R.string.notes_quick_content_hint);
        });
        MaterialButton add = new MaterialButton(context);
        add.setText("+"); add.setContentDescription(getResources().getString(R.string.notes_add));
        add.setMinWidth(0); add.setMinimumWidth(0); add.setPadding(0, 0, 0, 0);
        add.setTextSize(24); add.setCornerRadius(dp(24));
        add.setInsetTop(0); add.setInsetBottom(0);
        LayoutParams addParams = new LayoutParams(dp(48), dp(48));
        LinearLayout addBlock = new LinearLayout(context);
        addBlock.setOrientation(VERTICAL);
        // Give the button the same label space as its neighbouring fields.
        addBlock.addView(label(" ", 10));
        addBlock.addView(add, new LayoutParams(dp(48), dp(48)));
        addParams.height = LayoutParams.WRAP_CONTENT;
        addParams.setMarginStart(dp(6)); addParams.bottomMargin = 0;
        quickRow.addView(addBlock, addParams);
        addView(quickRow, new LayoutParams(-1, -2));
        LinearLayout contentRow = row();
        LinearLayout contentBlock = new LinearLayout(context);
        contentBlock.setOrientation(VERTICAL);
        contentBlock.addView(label("Content / items", 10));
        TextInputLayout contentLayout = new TextInputLayout(context);
        contentLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        quickContent = new TextInputEditText(context);
        quickContent.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        quickContent.setMinLines(2); quickContent.setMaxLines(4);
        quickContent.setHint(R.string.notes_quick_content_hint);
        contentLayout.addView(quickContent, new LayoutParams(-1, -2));
        contentLayout.setHintEnabled(false);
        contentBlock.addView(contentLayout, new LayoutParams(-1, -2));
        contentRow.addView(contentBlock, new LayoutParams(0, -2, 1.7f));
        LinearLayout choicesRow = row();
        dropdown(choicesRow, "Note category", categories, position -> quickCategory = position);
        dropdown(choicesRow, "Sharing", new String[]{context.getString(R.string.notes_private_status),
                context.getString(R.string.notes_shared_status)}, position -> quickSharing = position);
        contentRow.addView(choicesRow, new LayoutParams(0, -2, 1.8f));
        addView(contentRow, new LayoutParams(-1, -2));
        add.setOnClickListener(v -> {
            if (quickSaving) return;
            String title = value(quick);
            if (title.isEmpty()) {
                quick.setError(getResources().getString(R.string.notes_title_required));
                quick.requestFocus();
                return;
            }
            quick.setError(null);
            String content = value(quickContent);
            if (quickType == 1 && NotesChecklist.parse(content).isEmpty()) {
                contentLayout.setError(getResources().getString(R.string.notes_quick_items_required));
                quickContent.requestFocus();
                return;
            }
            contentLayout.setError(null);
            NoteEntry note = new NoteEntry(); note.title = title;
            note.noteType = quickType == 1 ? NoteEntry.TYPE_CHECKLIST : NoteEntry.TYPE_TEXT;
            note.content = content;
            note.category = categories[quickCategory]; note.collaborationStatus = "PENDING";
            note.isShared = quickSharing == 1;
            quickSaving = true; add.setEnabled(false);
            quick.setEnabled(false); quickContent.setEnabled(false);
            repository.save(note, () -> {
                quickSaving = false; add.setEnabled(true);
                quick.setEnabled(true); quickContent.setEnabled(true);
                if (title.equals(value(quick)) && content.equals(value(quickContent))) clearQuickAdd();
                if (isAttachedToWindow()) {
                    reload();
                    android.widget.Toast.makeText(getContext(), R.string.notes_quick_saved,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
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
        add.setCornerRadius(dp(24));
    }
    private interface Selection { void selected(int position); }
    private LinearLayout dropdown(LinearLayout parent, String name, String[] labels, Selection callback) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(VERTICAL);
        block.addView(label(name, 10));
        android.widget.Spinner input = new android.widget.Spinner(getContext(), android.widget.Spinner.MODE_DROPDOWN);
        input.setContentDescription(name);
        input.setBackground(fieldBackground());
        input.setPopupBackgroundDrawable(fieldBackground());
        input.setDropDownWidth(dropdownWidth(getContext(), labels));
        input.setDropDownVerticalOffset(dp(4));
        input.setAdapter(new android.widget.ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup owner) {
                TextView selected = choice(compactFilterLabel(name, labels[position]) + "  ▾");
                selected.setMaxLines(2);
                selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return selected;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup owner) {
                return choice(labels[position]);
            }
        });
        input.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> owner, View view, int position, long id) {
                callback.selected(position);
            }
            public void onNothingSelected(android.widget.AdapterView<?> owner) { }
        });
        block.addView(input, new LayoutParams(-1, dp("Note type".equals(name) ? 48 : 44)));
        LayoutParams params = new LayoutParams(0, -2, 1f);
        params.setMarginStart(parent.getChildCount() == 0 ? 0 : dp(6));
        params.bottomMargin = "Note type".equals(name) ? 0 : dp(4);
        parent.addView(block, params);
        return block;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        if (available != filterLayoutWidth) {
            filterLayoutWidth = available;
            arrangeFilters(available);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    private void arrangeFilters(int available) {
        int required = dp(18);
        for (int width : filterWidths) required += width;
        boolean allFit = required <= available;
        LinearLayout target = allFit ? filterRow : searchRow;
        if (categoryFilter.getParent() != target) {
            ((LinearLayout) categoryFilter.getParent()).removeView(categoryFilter);
            target.addView(categoryFilter);
        }
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View child = filterRow.getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            params.width = 0;
            params.weight = allFit ? filterWidths[i] : 1f;
            params.setMarginStart(i == 0 ? 0 : dp(6));
            child.setLayoutParams(params);
        }
        if (!allFit) {
            LayoutParams params = new LayoutParams(Math.min(filterWidths[3], available / 2), -2);
            params.setMarginStart(dp(6));
            params.bottomMargin = dp(4);
            categoryFilter.setLayoutParams(params);
        }
    }
    private static String compactFilterLabel(String name, String value) {
        // Keep full descriptions in the popup; shorten only the closed filter.
        if ("Status".equals(name)) {
            if ("All active".equals(value)) return "Active";
            if ("Completed".equals(value)) return "Done";
            if ("Archived".equals(value)) return "Archive";
        } else if ("Sort".equals(name)) {
            if ("Pinned / Latest".equals(value)) return "Latest";
            if ("Title A–Z".equals(value)) return "A–Z";
            if ("Reminder first".equals(value)) return "Due";
        } else if ("Type".equals(name)) {
            if ("All types".equals(value)) return "All";
            if ("Text note".equals(value)) return "Text";
        } else if ("Category".equals(name) && "All categories".equals(value)) return "All";
        return value;
    }
    static int dropdownWidth(Context context, String[] labels) {
        android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float density = context.getResources().getDisplayMetrics().density;
        paint.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
        float longest = 0;
        for (String label : labels) if (label != null) longest = Math.max(longest, paint.measureText(label));
        int desired = (int) Math.ceil(longest) + Math.round(34 * density);
        int available = context.getResources().getDisplayMetrics().widthPixels - Math.round(24 * density);
        return Math.max(1, Math.min(desired, available));
    }
    private TextView choice(String value) {
        TextView text = new TextView(getContext());
        text.setText(value); text.setTextSize(12); text.setTextColor(Color.rgb(31, 42, 49));
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setMinHeight(dp(44)); text.setPadding(dp(10), dp(4), dp(8), dp(4));
        text.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
        return text;
    }
    private GradientDrawable fieldBackground() {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.WHITE, Color.rgb(244, 249, 252)});
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(204, 214, 222));
        return background;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView label(String text, int size) { TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private static String value(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public void clearQuickAdd() { quick.setText(""); if (quickContent != null) quickContent.setText(""); }
    public void activate() { if (active) return; active = true; repository.startRealtimeSync(this::reload, (live, connecting) -> {
        if (!active) return;
        liveSync = live; connectingSync = connecting;
        if (syncStatusListener != null) syncStatusListener.onStateChanged(live, connecting);
    }); reload(); }
    public void setSyncStatusListener(NotesRepository.SyncStatusCallback listener) {
        syncStatusListener = listener;
        listener.onStateChanged(liveSync, connectingSync);
    }
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
