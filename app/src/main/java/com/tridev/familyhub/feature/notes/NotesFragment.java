package com.tridev.familyhub.feature.notes;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.data.repository.NotesRepository;
import com.tridev.familyhub.databinding.DialogNoteBinding;
import com.tridev.familyhub.databinding.FragmentNotesBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

/** Offline-first Fluent notes and checklists screen. */
public class NotesFragment extends Fragment implements AddActionHost {

    private static final String[] NOTE_TYPES = {
            NoteEntry.TYPE_TEXT,
            NoteEntry.TYPE_CHECKLIST
    };
    private static final String[] COLOR_KEYS = {
            "BLUE", "GREEN", "AMBER", "PINK", "NEUTRAL"
    };

    private FragmentNotesBinding binding;
    private NotesRepository repository;
    private NotesAdapter adapter;
    private boolean showingArchived;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentNotesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        repository = new NotesRepository(requireContext());
        adapter = new NotesAdapter(new NotesAdapter.NoteActionListener() {
            @Override
            public void onEdit(@NonNull NoteEntry note) {
                showEditor(note);
            }

            @Override
            public void onPinnedChanged(
                    @NonNull NoteEntry note,
                    boolean pinned
            ) {
                repository.setPinned(note, pinned, NotesFragment.this::reload);
            }

            @Override
            public void onArchivedChanged(
                    @NonNull NoteEntry note,
                    boolean archived
            ) {
                repository.setArchived(
                        note,
                        archived,
                        NotesFragment.this::reload
                );
            }

            @Override
            public void onDelete(@NonNull NoteEntry note) {
                confirmDelete(note);
            }
        });
        binding.notesRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.notesRecyclerView.setAdapter(adapter);
        binding.emptyAddNoteButton.setOnClickListener(
                clickedView -> showEditor(null)
        );
        binding.notesArchiveToggle.setOnClickListener(clickedView -> {
            showingArchived = !showingArchived;
            binding.notesArchiveToggle.setText(
                    showingArchived
                            ? R.string.notes_show_active
                            : R.string.notes_show_archived
            );
            binding.notesSearchLayout.setVisibility(
                    showingArchived ? View.GONE : View.VISIBLE
            );
            reload();
        });
        binding.notesSearchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after
                    ) {
                        // No action required.
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count
                    ) {
                        if (!showingArchived) {
                            reload();
                        }
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
        reload();
    }

    @Override
    public void onAddRequested() {
        showEditor(null);
    }

    private void showEditor(@Nullable NoteEntry existing) {
        DialogNoteBinding form = DialogNoteBinding.inflate(getLayoutInflater());
        NoteEntry note = existing == null ? new NoteEntry() : existing;
        String[] typeLabels =
                getResources().getStringArray(R.array.notes_type_labels);
        String[] colorLabels =
                getResources().getStringArray(R.array.notes_color_labels);
        String[] categoryLabels =
                getResources().getStringArray(R.array.notes_category_labels);
        form.noteCategoryInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                categoryLabels
        ));
        form.noteTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                typeLabels
        ));
        form.noteColorInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                colorLabels
        ));

        if (existing == null) {
            form.noteCategoryInput.setText(categoryLabels[0], false);
            form.noteTypeInput.setText(typeLabels[0], false);
            form.noteColorInput.setText(colorLabels[0], false);
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
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot())
                .create();
        form.cancelNoteButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        form.saveNoteButton.setOnClickListener(clickedView -> {
            String title = textOf(form.noteTitleInput);
            int typeIndex = indexOf(typeLabels, textOf(form.noteTypeInput));
            int colorIndex = indexOf(
                    colorLabels,
                    textOf(form.noteColorInput)
            );
            if (title.isEmpty()) {
                form.noteTitleLayout.setError(
                        getString(R.string.notes_title_required)
                );
                return;
            }
            form.noteTitleLayout.setError(null);
            note.title = title;
            note.content = textOf(form.noteContentInput);
            note.category = textOf(form.noteCategoryInput);
            note.noteType = NOTE_TYPES[typeIndex];
            note.colorKey = COLOR_KEYS[colorIndex];
            repository.save(note, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                reload();
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.notes_added
                                : R.string.notes_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    private void confirmDelete(@NonNull NoteEntry note) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.notes_delete_title)
                .setMessage(getString(
                        R.string.notes_delete_message,
                        note.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(note, this::reload)
                )
                .show();
    }

    private void reload() {
        if (binding == null) {
            return;
        }
        if (showingArchived) {
            repository.loadArchived(this::renderNotes);
        } else {
            repository.loadActive(
                    textOf(binding.notesSearchInput),
                    this::renderNotes
            );
        }
    }

    private void renderNotes(
            @NonNull java.util.List<NoteEntry> notes
    ) {
        if (binding == null) {
            return;
        }
        adapter.submitList(notes);
        binding.notesCountValue.setText(String.valueOf(notes.size()));
        binding.notesCountLabel.setText(
                showingArchived
                        ? R.string.notes_archived_count
                        : R.string.notes_active_count
        );
        boolean empty = notes.isEmpty();
        binding.notesRecyclerView.setVisibility(
                empty ? View.GONE : View.VISIBLE
        );
        binding.notesEmptyState.setVisibility(
                empty ? View.VISIBLE : View.GONE
        );
        binding.notesEmptyTitle.setText(
                showingArchived
                        ? R.string.notes_archived_empty_title
                        : R.string.notes_empty_title
        );
        binding.notesEmptyDetail.setText(
                showingArchived
                        ? R.string.notes_archived_empty_detail
                        : R.string.notes_empty_detail
        );
        binding.emptyAddNoteButton.setVisibility(
                showingArchived ? View.GONE : View.VISIBLE
        );
    }

    private int indexOf(
            @NonNull String[] values,
            @NonNull String selected
    ) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(selected)) {
                return index;
            }
        }
        return 0;
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        binding.notesRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
