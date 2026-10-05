package com.tridev.familyhub.feature.notes;

import android.content.Context;
import android.app.Dialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.data.repository.NotesRepository;
import com.tridev.familyhub.databinding.DialogNoteBinding;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

/** Shared regular/floating editor, preserving NotesRepository save and reminder scheduling. */
public final class NotesEditor {
    private static final String[] NOTE_TYPES = {NoteEntry.TYPE_TEXT, NoteEntry.TYPE_CHECKLIST};
    private static final String[] COLOR_KEYS = {"BLUE", "GREEN", "AMBER", "PINK", "NEUTRAL"};
    private NotesEditor() { }
    public static View create(Context context, LayoutInflater inflater, NotesRepository repository,
                              @Nullable NoteEntry existing, boolean overlay,
                              Runnable onCancel, Runnable onSaved) {
        DialogNoteBinding form = DialogNoteBinding.inflate(inflater);
        com.tridev.familyhub.core.ui.CompactFormStyle.apply(form.getRoot());
        NoteEntry note = existing == null ? new NoteEntry() : existing;
        final java.util.List<Dialog> pickers = new java.util.ArrayList<>();
        form.getRoot().setTag(pickers);
        final long[] reminderAt = {note.reminderAt};
        String[] typeLabels =
                context.getResources().getStringArray(R.array.notes_type_labels);
        String[] colorLabels =
                context.getResources().getStringArray(R.array.notes_color_labels);
        java.util.List<String> catalogue = NotesCategories.labels(context);
        if (existing != null && !note.category.isEmpty() && !catalogue.contains(note.category)) catalogue.add(note.category);
        catalogue.add(NotesCategories.ADD);
        String[] categoryLabels = catalogue.toArray(new String[0]);
        installChoices(form.noteCategoryInput, categoryLabels);
        final String[] categorySelection = {existing == null ? categoryLabels[0] : note.category};
        form.noteCategoryInput.setOnItemClickListener((parent, view, position, id) -> {
            String selected = textOf(form.noteCategoryInput);
            if (!NotesCategories.ADD.equals(selected)) { categorySelection[0] = selected; return; }
            form.noteCategoryInput.setText(categorySelection[0], false);
            androidx.appcompat.app.AlertDialog picker = NotesCategories.prompt(context, overlay, added -> {
                java.util.List<String> values = NotesCategories.labels(context); values.add(NotesCategories.ADD);
                installChoices(form.noteCategoryInput, values.toArray(new String[0]));
                categorySelection[0] = added; form.noteCategoryInput.setText(added, false);
            });
            pickers.add(picker); picker.setOnDismissListener(dialog -> pickers.remove(picker));
        });
        installChoices(form.noteTypeInput, typeLabels);
        installChoices(form.noteColorInput, colorLabels);
        String[] collaborationLabels =
                context.getResources().getStringArray(R.array.collaboration_status_labels);
        installChoices(form.noteCollaborationStatusInput, collaborationLabels);

        if (existing == null) {
            form.noteSharedSwitch.setChecked(true);
            form.noteCategoryInput.setText(categoryLabels[0], false);
            form.noteTypeInput.setText(typeLabels[0], false);
            form.noteColorInput.setText(colorLabels[0], false);
            form.noteCollaborationStatusInput.setText(collaborationLabels[0], false);
        } else {
            form.noteDialogTitle.setText(R.string.notes_edit);
            form.noteTitleInput.setText(note.title);
            form.noteContentInput.setText(note.content);
            form.noteCategoryInput.setText(note.category, false);
            form.noteTypeInput.setText(
                    typeLabels[indexOf(NOTE_TYPES, note.noteType)],
                    false
            );
            form.noteColorInput.setText(
                    colorLabels[indexOf(COLOR_KEYS, note.colorKey)],
                    false
            );
            form.noteSharedSwitch.setChecked(note.isShared);
            form.noteCollaborationStatusInput.setText(note.collaborationStatus, false);
        }
        if (reminderAt[0] > 0L) form.noteReminderInput.setText(
                DateFormat.getDateTimeInstance().format(new Date(reminderAt[0])));
        form.noteReminderInput.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            if (reminderAt[0] > 0L) c.setTimeInMillis(reminderAt[0]);
            DatePickerDialog date = new DatePickerDialog(context, (d, y, m, day) -> {
                c.set(y, m, day);
                TimePickerDialog time = new TimePickerDialog(context, (t, h, min) -> {
                    c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min);
                    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                    reminderAt[0] = c.getTimeInMillis();
                    form.noteReminderInput.setText(DateFormat.getDateTimeInstance().format(c.getTime()));
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false);
                pickers.add(time);
                present(time, overlay);
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            pickers.add(date);
            present(date, overlay);
        });
        form.noteReminderInput.setOnLongClickListener(v -> {
            reminderAt[0] = 0L;
            form.noteReminderInput.setText("");
            return true;
        });
        if (note.id == 0L) form.noteDialogTitle.setText(R.string.notes_add);
        form.noteTypeInput.setOnItemClickListener((parent, view, position, id) ->
                form.noteContentInput.setHint(position == 1
                        ? R.string.notes_checklist_editor_hint : R.string.notes_text_editor_hint));

        NotesChecklistComposer checklistEditor = attachChecklistPreview(form, typeLabels);
        form.cancelNoteButton.setOnClickListener(v -> onCancel.run());
        form.saveNoteButton.setOnClickListener(clickedView -> {
            String title = textOf(form.noteTitleInput);
            int typeIndex = indexOf(typeLabels, textOf(form.noteTypeInput));
            int colorIndex = indexOf(
                    colorLabels,
                    textOf(form.noteColorInput)
            );
            if (title.isEmpty()) {
                form.noteTitleLayout.setError(
                        context.getString(R.string.notes_title_required)
                );
                return;
            }
            form.noteTitleLayout.setError(null);
            if (typeIndex == 1 && checklistEditor != null) {
                checklistEditor.commitPending();
                if (NotesChecklist.parse(textOf(form.noteContentInput)).isEmpty()) {
                    checklistEditor.showEmptyError(); return;
                }
            }
            note.title = title;
            note.content = textOf(form.noteContentInput);
            note.category = textOf(form.noteCategoryInput);
            note.noteType = NOTE_TYPES[typeIndex];
            note.colorKey = COLOR_KEYS[colorIndex];
            note.isShared = form.noteSharedSwitch.isChecked();
            note.collaborationStatus = textOf(form.noteCollaborationStatusInput);
            note.reminderAt = reminderAt[0];
            if (NoteEntry.TYPE_CHECKLIST.equals(note.noteType) && "COMPLETED".equalsIgnoreCase(note.collaborationStatus))
                note.content = NotesChecklist.setAll(note.content, true);
            form.saveNoteButton.setEnabled(false);
            repository.save(note, () -> {
                if (form.getRoot().isAttachedToWindow()) { form.saveNoteButton.setEnabled(true); onSaved.run(); }
            });
        });
        return form.getRoot();
    }

