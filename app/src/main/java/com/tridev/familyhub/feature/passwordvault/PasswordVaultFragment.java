package com.tridev.familyhub.feature.passwordvault;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

/** Private AES-GCM encrypted credential vault backed by Android Keystore. */
public class PasswordVaultFragment extends Fragment implements AddActionHost {

    private FragmentPasswordVaultBinding binding;
    private PasswordVaultRepository repository;
    private PasswordVaultAdapter adapter;

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
}
