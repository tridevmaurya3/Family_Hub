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
    private NotesChecklistComposer quickChecklist;
    private int quickCategory, quickSharing;
    private boolean quickPinned, restoringDraft;
    private long quickReminder;
    private android.content.SharedPreferences draftStore;
    private MaterialButton textTab, checklistTab;
    private TextView draftTag;
    private android.widget.Spinner categoryInput, sharingInput;
    private LinearLayout undoBar;
    private NoteEntry pendingDelete;
    private final java.util.Set<Long> deleting = new java.util.HashSet<>();
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable persistDraft = this::saveDraft;
    private final Runnable finishDelete = this::commitDelete;
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
        setBackgroundColor(Color.rgb(248, 247, 253));
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
        searchLayout.setStartIconDrawable(R.drawable.ic_search);
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
        LinearLayout composerCard = new LinearLayout(context);
        composerCard.setOrientation(VERTICAL); composerCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable composerBackground = new GradientDrawable();
        composerBackground.setColor(Color.rgb(250, 248, 255)); composerBackground.setCornerRadius(dp(18));
        composerBackground.setStroke(dp(1), Color.rgb(228, 220, 244)); composerCard.setBackground(composerBackground);
        LayoutParams composerParams = new LayoutParams(-1, -2); composerParams.topMargin = dp(8);
        addView(composerCard, composerParams);
        LinearLayout modeRow = row();
        TextView quickLabel = label("Quick add", 12); quickLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        modeRow.addView(quickLabel, new LayoutParams(0, -2, 1));
        textTab = chip("Text"); checklistTab = chip("Checklist");
        modeRow.addView(textTab, new LayoutParams(dp(64), dp(40)));
        modeRow.addView(checklistTab, new LayoutParams(dp(80), dp(40)));
        composerCard.addView(modeRow);
        textTab.setOnClickListener(v -> selectQuickType(0));
        checklistTab.setOnClickListener(v -> selectQuickType(1));
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

        quickBlock.addView(quickLayout, new LayoutParams(-1, dp(48)));
        quickRow.addView(quickBlock, new LayoutParams(0, -2, 1f));
        MaterialButton add = new MaterialButton(context);
        add.setText("+"); add.setContentDescription(getResources().getString(R.string.notes_add));
        add.setMinWidth(0); add.setMinimumWidth(0); add.setPadding(0, 0, 0, 0);
        add.setTextSize(24); add.setCornerRadius(dp(24));
        add.setInsetTop(0); add.setInsetBottom(0);
        LayoutParams addParams = new LayoutParams(dp(48), dp(48));
        LinearLayout addBlock = new LinearLayout(context);
        addBlock.setOrientation(VERTICAL);
        // Give the button the same label space as its neighbouring fields.

        addBlock.addView(add, new LayoutParams(dp(48), dp(48)));
        addParams.height = LayoutParams.WRAP_CONTENT;
        addParams.setMarginStart(dp(6)); addParams.bottomMargin = 0;
        quickRow.addView(addBlock, addParams);
        composerCard.addView(quickRow, new LayoutParams(-1, -2));
        LinearLayout contentRow = row();
        LinearLayout contentBlock = new LinearLayout(context);
        contentBlock.setOrientation(VERTICAL);

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
        contentRow.addView(contentBlock, new LayoutParams(-1, -2));
        LinearLayout choicesRow = row();
        LinearLayout categoryBlock = dropdown(choicesRow, "Note category", categories, position -> { quickCategory = position; scheduleDraft(); });
        categoryInput = (android.widget.Spinner) categoryBlock.getChildAt(0);
        LinearLayout sharingBlock = dropdown(choicesRow, "Sharing", new String[]{context.getString(R.string.notes_private_status),
                context.getString(R.string.notes_shared_status)}, position -> { quickSharing = position; scheduleDraft(); });
        sharingInput = (android.widget.Spinner) sharingBlock.getChildAt(0);
        composerCard.addView(contentRow, new LayoutParams(-1, -2));
        quickChecklist = new NotesChecklistComposer(context, quickContent, contentBlock);
        composerCard.addView(quickChecklist, new LayoutParams(-1, -2));
        composerCard.addView(choicesRow, new LayoutParams(-1, -2));
        LinearLayout shortcuts = row();
        MaterialButton tomorrow = chip("Tomorrow"); MaterialButton pin = chip("Pin");
        tomorrow.setIconResource(R.drawable.ic_bell); tomorrow.setIconSize(dp(14));
        pin.setIconResource(R.drawable.ic_notes_pin); pin.setIconSize(dp(14));
        shortcuts.addView(tomorrow, new LayoutParams(-2, dp(40)));
        shortcuts.addView(pin, new LayoutParams(-2, dp(40)));
        tomorrow.setOnClickListener(v -> {
            if (quickReminder > 0) quickReminder = 0;
            else {
                java.util.Calendar date = java.util.Calendar.getInstance(); date.add(java.util.Calendar.DAY_OF_YEAR, 1);
                date.set(java.util.Calendar.HOUR_OF_DAY, 9); date.set(java.util.Calendar.MINUTE, 0);
                date.set(java.util.Calendar.SECOND, 0); date.set(java.util.Calendar.MILLISECOND, 0);
                quickReminder = date.getTimeInMillis();
                if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    context.startActivity(new android.content.Intent(context,
                            com.tridev.familyhub.feature.notes.overlay.NotesNotificationPermissionActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            tomorrow.setText(quickReminder > 0 ? "✓ Tomorrow 9 AM" : "Tomorrow"); scheduleDraft();
        });
        pin.setOnClickListener(v -> { quickPinned = !quickPinned; pin.setText(quickPinned ? "✓ Pinned" : "Pin"); scheduleDraft(); });
        if (overlay) {
            MaterialButton more = chip("More options ▾"); composerCard.addView(more, new LayoutParams(-2, dp(36)));
            shortcuts.setVisibility(GONE);
            more.setOnClickListener(v -> { boolean expand = shortcuts.getVisibility() != VISIBLE;
                shortcuts.setVisibility(expand ? VISIBLE : GONE); more.setText(expand ? "Less options ▴" : "More options ▾"); });
        }
        composerCard.addView(shortcuts);
        draftTag = label("", 10); draftTag.setTextColor(Color.rgb(128, 89, 191));
        composerCard.addView(draftTag);
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        String owner = user == null ? "local" : user.getUid();
        draftStore = context.getSharedPreferences("notes_draft_" + owner + (overlay ? "_floating" : "_main"), Context.MODE_PRIVATE);
        restoringDraft = true;
        quick.setText(draftStore.getString("title", "")); quickContent.setText(draftStore.getString("content", ""));
        quickCategory = Math.min(categories.length - 1, Math.max(0, draftStore.getInt("category", 0)));
        quickSharing = draftStore.getBoolean("shared", false) ? 1 : 0;
        categoryInput.setSelection(quickCategory); sharingInput.setSelection(quickSharing);
        quickReminder = draftStore.getLong("reminder", 0);
        if (quickReminder <= System.currentTimeMillis()) quickReminder = 0; quickPinned = draftStore.getBoolean("pinned", false);
        tomorrow.setText(quickReminder > 0 ? "✓ Tomorrow 9 AM" : "Tomorrow"); pin.setText(quickPinned ? "✓ Pinned" : "Pin");
        quickChecklist.setPendingDraft(draftStore.getString("item", ""));
        selectQuickType(draftStore.getInt("type", 0)); restoringDraft = false;
        draftTag.setVisibility(GONE);
        if (!value(quick).isEmpty() || !value(quickContent).isEmpty() || !quickChecklist.pendingDraft().isEmpty()) { draftTag.setText("● Draft restored"); draftTag.setVisibility(VISIBLE); }
        TextWatcher draftWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleDraft(); }
            public void afterTextChanged(Editable text) { }
        };
        quick.addTextChangedListener(draftWatcher); quickContent.addTextChangedListener(draftWatcher);
        quickChecklist.setDraftChangedListener(this::scheduleDraft);
        add.setOnClickListener(v -> {
            if (quickSaving) return;
            String title = value(quick);
            if (title.isEmpty()) {
                quick.setError(getResources().getString(R.string.notes_title_required));
                quick.requestFocus();
                return;
            }
            quick.setError(null);
            if (quickType == 1) quickChecklist.commitPending();
            String content = value(quickContent);
            if (quickType == 1 && NotesChecklist.parse(content).isEmpty()) {
                quickChecklist.showEmptyError();
                return;
            }
            contentLayout.setError(null);
            NoteEntry note = new NoteEntry(); note.title = title;
            note.noteType = quickType == 1 ? NoteEntry.TYPE_CHECKLIST : NoteEntry.TYPE_TEXT;
            note.content = content;
            note.category = categories[quickCategory]; note.collaborationStatus = "PENDING";
            note.isShared = quickSharing == 1; note.isPinned = quickPinned; note.reminderAt = quickReminder;
            if (quickType == 1 && NotesChecklist.completed(content) == NotesChecklist.parse(content).size())
                note.collaborationStatus = "COMPLETED";
            quickSaving = true; add.setEnabled(false);
            quick.setEnabled(false); quickContent.setEnabled(false);
            repository.save(note, () -> {
                quickSaving = false; add.setEnabled(true);
                quick.setEnabled(true); quickContent.setEnabled(true);
                if (title.equals(value(quick)) && content.equals(value(quickContent))) {
                    clearQuickAdd(); quickReminder = 0; quickPinned = false;
                    tomorrow.setText("Tomorrow"); pin.setText("Pin");
                    uiHandler.removeCallbacks(persistDraft); draftStore.edit().clear().apply(); draftTag.setText(""); draftTag.setVisibility(GONE);
                }
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
                commitDelete(); pendingDelete = note; undoBar.setVisibility(VISIBLE); render();
                uiHandler.postDelayed(finishDelete, 5000);
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
        undoBar = row(); undoBar.setPadding(dp(8), 0, dp(8), 0); undoBar.setBackgroundColor(Color.rgb(235, 230, 245));
        undoBar.addView(label("Note removed", 11), new LayoutParams(0, -2, 1));
        MaterialButton undo = chip("Undo"); undoBar.addView(undo, new LayoutParams(-2, dp(44)));
        undo.setOnClickListener(v -> { uiHandler.removeCallbacks(finishDelete); pendingDelete = null;
            undoBar.setVisibility(GONE); render(); });
        undoBar.setVisibility(GONE); addView(undoBar, new LayoutParams(-1, -2));
        if (overlay) { TextView footer = label("Quick save without opening a form", 10); footer.setPadding(dp(6), dp(4), dp(6), 0); addView(footer); }
        makeControlsScrollable(0, indexOfChild(list));
        CompactFormStyle.applyInputs(this);
        add.setCornerRadius(dp(24));
    }
    private MaterialButton chip(String text) {
        MaterialButton button = new MaterialButton(getContext()); button.setText(text); button.setAllCaps(false);
        button.setTextSize(11); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0); button.setInsetTop(0); button.setInsetBottom(0);
        button.setCornerRadius(dp(18));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(244, 239, 253)));
        button.setTextColor(Color.rgb(123, 92, 176)); button.setStrokeWidth(dp(1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(226, 217, 241)));
        return button;
    }
    private void selectQuickType(int selected) {
        quickType = selected == 1 ? 1 : 0;
        if (quickChecklist != null) quickChecklist.setChecklist(quickType == 1);
        if (textTab != null) {
            int purple = Color.rgb(131, 104, 205), pale = Color.rgb(240, 235, 252);
            textTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(quickType == 0 ? purple : pale));
            checklistTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(quickType == 1 ? purple : pale));
            textTab.setTextColor(quickType == 0 ? Color.WHITE : purple); checklistTab.setTextColor(quickType == 1 ? Color.WHITE : purple);
            textTab.setSelected(quickType == 0); checklistTab.setSelected(quickType == 1);
        }
        scheduleDraft();
    }
    private void scheduleDraft() {
        if (draftStore == null || restoringDraft) return;
        uiHandler.removeCallbacks(persistDraft); uiHandler.postDelayed(persistDraft, 350);
    }
    private void saveDraft() {
        if (draftStore == null || quickSaving) return;
        draftStore.edit().putString("title", value(quick)).putString("content", value(quickContent))
                .putString("item", quickChecklist.pendingDraft()).putInt("type", quickType).putInt("category", quickCategory)
                .putBoolean("shared", quickSharing == 1).putBoolean("pinned", quickPinned).putLong("reminder", quickReminder).apply();
        draftTag.setText(value(quick).isEmpty() && value(quickContent).isEmpty() && quickChecklist.pendingDraft().isEmpty() ? "" : "● Draft saved");
        draftTag.setVisibility(draftTag.getText().length() == 0 ? GONE : VISIBLE);
    }
    private void commitDelete() {
        uiHandler.removeCallbacks(finishDelete);
        NoteEntry deleted = pendingDelete; pendingDelete = null;
        if (undoBar != null) undoBar.setVisibility(GONE);
        if (deleted != null) {
            deleting.add(deleted.id);
            repository.delete(deleted, () -> { deleting.remove(deleted.id); reload(); });
        }
    }
    private interface Selection { void selected(int position); }
    private LinearLayout dropdown(LinearLayout parent, String name, String[] labels, Selection callback) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(VERTICAL);

        android.widget.Spinner input = new android.widget.Spinner(getContext(), android.widget.Spinner.MODE_DROPDOWN);
        input.setContentDescription(name);
        input.setBackground(fieldBackground());
        input.setPopupBackgroundDrawable(fieldBackground());
        input.setDropDownWidth(dropdownWidth(getContext(), labels));
        input.setDropDownVerticalOffset(dp(4));
        input.setAdapter(new android.widget.ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup owner) {
                TextView selected = choice(compactFilterLabel(name, labels[position]) + "  ▾");
                if ("Note category".equals(name) || "Sharing".equals(name)) {
                    android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(getContext(),
                            "Sharing".equals(name) ? R.drawable.ic_lock : R.drawable.ic_family);
                    if (icon != null) { icon = icon.mutate(); icon.setTint(Color.rgb(117, 92, 156));
                        icon.setBounds(0, 0, dp(14), dp(14)); selected.setCompoundDrawablePadding(dp(5));
                        selected.setCompoundDrawablesRelative(icon, null, null, null); }
                }
                selected.setMaxLines(1);
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
        if ("Sharing".equals(name) && "Shared with family".equals(value)) return "Shared";
        if ("Status".equals(name)) {
            if ("All active".equals(value)) return "Active";
            if ("Completed".equals(value)) return "Done";
            if ("Archived".equals(value)) return "Archive";
        } else if ("Sort".equals(name)) {
            if ("Pinned / Latest".equals(value)) return "Latest";
            if ("Title A–Z".equals(value)) return "A–Z";
            if ("Reminder first".equals(value)) return "Due";
        } else if ("Type".equals(name)) {
            if ("All types".equals(value)) return "All types";
            if ("Text note".equals(value)) return "Text";
        } else if ("Category".equals(name) && "All categories".equals(value)) return "Category";
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
                new int[]{Color.rgb(254, 252, 255), Color.rgb(249, 247, 253)});
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(227, 219, 242));
        return background;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView label(String text, int size) { TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private static String value(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public void clearQuickAdd() { quick.setText(""); if (quickContent != null) quickContent.setText(""); if (quickChecklist != null) quickChecklist.clearDraft(); }
    public void activate() { if (active) return; active = true; repository.startRealtimeSync(this::reload, (live, connecting) -> {
        if (!active) return;
        liveSync = live; connectingSync = connecting;
        if (syncStatusListener != null) syncStatusListener.onStateChanged(live, connecting);
    }); reload(); }
    public void setSyncStatusListener(NotesRepository.SyncStatusCallback listener) {
        syncStatusListener = listener;
        listener.onStateChanged(liveSync, connectingSync);
    }
    public void deactivate() { saveDraft(); uiHandler.removeCallbacks(persistDraft); commitDelete(); active = false; generation++; repository.stopRealtimeSync(); }
    public void reload() {
        if (!active) return;
        final int request = ++generation;
        NotesRepository.NotesCallback callback = loaded -> { if (active && request == generation) { notes = loaded; render(); } };
        if (status == 4) repository.loadArchived(callback); else repository.loadActive("", callback);
    }
    private void render() {
        if (adapter == null) return;
        List<NoteEntry> visible = NotesSmartFilter.apply(notes, status, type, category, sort, value(search));
        visible.removeIf(n -> deleting.contains(n.id) || pendingDelete != null && n.id == pendingDelete.id);
        adapter.submitList(visible);
        int pending = 0, pinned = 0;
        for (NoteEntry note : notes) { if (!note.isArchived && !NotesSmartFilter.completed(note)) pending++; if (note.isPinned) pinned++; }
        summary.setText(visible.size() + " shown • " + pending + " pending • " + pinned + " pinned");
        empty.setVisibility(visible.isEmpty() ? VISIBLE : GONE);
    }
}
