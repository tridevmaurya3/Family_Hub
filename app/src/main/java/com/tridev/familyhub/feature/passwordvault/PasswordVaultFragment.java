package com.tridev.familyhub.feature.passwordvault;

import android.os.Bundle;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.entity.PasswordEntry;
import com.tridev.familyhub.data.repository.PasswordVaultRepository;
import com.tridev.familyhub.databinding.DialogPasswordEditorBinding;
import com.tridev.familyhub.databinding.DialogPasswordViewBinding;
import com.tridev.familyhub.databinding.FragmentPasswordVaultBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private AES-GCM encrypted credential vault backed by Android Keystore. */
public class PasswordVaultFragment extends Fragment implements AddActionHost {

    private FragmentPasswordVaultBinding binding;
    private PasswordVaultRepository repository;
    private PasswordVaultAdapter adapter;
    private final ExecutorService transferExecutor =
            Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> importCsvLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::handleImportFile
            );
    private final ActivityResultLauncher<String> exportCsvLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("text/csv"),
                    this::handleExportFile
            );

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentPasswordVaultBinding.inflate(
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

        repository = new PasswordVaultRepository(requireContext());
        adapter = new PasswordVaultAdapter(
                new PasswordVaultAdapter.EntryActionListener() {
                    @Override
                    public void onOpen(@NonNull PasswordEntry entry) {
                        showDetails(entry);
                    }

                    @Override
                    public void onDelete(@NonNull PasswordEntry entry) {
                        confirmDelete(entry);
                    }
                }
        );

        binding.passwordVaultRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.passwordVaultRecyclerView.setAdapter(adapter);
        binding.emptyAddPasswordButton.setOnClickListener(
                clickedView -> showEditor(null)
        );
        binding.importPasswordsButton.setOnClickListener(
                clickedView -> importCsvLauncher.launch(new String[]{
                        "text/csv",
                        "text/comma-separated-values",
                        "text/plain",
                        "application/vnd.ms-excel"
                })
        );
        binding.exportPasswordsButton.setOnClickListener(
                clickedView -> confirmCsvExport()
        );
        binding.passwordVaultSearchInput.addTextChangedListener(
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
                        loadEntries(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );

        loadEntries("");
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
        );
    }

    @Override
    public void onPause() {
        requireActivity().getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
        );
        super.onPause();
    }

    @Override
    public void onAddRequested() {
        showEditor(null);
    }

    private void showEditor(@Nullable PasswordEntry existing) {
        DialogPasswordEditorBinding dialogBinding =
                DialogPasswordEditorBinding.inflate(getLayoutInflater());

        if (existing != null) {
            dialogBinding.passwordDialogTitle.setText(
                    R.string.vault_edit_credential
            );
            dialogBinding.vaultTitleInput.setText(existing.title);
            dialogBinding.vaultWebsiteInput.setText(existing.website);
            dialogBinding.vaultUsernameInput.setText(
                    VaultCipher.decrypt(existing.usernameEncrypted)
            );
            dialogBinding.vaultPasswordInput.setText(
                    VaultCipher.decrypt(existing.passwordEncrypted)
            );
            dialogBinding.vaultNotesInput.setText(
                    VaultCipher.decrypt(existing.notesEncrypted)
            );
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

        dialogBinding.cancelPasswordButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        dialogBinding.savePasswordButton.setOnClickListener(clickedView -> {
            String title = textOf(dialogBinding.vaultTitleInput);
            String password = textOf(dialogBinding.vaultPasswordInput);

            if (title.isEmpty()) {
                dialogBinding.vaultTitleLayout.setError(
                        getString(R.string.vault_title_required)
                );
                return;
            }
            dialogBinding.vaultTitleLayout.setError(null);

            if (password.isEmpty()) {
                dialogBinding.vaultPasswordLayout.setError(
                        getString(R.string.vault_password_required)
                );
                return;
            }
            dialogBinding.vaultPasswordLayout.setError(null);

            PasswordEntry entry = existing == null
                    ? new PasswordEntry()
                    : existing;
            entry.title = title;
            entry.website = textOf(dialogBinding.vaultWebsiteInput);
            entry.usernameEncrypted = VaultCipher.encrypt(
                    textOf(dialogBinding.vaultUsernameInput)
            );
            entry.passwordEncrypted = VaultCipher.encrypt(password);
            entry.notesEncrypted = VaultCipher.encrypt(
                    textOf(dialogBinding.vaultNotesInput)
            );

            repository.save(entry, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                loadEntries(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.vault_credential_added
                                : R.string.vault_credential_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });

        dialog.show();
    }

    private void showDetails(@NonNull PasswordEntry entry) {
        DialogPasswordViewBinding dialogBinding =
                DialogPasswordViewBinding.inflate(getLayoutInflater());
        dialogBinding.vaultViewTitle.setText(entry.title);
        dialogBinding.vaultViewWebsite.setText(
                entry.website.isEmpty()
                        ? getString(R.string.vault_no_website)
                        : entry.website
        );
        dialogBinding.vaultViewUsername.setText(
                fallbackSecret(VaultCipher.decrypt(entry.usernameEncrypted))
        );
        dialogBinding.vaultViewPassword.setText(
                fallbackSecret(VaultCipher.decrypt(entry.passwordEncrypted))
        );
        dialogBinding.vaultViewNotes.setText(
                fallbackSecret(VaultCipher.decrypt(entry.notesEncrypted))
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();
        dialogBinding.closePasswordViewButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        dialogBinding.editPasswordViewButton.setOnClickListener(clickedView -> {
            dialog.dismiss();
            showEditor(entry);
        });
        dialog.show();
    }

    @NonNull
    private String fallbackSecret(@NonNull String value) {
        return value.isEmpty()
                ? getString(R.string.vault_not_provided)
                : value;
    }

    private void confirmDelete(@NonNull PasswordEntry entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vault_delete_title)
                .setMessage(getString(
                        R.string.vault_delete_message,
                        entry.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(entry, () -> {
                            if (binding == null) {
                                return;
                            }
                            loadEntries(currentQuery());
                            Snackbar.make(
                                    binding.getRoot(),
                                    R.string.vault_credential_removed,
                                    Snackbar.LENGTH_SHORT
                            ).show();
                        })
                )
                .show();
    }

    private void loadEntries(@NonNull String query) {
        if (repository == null) {
            return;
        }

        repository.loadEntries(query, entries -> {
            if (binding == null) {
                return;
            }
            adapter.submitList(entries);
            boolean isEmpty = entries.isEmpty();
            binding.passwordVaultRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.passwordVaultEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    private void handleImportFile(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        transferExecutor.execute(() -> {
            try {
                List<PasswordCsvTransfer.PlainCredential> imported =
                        PasswordCsvTransfer.read(requireContext(), uri);
                requireActivity().runOnUiThread(
                        () -> previewImport(imported)
                );
            } catch (Exception error) {
                showTransferError(R.string.vault_import_error, error);
            }
        });
    }

    private void previewImport(
            @NonNull List<PasswordCsvTransfer.PlainCredential> imported
    ) {
        if (binding == null) {
            return;
        }
        if (imported.isEmpty()) {
            Snackbar.make(
                    binding.getRoot(),
                    R.string.vault_import_empty,
                    Snackbar.LENGTH_LONG
            ).show();
            return;
        }
        repository.loadEntries("", existing -> {
            if (binding == null) {
                return;
            }
            Set<String> knownKeys = new HashSet<>();
            for (PasswordEntry entry : existing) {
                knownKeys.add(PasswordCsvTransfer.duplicateKey(
                        entry.title,
                        entry.website,
                        VaultCipher.decrypt(entry.usernameEncrypted)
                ));
            }
            List<PasswordCsvTransfer.PlainCredential> newCredentials =
                    new ArrayList<>();
            int duplicateCount = 0;
            for (PasswordCsvTransfer.PlainCredential credential : imported) {
                String key = PasswordCsvTransfer.duplicateKey(
                        credential.title,
                        credential.website,
                        credential.username
                );
                if (knownKeys.add(key)) {
                    newCredentials.add(credential);
                } else {
                    duplicateCount++;
                }
            }
            showImportConfirmation(newCredentials, duplicateCount);
        });
    }

    private void showImportConfirmation(
            @NonNull List<PasswordCsvTransfer.PlainCredential> credentials,
            int duplicateCount
    ) {
        if (credentials.isEmpty()) {
            Snackbar.make(
                    binding.getRoot(),
                    getString(
                            R.string.vault_import_success,
                            0,
                            duplicateCount
                    ),
                    Snackbar.LENGTH_LONG
            ).show();
            return;
        }
        int finalDuplicateCount = duplicateCount;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vault_import_preview_title)
                .setMessage(getString(
                        R.string.vault_import_preview_message,
                        credentials.size(),
                        duplicateCount
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.vault_import_confirm,
                        (dialog, which) -> importCredentials(
                                credentials,
                                finalDuplicateCount
                        )
                )
                .show();
    }

    private void importCredentials(
            @NonNull List<PasswordCsvTransfer.PlainCredential> credentials,
            int duplicateCount
    ) {
        transferExecutor.execute(() -> {
            try {
                List<PasswordEntry> encrypted = new ArrayList<>();
                for (PasswordCsvTransfer.PlainCredential credential
                        : credentials) {
                    encrypted.add(PasswordCsvTransfer.encryptedEntry(
                            credential
                    ));
                }
                repository.importEntries(encrypted, () -> {
                    if (binding == null) {
                        return;
                    }
                    loadEntries(currentQuery());
                    Snackbar.make(
                            binding.getRoot(),
                            getString(
                                    R.string.vault_import_success,
                                    encrypted.size(),
                                    duplicateCount
                            ),
                            Snackbar.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                showTransferError(R.string.vault_import_error, error);
            }
        });
    }

    private void confirmCsvExport() {
        repository.loadEntries("", entries -> {
            if (binding == null) {
                return;
            }
            if (entries.isEmpty()) {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.vault_export_empty,
                        Snackbar.LENGTH_LONG
                ).show();
                return;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.vault_export_warning_title)
                    .setMessage(R.string.vault_export_warning_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                            R.string.vault_export_continue,
                            (dialog, which) -> authenticateForExport()
                    )
                    .show();
        });
    }

    private void authenticateForExport() {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        BiometricPrompt.PromptInfo promptInfo =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(
                                R.string.vault_export_warning_title
                        ))
                        .setSubtitle(getString(
                                R.string.vault_export_warning_message
                        ))
                        .setAllowedAuthenticators(authenticators)
                        .build();
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(requireContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result
                    ) {
                        String date = new SimpleDateFormat(
                                "yyyy-MM-dd",
                                Locale.ROOT
                        ).format(new Date());
                        exportCsvLauncher.launch(
                                "family-hub-passwords-" + date + ".csv"
                        );
                    }
                }
        );
        prompt.authenticate(promptInfo);
    }

    private void handleExportFile(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        repository.loadEntries("", entries -> transferExecutor.execute(() -> {
            try {
                PasswordCsvTransfer.write(requireContext(), uri, entries);
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            Snackbar.make(
                                    binding.getRoot(),
                                    R.string.vault_export_success,
                                    Snackbar.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            } catch (Exception error) {
                showTransferError(R.string.vault_export_error, error);
            }
        }));
    }

    private void showTransferError(int messageResource, @NonNull Exception error) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (binding == null) {
                return;
            }
            String reason = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            Snackbar.make(
                    binding.getRoot(),
                    getString(messageResource, reason),
                    Snackbar.LENGTH_LONG
            ).show();
        });
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.passwordVaultSearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        binding.passwordVaultRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        transferExecutor.shutdownNow();
        super.onDestroy();
    }
}
