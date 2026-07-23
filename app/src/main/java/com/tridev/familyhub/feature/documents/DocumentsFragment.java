package com.tridev.familyhub.feature.documents;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.repository.DocumentRepository;
import com.tridev.familyhub.databinding.DialogDocumentEditorBinding;
import com.tridev.familyhub.databinding.FragmentDocumentsBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

/** Offline document vault backed by persisted Android document permissions. */
public class DocumentsFragment extends Fragment implements AddActionHost {

    private FragmentDocumentsBinding binding;
    private DocumentRepository repository;
    private DocumentAdapter adapter;
    private String pendingTitle;
    private String pendingCategory;

    private final ActivityResultLauncher<String[]> documentPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::onDocumentPicked
            );

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentDocumentsBinding.inflate(
                inflater,
                container,
                false
        );
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        repository = new DocumentRepository(requireContext());
        adapter = new DocumentAdapter(new DocumentAdapter.DocumentActionListener() {
            @Override
            public void onOpen(@NonNull DocumentEntry document) {
                openDocument(document);
            }

            @Override
            public void onDelete(@NonNull DocumentEntry document) {
                confirmDelete(document);
            }
        });

        binding.documentRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.documentRecyclerView.setAdapter(adapter);
        binding.emptyAddDocumentButton.setOnClickListener(
                clickedView -> showDocumentEditor()
        );
        binding.documentSearchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text,
                            int start,
                            int count,
                            int after
                    ) {
                        // No action required.
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence text,
                            int start,
                            int before,
                            int count
                    ) {
                        loadDocuments(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );

        loadDocuments("");
    }

    @Override
    public void onAddRequested() {
        showDocumentEditor();
    }

    private void showDocumentEditor() {
        DialogDocumentEditorBinding dialogBinding =
                DialogDocumentEditorBinding.inflate(getLayoutInflater());

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(requireContext())
                        .setView(dialogBinding.getRoot())
                        .create();

        dialogBinding.cancelDocumentButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        dialogBinding.chooseDocumentButton.setOnClickListener(clickedView -> {
            String title = textOf(dialogBinding.documentTitleInput);
            String category = textOf(dialogBinding.documentCategoryInput);

            if (title.isEmpty()) {
                dialogBinding.documentTitleLayout.setError(
                        getString(R.string.document_title_required)
                );
                return;
            }

            dialogBinding.documentTitleLayout.setError(null);
            pendingTitle = title;
            pendingCategory = category.isEmpty()
                    ? getString(R.string.document_default_category)
                    : category;

            dialog.dismiss();
            documentPicker.launch(new String[]{
                    "application/pdf",
                    "image/*"
            });
        });

        dialog.show();
    }

    private void onDocumentPicked(@Nullable Uri uri) {
        if (uri == null || pendingTitle == null) {
            clearPendingDocument();
            return;
        }

        try {
            requireContext().getContentResolver()
                    .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
        } catch (SecurityException ignored) {
            // Some providers grant access without a persistable permission.
        }

        String resolvedMimeType =
                requireContext().getContentResolver().getType(uri);

        DocumentEntry document = new DocumentEntry();
        document.title = pendingTitle;
        document.category = pendingCategory;
        document.contentUri = uri.toString();
        document.mimeType = resolvedMimeType == null
                ? ""
                : resolvedMimeType;
        document.createdAt = System.currentTimeMillis();

        repository.save(document, documentId -> {
            if (binding == null) {
                return;
            }
            clearPendingDocument();
            loadDocuments(currentQuery());
            Snackbar.make(
                    binding.getRoot(),
                    R.string.document_added,
                    Snackbar.LENGTH_SHORT
            ).show();
        });
    }

    private void loadDocuments(@NonNull String query) {
        if (repository == null) {
            return;
        }

        repository.loadDocuments(query, documents -> {
            if (binding == null) {
                return;
            }

            adapter.submitList(documents);
            boolean isEmpty = documents.isEmpty();
            binding.documentRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.documentsEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    private void openDocument(@NonNull DocumentEntry document) {
        try {
            Uri uri = Uri.parse(document.contentUri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(
                    uri,
                    document.mimeType.isEmpty()
                            ? "*/*"
                            : document.mimeType
            );
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception exception) {
            if (binding != null) {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.document_unavailable,
                        Snackbar.LENGTH_LONG
                ).show();
            }
        }
    }

    private void confirmDelete(@NonNull DocumentEntry document) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.document_remove_title)
                .setMessage(R.string.document_remove_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(document, () -> {
                            if (binding == null) {
                                return;
                            }
                            loadDocuments(currentQuery());
                            Snackbar.make(
                                    binding.getRoot(),
                                    R.string.document_removed,
                                    Snackbar.LENGTH_SHORT
                            ).show();
                        })
                )
                .show();
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.documentSearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    private void clearPendingDocument() {
        pendingTitle = null;
        pendingCategory = null;
    }

    @Override
    public void onDestroyView() {
        binding.documentRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