    private static NotesChecklistComposer attachChecklistPreview(DialogNoteBinding form, String[] typeLabels) {
        android.view.ViewParent owner = form.noteContentInput.getParent();
        while (owner != null && !(owner instanceof com.google.android.material.textfield.TextInputLayout)) owner = owner.getParent();
        if (owner == null || !(owner.getParent() instanceof android.widget.LinearLayout)) return null;
        android.widget.LinearLayout parent = (android.widget.LinearLayout) owner.getParent();
        NotesChecklistComposer composer = new NotesChecklistComposer(form.getRoot().getContext(),
                form.noteContentInput, (View) owner);
        parent.addView(composer, parent.indexOfChild((View) owner) + 1);
        Runnable update = () -> {
            boolean checklist = indexOf(typeLabels, textOf(form.noteTypeInput)) == 1;
            composer.setChecklist(checklist);
        };
        form.noteTypeInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(android.text.Editable text) { }
        });
        form.noteContentInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (indexOf(typeLabels, textOf(form.noteTypeInput)) != 1) return;
                String content = textOf(form.noteContentInput);
                int countItems = NotesChecklist.parse(content).size();
                if (countItems > 0 && NotesChecklist.completed(content) == countItems)
                    form.noteCollaborationStatusInput.setText("COMPLETED", false);
                else if ("COMPLETED".equals(textOf(form.noteCollaborationStatusInput)))
                    form.noteCollaborationStatusInput.setText("PENDING", false);
            }
            public void afterTextChanged(android.text.Editable text) { }
        });
        update.run();
        return composer;
    }

    /** Show the full catalogue, independent of the currently selected text. */
    private static void installChoices(com.google.android.material.textfield.MaterialAutoCompleteTextView input,
                                       String[] labels) {
        ArrayAdapter<String> choices = new ArrayAdapter<>(input.getContext(), R.layout.item_form_dropdown, labels);
        input.setAdapter(choices);
        input.setDropDownWidth(NotesWorkspaceView.dropdownWidth(input.getContext(), labels));
        input.setThreshold(0);
        input.setKeyListener(null);
        Runnable show = () -> choices.getFilter().filter(null, count -> {
            if (input.isAttachedToWindow()) input.showDropDown();
        });
        input.setOnClickListener(view -> show.run());
        android.view.ViewParent parent = input.getParent();
        while (parent != null && !(parent instanceof com.google.android.material.textfield.TextInputLayout))
            parent = parent.getParent();
        if (parent instanceof com.google.android.material.textfield.TextInputLayout)
            ((com.google.android.material.textfield.TextInputLayout) parent)
                    .setEndIconOnClickListener(view -> show.run());
    }

    public static void dispose(View root) {
        Object tag = root.getTag();
        if (tag instanceof java.util.List<?>) {
            for (Object value : new java.util.ArrayList<>((java.util.List<?>) tag)) if (value instanceof Dialog) ((Dialog) value).dismiss();
            root.setTag(null);
        }
    }
    public static void present(Dialog dialog, boolean overlay) {
        if (overlay && dialog.getWindow() != null)
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }
    private static String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
    private static int indexOf(String[] values, String selected) {
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(selected)) return i;
        return 0;
    }
}
