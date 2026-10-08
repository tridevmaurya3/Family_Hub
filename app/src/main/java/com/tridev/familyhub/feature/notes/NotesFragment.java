package com.tridev.familyhub.feature.notes;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import com.tridev.familyhub.data.repository.NotesRepository;
import com.tridev.familyhub.databinding.FragmentNotesBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;

/** Smart Notes page; shared workspace/editor also power the floating Notes panel. */
public class NotesFragment extends Fragment implements AddActionHost {
    private FragmentNotesBinding binding;
    private NotesRepository repository;
    private NotesWorkspaceView workspace;
    @Nullable private AlertDialog editorDialog;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentNotesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        binding.notesOverview.setNavigationAction(R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title, v -> ((MainActivity) requireActivity()).showFeatureMenu());
        binding.notesSummary.setVisibility(View.GONE);
        binding.notesSearchLayout.setVisibility(View.GONE);
        binding.notesRecyclerView.setVisibility(View.GONE);
        binding.notesEmptyState.setVisibility(View.GONE);
        repository = new NotesRepository(requireContext());
        workspace = new NotesWorkspaceView(requireContext(), repository, false, this::showEditor);
        binding.notesWorkspaceContainer.addView(workspace, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.TextView syncTag = binding.notesOverview.findViewById(R.id.module_overview_detail);
        workspace.setSyncStatusListener((live, connecting) -> {
            syncTag.setText(live ? R.string.notes_main_live : connecting ? R.string.notes_sync_connecting : R.string.notes_sync_offline);
            syncTag.setTextColor(live ? android.graphics.Color.rgb(38, 139, 88) : android.graphics.Color.rgb(110, 98, 130));
        });
        binding.notesOverview.setSearchAction(v -> workspace.toggleSearch());
        workspace.activate();
    }
    @Override public void onAddRequested() { showEditor(null); }
    private void showEditor(@Nullable NoteEntry note) {
        if (binding == null) return;
        if (editorDialog != null) editorDialog.dismiss();
        final boolean isNew = note == null || note.id == 0;
        final boolean quickDraft = note != null && note.id == 0;
        View form = NotesEditor.create(requireContext(), getLayoutInflater(), repository, note, false,
                () -> { if (editorDialog != null) editorDialog.dismiss(); },
                () -> {
                    if (binding == null) return;
                    if (editorDialog != null) editorDialog.dismiss();
                    if (quickDraft) workspace.clearQuickAdd();
                    workspace.reload();
                    com.google.android.material.snackbar.Snackbar.make(binding.getRoot(),
                            isNew ? R.string.notes_added : R.string.notes_updated,
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5301);
                });
        editorDialog = new MaterialAlertDialogBuilder(requireContext()).setView(form).create();
        editorDialog.setOnDismissListener(dialog -> NotesEditor.dispose(form));
        editorDialog.show();
    }
    @Override public void onDestroyView() {
        if (editorDialog != null) editorDialog.dismiss();
        editorDialog = null;
        if (workspace != null) workspace.deactivate();
        workspace = null; repository = null; binding = null;
        super.onDestroyView();
    }
}
