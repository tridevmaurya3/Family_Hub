package com.tridev.familyhub.feature.notes;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.databinding.ItemNoteBinding;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Fluent note cards with pin, archive, restore, edit, and delete actions. */
public class NotesAdapter
        extends RecyclerView.Adapter<NotesAdapter.NoteViewHolder> {

    public interface NoteActionListener {
        void onEdit(@NonNull NoteEntry note);

        void onPinnedChanged(@NonNull NoteEntry note, boolean pinned);

        void onArchivedChanged(@NonNull NoteEntry note, boolean archived);

        void onDelete(@NonNull NoteEntry note);
        default void onStatusChanged(@NonNull NoteEntry note, boolean completed) { }
        default void onChecklistChanged(@NonNull NoteEntry note, int index, boolean checked) { }
    }

    private final List<NoteEntry> notes = new ArrayList<>();
    private final NoteActionListener listener;

    public NotesAdapter(@NonNull NoteActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<NoteEntry> updated) {
        notes.clear();
        notes.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public NoteViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new NoteViewHolder(ItemNoteBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull NoteViewHolder holder,
            int position
    ) {
        holder.bind(notes.get(position));
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    class NoteViewHolder extends RecyclerView.ViewHolder {

        private final ItemNoteBinding binding;

        NoteViewHolder(@NonNull ItemNoteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull NoteEntry note) {
            binding.noteTitle.setText(note.title);
            binding.noteCompleted.setOnCheckedChangeListener(null);
            binding.noteCompleted.setChecked(NotesSmartFilter.completed(note));
            binding.noteCompleted.setEnabled(!note.isArchived);
            binding.noteCompleted.setOnCheckedChangeListener((button, checked) ->
                    listener.onStatusChanged(note, checked));
            binding.noteChecklist.removeAllViews();
            boolean checklist = NoteEntry.TYPE_CHECKLIST.equals(note.noteType);
            binding.noteChecklist.setVisibility(checklist ? View.VISIBLE : View.GONE);
            binding.noteContent.setVisibility(checklist ? View.GONE : View.VISIBLE);
            if (checklist) {
                java.util.List<NotesChecklist.Item> items = NotesChecklist.parse(note.content);
                for (int i = 0; i < items.size(); i++) {
                    final int index = i;
                    NotesChecklist.Item item = items.get(i);
                    android.widget.CheckBox check = new android.widget.CheckBox(binding.getRoot().getContext());
                    check.setText(item.text);
                    check.setTextSize(12);
                    check.setChecked(item.checked);
                    check.setEnabled(!note.isArchived);
                    if (item.checked) check.setPaintFlags(check.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    check.setOnCheckedChangeListener((button, checked) ->
                            listener.onChecklistChanged(note, index, checked));
                    binding.noteChecklist.addView(check);
                }
                binding.noteProgress.setText(NotesChecklist.completed(note.content) + "/" + items.size() + " completed");
            }
            binding.noteProgress.setVisibility(checklist ? View.VISIBLE : View.GONE);
            binding.noteContent.setText(
                    note.content == null || note.content.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.notes_no_content
                            )
                            : note.content
            );
            binding.noteCategory.setText(
                    note.category == null || note.category.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.notes_uncategorized
                            )
                            : note.category
            );
            binding.noteType.setText(
                    NoteEntry.TYPE_CHECKLIST.equals(note.noteType)
                            ? R.string.notes_type_checklist
                            : R.string.notes_type_text
            );
            binding.noteUpdated.setText(DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT
            ).format(new Date(note.updatedAt)));
            binding.notePinned.setVisibility(
                    note.isPinned ? View.VISIBLE : View.GONE
            );
            boolean assigned = note.assignedMemberName != null
                    && !note.assignedMemberName.isEmpty();
            binding.noteAssignment.setVisibility(assigned ? View.VISIBLE : View.GONE);
            if (assigned) binding.noteAssignment.setText(
                    binding.getRoot().getContext().getString(
                            R.string.notes_assigned_to, note.assignedMemberName));
            binding.noteSharing.setText(note.isShared
                    ? R.string.notes_shared_status : R.string.notes_private_status);
            binding.pinNoteButton.setText(
                    note.isPinned ? R.string.notes_unpin : R.string.notes_pin
            );
            binding.archiveNoteButton.setText(
                    note.isArchived
                            ? R.string.notes_restore
                            : R.string.notes_archive
            );
            binding.pinNoteButton.setVisibility(
                    note.isArchived ? View.GONE : View.VISIBLE
            );

            int accent = accentColor(note.colorKey);
            int container = containerColor(note.colorKey);
            binding.getRoot().setStrokeColor(accent);
            binding.getRoot().setCardBackgroundColor(container);
            binding.noteIcon.setImageTintList(
                    ColorStateList.valueOf(accent)
            );
            binding.noteType.setTextColor(accent);

            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(note)
            );
            binding.editNoteButton.setOnClickListener(
                    view -> listener.onEdit(note)
            );
            binding.pinNoteButton.setOnClickListener(
                    view -> listener.onPinnedChanged(note, !note.isPinned)
            );
            binding.archiveNoteButton.setOnClickListener(
                    view -> listener.onArchivedChanged(note, !note.isArchived)
            );
            binding.deleteNoteButton.setOnClickListener(
                    view -> listener.onDelete(note)
            );
        }

        private int accentColor(@NonNull String key) {
            int color;
            switch (key == null ? "" : key) {
                case "GREEN":
                    color = R.color.fh_success;
                    break;
                case "AMBER":
                    color = R.color.fh_warning;
                    break;
                case "PINK":
                    color = R.color.fh_module_health;
                    break;
                case "NEUTRAL":
                    color = R.color.fh_text_secondary;
                    break;
                default:
                    color = R.color.fh_primary;
                    break;
            }
            return ContextCompat.getColor(binding.getRoot().getContext(), color);
        }

        private int containerColor(@NonNull String key) {
            int color;
            switch (key == null ? "" : key) {
                case "GREEN":
                    color = R.color.fh_success_container;
                    break;
                case "AMBER":
                    color = R.color.fh_warning_container;
                    break;
                case "PINK":
                    color = R.color.fh_module_health_container;
                    break;
                case "NEUTRAL":
                    color = R.color.fh_surface_variant;
                    break;
                default:
                    color = R.color.fh_primary_container;
                    break;
            }
            return ContextCompat.getColor(binding.getRoot().getContext(), color);
        }
    }
}

