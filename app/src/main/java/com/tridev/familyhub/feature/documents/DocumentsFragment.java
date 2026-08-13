package com.tridev.familyhub.feature.documents;

import android.Manifest;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.repository.DocumentRepository;
import com.tridev.familyhub.databinding.DialogDocumentEditorBinding;
import com.tridev.familyhub.databinding.FragmentDocumentsBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Private offline Documents Vault with device authentication, secure Android
 * document permissions, camera scans, expiry reminders, filters and encrypted
 * backup support.
 */
public class DocumentsFragment extends Fragment implements AddActionHost {

    private enum FilterMode {
        ALL,
        EXPIRING,
        EXPIRED,
        FAVORITES,
        EMERGENCY,
        TRASH
    }

    private FragmentDocumentsBinding binding;
    private DocumentRepository repository;
    private DocumentAdapter adapter;
    private DocumentVaultPreferences preferences;

    private final List<DocumentEntry> loadedDocuments = new ArrayList<>();
    private FilterMode filterMode = FilterMode.ALL;
    @NonNull
    private String selectedCategory = "";

    @Nullable
    private PendingDocument pendingDocument;
    @Nullable
    private DocumentCaptureStorage.CaptureTarget captureTarget;
    @Nullable
    private androidx.appcompat.app.AlertDialog editorDialog;
    @Nullable
    private DialogDocumentEditorBinding editorBinding;
    private long editorExpiryAt;
    private boolean updatingLockSwitch;
    @Nullable
    private Runnable authenticationSuccessAction;

    private final ActivityResultLauncher<String[]> documentPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::onDocumentPicked
            );

    private final ActivityResultLauncher<Uri> cameraCapture =
            registerForActivityResult(
                    new ActivityResultContracts.TakePicture(),
                    this::onCameraCaptured
            );

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (binding == null) {
                            return;
                        }
                        binding.switchDocumentExpiryAlerts.setChecked(granted);
                        preferences.setExpiryAlertsEnabled(granted);
                        DocumentExpiryScheduler.sync(requireContext());
                        if (granted) {
                            DocumentExpiryScheduler.runNow(requireContext());
                        }
                    }
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
        preferences = new DocumentVaultPreferences(requireContext());
        adapter = new DocumentAdapter(new DocumentAdapter.DocumentActionListener() {
            @Override
            public void onOpen(@NonNull DocumentEntry document) {
                runProtected(() -> openDocument(document));
            }

            @Override
            public void onShare(@NonNull DocumentEntry document) {
                runProtected(() -> shareDocument(document));
            }

            @Override
            public void onEdit(@NonNull DocumentEntry document) {
                runProtected(() -> showDocumentEditorInternal(document));
            }

            @Override
            public void onToggleFavorite(@NonNull DocumentEntry document) {
                preferences.toggleFavorite(document.id);
                applyFilters();
            }

            @Override
            public void onDelete(@NonNull DocumentEntry document) {
                runProtected(() -> confirmDelete(document));
            }

            @Override
            public void onRestore(@NonNull DocumentEntry document) {
                runProtected(() -> restoreDocument(document));
            }

            @Override
            public void onPermanentDelete(@NonNull DocumentEntry document) {
                runProtected(() -> confirmPermanentDelete(document));
            }

            @Override
            public void onHistory(@NonNull DocumentEntry document) {
                runProtected(() -> showVersionHistory(document));
            }

            @Override
            public boolean isFavorite(@NonNull DocumentEntry document) {
                return preferences.isFavorite(document.id);
            }
        });

        binding.documentRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.documentRecyclerView.setAdapter(adapter);

        configureCategoryFilter();
        configureReminderSettings();
        configureFilters();
        configureSearch();
        configureVaultLock();

        binding.emptyAddDocumentButton.setOnClickListener(
                clickedView -> onAddRequested()
        );
        binding.unlockDocumentsButton.setOnClickListener(
                clickedView -> authenticate(() -> {
                    preferences.markUnlocked();
                    renderLockState();
                    loadDocuments();
                })
        );
        binding.buttonDocumentsLockNow.setOnClickListener(clickedView -> {
            preferences.lockNow();
            renderLockState();
        });
        binding.buttonMissingDocumentsChecklist.setOnClickListener(
                clickedView -> runProtected(this::showMissingDocumentsChecklist));
        binding.buttonDocumentsPdf.setOnClickListener(v -> runProtected(() -> exportInventory(true)));
        binding.buttonDocumentsExcel.setOnClickListener(v -> runProtected(() -> exportInventory(false)));
        binding.buttonDocumentsReportShare.setOnClickListener(v -> runProtected(() -> exportInventory(true)));
        binding.buttonDocumentsIntegrity.setOnClickListener(
                v -> runProtected(this::showIntegrityCenter));

        renderLockState();
        if (preferences.isUnlocked()) {
            loadDocuments();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            renderLockState();
            if (preferences.isUnlocked()) {
                loadDocuments();
            }
        }
    }

    @Override
    public void onAddRequested() {
        runProtected(() -> showDocumentEditorInternal(null));
    }

    private void configureVaultLock() {
        updatingLockSwitch = true;
        binding.switchDocumentVaultLock.setChecked(
                preferences.lockEnabled()
        );
        updatingLockSwitch = false;

        binding.switchDocumentVaultLock.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (updatingLockSwitch) {
                        return;
                    }
                    if (enabled) {
                        authenticate(() -> {
                            preferences.setLockEnabled(true);
                            preferences.markUnlocked();
                            renderLockState();
                        });
                    } else if (preferences.lockEnabled()) {
                        runProtected(() -> {
                            preferences.setLockEnabled(false);
                            renderLockState();
                        });
                    }
                }
        );
    }

    private void renderLockState() {
        if (binding == null) {
            return;
        }
        boolean lockEnabled = preferences.lockEnabled();
        boolean unlocked = preferences.isUnlocked();
        boolean locked = lockEnabled && !unlocked;

        updatingLockSwitch = true;
        binding.switchDocumentVaultLock.setChecked(lockEnabled);
        updatingLockSwitch = false;
        binding.documentsLockedState.setVisibility(
                locked ? View.VISIBLE : View.GONE
        );
        binding.documentsVaultContent.setVisibility(
                locked ? View.GONE : View.VISIBLE
        );
        binding.buttonDocumentsLockNow.setVisibility(
                lockEnabled && unlocked ? View.VISIBLE : View.GONE
        );
        if (locked) {
            adapter.submitList(new ArrayList<>());
        }
    }

    private void runProtected(@NonNull Runnable action) {
        if (preferences.isUnlocked()) {
            action.run();
            return;
        }
        authenticate(() -> {
            preferences.markUnlocked();
            renderLockState();
            loadDocuments();
            action.run();
        });
    }

    private void authenticate(@NonNull Runnable onSuccess) {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        int availability = BiometricManager.from(requireContext())
                .canAuthenticate(authenticators);
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            resetLockSwitch();
            showMessage(R.string.documents_vault_auth_unavailable);
            return;
        }

        authenticationSuccessAction = onSuccess;
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(requireContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result
                    ) {
                        super.onAuthenticationSucceeded(result);
                        Runnable success = authenticationSuccessAction;
                        authenticationSuccessAction = null;
                        if (success != null) {
                            success.run();
                        }
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode,
                            @NonNull CharSequence errorString
                    ) {
                        super.onAuthenticationError(errorCode, errorString);
                        authenticationSuccessAction = null;
                        resetLockSwitch();
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            showMessage(R.string.documents_vault_auth_failed);
                        }
                    }
                }
        );
        BiometricPrompt.PromptInfo promptInfo =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(
                                R.string.documents_vault_auth_title
                        ))
                        .setSubtitle(getString(
                                R.string.documents_vault_auth_subtitle
                        ))
                        .setAllowedAuthenticators(authenticators)
                        .build();
        prompt.authenticate(promptInfo);
    }

    private void resetLockSwitch() {
        if (binding == null) {
            return;
        }
        updatingLockSwitch = true;
        binding.switchDocumentVaultLock.setChecked(
                preferences.lockEnabled()
        );
        updatingLockSwitch = false;
    }

    private void configureReminderSettings() {
        String[] labels = getResources().getStringArray(
                R.array.documents_vault_reminder_labels
        );
        binding.documentReminderDaysInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                labels
        ));
        binding.documentReminderDaysInput.setText(
                reminderLabel(preferences.reminderDays()),
                false
        );
        binding.switchDocumentExpiryAlerts.setChecked(
                preferences.expiryAlertsEnabled()
        );
        binding.documentReminderDaysInput.setEnabled(
                preferences.expiryAlertsEnabled()
        );
        adapter.setReminderDays(preferences.reminderDays());

        binding.switchDocumentExpiryAlerts.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (enabled && Build.VERSION.SDK_INT
                            >= Build.VERSION_CODES.TIRAMISU
                            && ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                        );
                        return;
                    }
                    preferences.setExpiryAlertsEnabled(enabled);
                    binding.documentReminderDaysInput.setEnabled(enabled);
                    DocumentExpiryScheduler.sync(requireContext());
                    if (enabled) {
                        DocumentExpiryScheduler.runNow(requireContext());
                    }
                }
        );
        binding.documentReminderDaysInput.setOnItemClickListener(
                (parent, view, position, id) -> {
                    int days = position == 0
                            ? 7
                            : position == 1
                            ? 15
                            : position == 3
                            ? 60
                            : 30;
                    preferences.setReminderDays(days);
                    adapter.setReminderDays(days);
                    DocumentExpiryScheduler.sync(requireContext());
                    DocumentExpiryScheduler.runNow(requireContext());
                    loadDocuments();
                }
        );
    }

    private void configureCategoryFilter() {
        List<String> categories = new ArrayList<>();
        categories.add(getString(
                R.string.documents_vault_all_categories
        ));
        categories.addAll(Arrays.asList(getResources().getStringArray(
                R.array.documents_vault_category_labels
        )));
        binding.documentCategoryFilterInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                categories
        ));
        binding.documentCategoryFilterInput.setText(categories.get(0), false);
        selectedCategory = "";
        binding.documentCategoryFilterInput.setOnItemClickListener(
                (parent, view, position, id) -> {
                    selectedCategory = position == 0
                            ? ""
                            : categories.get(position);
                    loadDocuments();
                }
        );
    }

    private void configureFilters() {
        binding.documentFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    int checkedId = checkedIds.isEmpty()
                            ? R.id.filter_all_chip
                            : checkedIds.get(0);
                    if (checkedId == R.id.filter_expiring_chip) {
                        filterMode = FilterMode.EXPIRING;
                    } else if (checkedId == R.id.filter_expired_chip) {
                        filterMode = FilterMode.EXPIRED;
                    } else if (checkedId == R.id.filter_favorites_chip) {
                        filterMode = FilterMode.FAVORITES;
                    } else if (checkedId == R.id.filter_emergency_chip) {
                        filterMode = FilterMode.EMERGENCY;
                    } else if (checkedId == R.id.filter_trash_chip) {
                        filterMode = FilterMode.TRASH;
                    } else {
                        filterMode = FilterMode.ALL;
                    }
                    loadDocuments();
                }
        );
    }

    private void configureSearch() {
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
                        loadDocuments();
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
    }

    private void loadDocuments() {
        if (binding == null || repository == null
                || !preferences.isUnlocked()) {
            return;
        }
        DocumentRepository.DocumentsCallback documentsCallback = documents -> {
            if (binding == null || !preferences.isUnlocked()) {
                return;
            }
            loadedDocuments.clear();
            loadedDocuments.addAll(documents);
            applyFilters();
        };
        if (filterMode == FilterMode.TRASH) {
            repository.loadTrash(documentsCallback);
        } else {
            repository.loadDocuments(currentQuery(), documentsCallback);
        }
        repository.loadStats(
                preferences.reminderDays(),
                (total, expiring, expired, trash) -> {
                    if (binding == null) {
                        return;
                    }
                    binding.textDocumentsTotal.setText(String.valueOf(total));
                    binding.textDocumentsExpiring.setText(
                            String.valueOf(expiring)
                    );
                    binding.textDocumentsExpired.setText(
                            String.valueOf(expired)
                    );
                    binding.textDocumentsTrash.setText(String.valueOf(trash));
                }
        );
    }

    private void applyFilters() {
        if (binding == null || !preferences.isUnlocked()) {
            return;
        }
        List<DocumentEntry> visible = new ArrayList<>();
        long now = System.currentTimeMillis();
        int reminderDays = preferences.reminderDays();
        for (DocumentEntry document : loadedDocuments) {
            if (filterMode == FilterMode.TRASH && !matchesQuery(document, currentQuery())) {
                continue;
            }
            if (!selectedCategory.isEmpty()
                    && !selectedCategory.equalsIgnoreCase(document.category)) {
                continue;
            }
            String status = DocumentExpiryPolicy.status(
                    document.expiryAt,
                    now,
                    reminderDays
            );
            if (filterMode == FilterMode.EXPIRING
                    && !DocumentExpiryPolicy.STATUS_EXPIRING.equals(status)) {
                continue;
            }
            if (filterMode == FilterMode.EXPIRED
                    && !DocumentExpiryPolicy.STATUS_EXPIRED.equals(status)) {
                continue;
            }
            if (filterMode == FilterMode.FAVORITES
                    && !preferences.isFavorite(document.id)) {
                continue;
            }
            if (filterMode == FilterMode.EMERGENCY && !document.emergency) {
                continue;
            }
            visible.add(document);
        }

        adapter.setReminderDays(reminderDays);
        adapter.setTrashMode(filterMode == FilterMode.TRASH);
        adapter.submitList(visible);
        boolean empty = visible.isEmpty();
        binding.documentRecyclerView.setVisibility(
                empty ? View.GONE : View.VISIBLE
        );
        binding.documentsEmptyState.setVisibility(
                empty ? View.VISIBLE : View.GONE
        );
        boolean hasAnyDocuments = !loadedDocuments.isEmpty()
                || !currentQuery().isEmpty();
        binding.documentsEmptyTitle.setText(filterMode == FilterMode.TRASH
                ? R.string.documents_vault_trash_empty_title
                : hasAnyDocuments
                ? R.string.documents_vault_no_results_title
                : R.string.documents_empty_title);
        binding.documentsEmptyDetail.setText(filterMode == FilterMode.TRASH
                ? R.string.documents_vault_trash_empty_detail
                : hasAnyDocuments
                ? R.string.documents_vault_no_results_detail
                : R.string.documents_empty_detail);
        binding.emptyAddDocumentButton.setVisibility(
                filterMode == FilterMode.TRASH || hasAnyDocuments ? View.GONE : View.VISIBLE
        );
    }

    private boolean matchesQuery(@NonNull DocumentEntry document, @NonNull String query) {
        String needle = query.trim().toLowerCase(java.util.Locale.ENGLISH);
        if (needle.isEmpty()) return true;
        String value = (document.title + " " + document.category + " "
                + document.documentNumber + " " + document.issuer + " "
                + document.memberName + " " + document.tags + " "
                + document.notes + " " + document.linkedModule + " "
                + document.searchableText)
                .toLowerCase(java.util.Locale.ENGLISH);
        return value.contains(needle);
    }

    private void showDocumentEditorInternal(
            @Nullable DocumentEntry existing
    ) {
        dismissEditor();
        editorBinding = DialogDocumentEditorBinding.inflate(
                getLayoutInflater()
        );
        DialogDocumentEditorBinding dialogBinding = editorBinding;
        String[] categories = getResources().getStringArray(
                R.array.documents_vault_category_labels
        );
        dialogBinding.documentCategoryInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                categories
        ));
        String[] moduleLabels = getResources().getStringArray(
                R.array.documents_vault_module_labels);
        dialogBinding.documentLinkedModuleInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                moduleLabels
        ));

        boolean editing = existing != null;
        editorExpiryAt = editing ? existing.expiryAt : 0L;
        dialogBinding.documentEditorTitle.setText(editing
                ? R.string.documents_vault_editor_edit_title
                : R.string.documents_vault_editor_add_title);
        dialogBinding.documentTitleInput.setText(
                editing ? existing.title : ""
        );
        dialogBinding.documentCategoryInput.setText(
                editing
                        ? existing.category
                        : categories[categories.length - 1],
                false
        );
        dialogBinding.documentNumberInput.setText(
                editing ? existing.documentNumber : "");
        dialogBinding.documentIssuerInput.setText(
                editing ? existing.issuer : "");
        dialogBinding.documentMemberInput.setText(
                editing ? existing.memberName : "");
        dialogBinding.documentLinkedModuleInput.setText(
                editing && !existing.linkedModule.isEmpty()
                        ? existing.linkedModule
                        : getString(R.string.documents_vault_no_link), false);
        dialogBinding.documentTagsInput.setText(
                editing ? existing.tags : "");
        dialogBinding.documentNotesInput.setText(
                editing ? existing.notes : "");
        dialogBinding.documentEmergencySwitch.setChecked(
                editing && existing.emergency);
        renderEditorExpiry();

        if (editing) {
            dialogBinding.selectedDocumentFile.setText(
                    displayFileName(existing.contentUri)
            );
            dialogBinding.chooseDocumentButton.setVisibility(View.GONE);
            dialogBinding.saveDocumentChangesButton.setVisibility(View.VISIBLE);
            dialogBinding.replaceDocumentButton.setVisibility(View.VISIBLE);
        }

        editorDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

        dialogBinding.documentExpiryInput.setOnClickListener(
                clicked -> showExpiryPicker()
        );
        dialogBinding.documentExpiryLayout.setEndIconOnClickListener(
                clicked -> showExpiryPicker()
        );
        dialogBinding.clearDocumentExpiryButton.setOnClickListener(
                clicked -> {
                    editorExpiryAt = 0L;
                    renderEditorExpiry();
                }
        );
        dialogBinding.cancelDocumentButton.setOnClickListener(
                clicked -> dismissEditor()
        );
        dialogBinding.chooseDocumentButton.setOnClickListener(clicked -> {
            DocumentEntry draft = buildDraft(null);
            if (draft == null) {
                return;
            }
            pendingDocument = new PendingDocument(draft, false);
            dismissEditor();
            launchDocumentPicker();
        });
        dialogBinding.saveDocumentChangesButton.setOnClickListener(clicked -> {
            DocumentEntry draft = buildDraft(existing);
            if (draft != null) {
                saveDocument(draft, false, null, false);
            }
        });
        dialogBinding.replaceDocumentButton.setOnClickListener(clicked -> {
            DocumentEntry draft = buildDraft(existing);
            if (draft == null) {
                return;
            }
            pendingDocument = new PendingDocument(draft, true);
            dismissEditor();
            launchDocumentPicker();
        });
        dialogBinding.scanDocumentButton.setOnClickListener(clicked -> {
            DocumentEntry draft = buildDraft(existing);
            if (draft == null) {
                return;
            }
            pendingDocument = new PendingDocument(draft, editing);
            dismissEditor();
            launchCameraCapture();
        });

        editorDialog.setOnDismissListener(dialog -> {
            editorDialog = null;
            editorBinding = null;
        });
        editorDialog.show();
    }

    @Nullable
    private DocumentEntry buildDraft(@Nullable DocumentEntry existing) {
        if (editorBinding == null) {
            return null;
        }
        String title = textOf(editorBinding.documentTitleInput);
        String category = textOf(editorBinding.documentCategoryInput);
        if (title.isEmpty()) {
            editorBinding.documentTitleLayout.setError(
                    getString(R.string.document_title_required)
            );
            return null;
        }
        editorBinding.documentTitleLayout.setError(null);

        DocumentEntry draft = copyOf(existing);
        draft.title = title;
        draft.category = category.isEmpty()
                ? getString(R.string.document_default_category)
                : category;
        draft.expiryAt = editorExpiryAt;
        draft.documentNumber = textOf(editorBinding.documentNumberInput);
        draft.issuer = textOf(editorBinding.documentIssuerInput);
        draft.memberName = textOf(editorBinding.documentMemberInput);
        String linkedModule = textOf(editorBinding.documentLinkedModuleInput);
        draft.linkedModule = linkedModule.equals(
                getString(R.string.documents_vault_no_link)) ? "" : linkedModule;
        draft.tags = textOf(editorBinding.documentTagsInput);
        draft.notes = textOf(editorBinding.documentNotesInput);
        draft.emergency = editorBinding.documentEmergencySwitch.isChecked();
        draft.searchableText = (draft.title + " " + draft.category + " "
                + draft.documentNumber + " " + draft.issuer + " "
                + draft.memberName + " " + draft.tags + " " + draft.notes)
                .trim();
        if (draft.createdAt <= 0L) {
            draft.createdAt = System.currentTimeMillis();
        }
        return draft;
    }

    @NonNull
    private static DocumentEntry copyOf(@Nullable DocumentEntry source) {
        DocumentEntry copy = new DocumentEntry();
        if (source == null) {
            return copy;
        }
        copy.id = source.id;
        copy.title = source.title;
        copy.category = source.category;
        copy.contentUri = source.contentUri;
        copy.mimeType = source.mimeType;
        copy.documentNumber = source.documentNumber;
        copy.issuer = source.issuer;
        copy.memberName = source.memberName;
        copy.tags = source.tags;
        copy.notes = source.notes;
        copy.searchableText = source.searchableText;
        copy.fingerprint = source.fingerprint;
        copy.linkedModule = source.linkedModule;
        copy.emergency = source.emergency;
        copy.issuedAt = source.issuedAt;
        copy.expiryAt = source.expiryAt;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.deletedAt = source.deletedAt;
        copy.previousVersionId = source.previousVersionId;
        return copy;
    }

    private void showExpiryPicker() {
        long selection = editorExpiryAt > 0L
                ? editorExpiryAt
                : MaterialDatePicker.todayInUtcMilliseconds();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder
                .datePicker()
                .setTitleText(R.string.documents_vault_expiry_date)
                .setSelection(selection)
                .build();
        picker.addOnPositiveButtonClickListener(value -> {
            if (value != null) {
                editorExpiryAt = value;
                renderEditorExpiry();
            }
        });
        picker.show(getParentFragmentManager(), "document_expiry_picker");
    }

    private void renderEditorExpiry() {
        if (editorBinding == null) {
            return;
        }
        if (editorExpiryAt <= 0L) {
            editorBinding.documentExpiryInput.setText("");
            editorBinding.clearDocumentExpiryButton.setVisibility(View.GONE);
        } else {
            editorBinding.documentExpiryInput.setText(
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(new Date(editorExpiryAt))
            );
            editorBinding.clearDocumentExpiryButton.setVisibility(View.VISIBLE);
        }
    }

    private void launchDocumentPicker() {
        documentPicker.launch(new String[]{
                "application/pdf",
                "image/*"
        });
    }

    private void launchCameraCapture() {
        try {
            captureTarget = DocumentCaptureStorage.create(requireContext());
            cameraCapture.launch(captureTarget.uri);
        } catch (Exception error) {
            DocumentCaptureStorage.delete(captureTarget);
            captureTarget = null;
            pendingDocument = null;
            showMessage(R.string.documents_vault_scan_failed);
        }
    }

    private void onCameraCaptured(Boolean captured) {
        PendingDocument pending = pendingDocument;
        DocumentCaptureStorage.CaptureTarget target = captureTarget;
        captureTarget = null;
        pendingDocument = null;
        if (!Boolean.TRUE.equals(captured)
                || pending == null
                || target == null
                || !target.file.exists()
                || target.file.length() <= 0L) {
            DocumentCaptureStorage.delete(target);
            if (Boolean.TRUE.equals(captured)) {
                showMessage(R.string.documents_vault_scan_failed);
            }
            return;
        }

        String oldContentUri = pending.document.contentUri;
        pending.document.contentUri = target.uri.toString();
        pending.document.mimeType = "image/jpeg";
        enrichAndSave(
                pending.document,
                pending.replacingFile,
                oldContentUri,
                pending.document.id == 0L
        );
    }

    private void onDocumentPicked(@Nullable Uri uri) {
        PendingDocument pending = pendingDocument;
        if (uri == null || pending == null) {
            pendingDocument = null;
            return;
        }

        String uriValue = uri.toString();
        repository.checkDuplicate(
                uriValue,
                pending.document.id,
                duplicate -> {
                    if (binding == null) {
                        pendingDocument = null;
                        return;
                    }
                    if (duplicate) {
                        pendingDocument = null;
                        showMessage(R.string.documents_vault_duplicate_file);
                        return;
                    }
                    persistPermission(uri);
                    String oldContentUri = pending.document.contentUri;
                    boolean isNew = pending.document.id == 0L;
                    pending.document.contentUri = uriValue;
                    String mime = requireContext().getContentResolver()
                            .getType(uri);
                    pending.document.mimeType = mime == null ? "" : mime;
                    enrichAndSave(
                            pending.document,
                            pending.replacingFile,
                            oldContentUri,
                            isNew
                    );
                    pendingDocument = null;
                }
        );
    }

    private void persistPermission(@NonNull Uri uri) {
        try {
            requireContext().getContentResolver()
                    .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
        } catch (SecurityException ignored) {
            // Some providers grant durable access without this flag.
        }
    }

    private void saveDocument(
            @NonNull DocumentEntry document,
            boolean fileReplaced,
            @Nullable String replacedContentUri,
            boolean isNew
    ) {
        android.content.Context appContext = requireContext().getApplicationContext();
        DocumentRepository.SaveCallback callback = new DocumentRepository.SaveCallback() {
            @Override
            public void onSaved(long documentId) {
                if (binding == null) {
                    return;
                }
                dismissEditor();
                loadDocuments();
                showMessage(fileReplaced
                        ? R.string.documents_vault_renewed
                        : isNew
                        ? R.string.documents_vault_added
                        : R.string.documents_vault_updated);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (isNew || fileReplaced) {
                    DocumentCaptureStorage.deleteIfOwned(
                            appContext,
                            document.contentUri
                    );
                    releasePermission(appContext, document.contentUri);
                }
                if (binding != null) {
                    showMessage(R.string.backup_error_generic);
                }
            }
        };
        if (fileReplaced && replacedContentUri != null && document.id > 0L) {
            DocumentEntry previous = copyOf(document);
            previous.contentUri = replacedContentUri;
            previous.fingerprint = "";
            repository.renew(document, previous, callback);
        } else {
            repository.save(document, callback);
        }
    }

    private void enrichAndSave(
            @NonNull DocumentEntry document,
            boolean fileReplaced,
            @Nullable String replacedContentUri,
            boolean isNew
    ) {
        android.content.Context appContext = requireContext().getApplicationContext();
        DocumentOcrProcessor.enrich(appContext, document, detected -> {
            saveDocument(document, fileReplaced, replacedContentUri, isNew);
            if (detected && binding != null) {
                showMessage(R.string.documents_vault_ocr_complete);
            }
        });
    }

    private void openDocument(@NonNull DocumentEntry document) {
        try {
            Uri uri = Uri.parse(document.contentUri);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(
                            uri,
                            document.mimeType.isEmpty()
                                    ? "*/*"
                                    : document.mimeType
                    )
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(
                    requireContext().getContentResolver(),
                    document.title,
                    uri
            ));
            startActivity(intent);
        } catch (Exception exception) {
            showMessage(R.string.documents_vault_missing_file);
        }
    }

    private void shareDocument(@NonNull DocumentEntry document) {
        String[] options = {
                getString(R.string.documents_vault_share_5_minutes),
                getString(R.string.documents_vault_share_15_minutes),
                getString(R.string.documents_vault_share_60_minutes)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.documents_vault_secure_share_title)
                .setMessage(R.string.documents_vault_secure_share_warning)
                .setItems(options, (dialog, which) -> {
                    long minutes = which == 0 ? 5L : which == 1 ? 15L : 60L;
                    launchSecureShare(document, minutes);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchSecureShare(@NonNull DocumentEntry document, long minutes) {
        try {
            Uri uri = Uri.parse(document.contentUri);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(document.mimeType.isEmpty()
                            ? "*/*"
                            : document.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, document.title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newUri(
                    requireContext().getContentResolver(),
                    document.title,
                    uri
            ));
            startActivity(Intent.createChooser(
                    share,
                    getString(R.string.documents_vault_share_title)
            ));
            android.content.Context shareContext = requireContext().getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> {
                        shareContext.revokeUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }, java.util.concurrent.TimeUnit.MINUTES.toMillis(minutes));
        } catch (Exception exception) {
            showMessage(R.string.documents_vault_missing_file);
        }
    }

    private void showVersionHistory(@NonNull DocumentEntry document) {
        repository.loadVersionHistory(document, versions -> {
            if (binding == null) return;
            String[] labels = new String[versions.size()];
            for (int index = 0; index < versions.size(); index++) {
                DocumentEntry version = versions.get(index);
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(new Date(version.updatedAt > 0L
                                ? version.updatedAt : version.createdAt));
                labels[index] = getString(index == 0
                        ? R.string.documents_vault_current_version
                        : R.string.documents_vault_old_version, date);
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.documents_vault_version_history)
                    .setItems(labels, (dialog, which) -> openDocument(versions.get(which)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void showMissingDocumentsChecklist() {
        repository.loadMissingChecklist((missingItems, hasMembers) -> {
            if (binding == null) return;
            String message;
            if (!hasMembers) {
                message = getString(R.string.documents_vault_add_members_first);
            } else if (missingItems.isEmpty()) {
                message = getString(R.string.documents_vault_all_ready);
            } else {
                StringBuilder builder = new StringBuilder();
                for (String item : missingItems) {
                    if (builder.length() > 0) builder.append("\n");
                    builder.append("• ").append(item);
                }
                message = builder.toString();
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.documents_vault_missing_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void exportInventory(boolean pdf) {
        repository.loadDocuments("", documents -> {
            android.content.Context context = requireContext().getApplicationContext();
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    java.io.File folder = new java.io.File(context.getCacheDir(), "document_reports");
                    if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException();
                    java.io.File file = new java.io.File(folder, "Documents_Inventory_"
                            + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH).format(new java.util.Date())
                            + (pdf ? ".pdf" : ".xls"));
                    if (pdf) DocumentReportExporter.pdf(file, documents);
                    else DocumentReportExporter.excel(file, documents);
                    if (isAdded()) requireActivity().runOnUiThread(() -> shareInventory(file, pdf));
                } catch (Exception error) {
                    if (isAdded()) requireActivity().runOnUiThread(() -> showMessage(R.string.documents_vault_report_error));
                }
            });
        });
    }

    private void showIntegrityCenter() {
        repository.verifyIntegrity((verified, changed, missing, notIndexed, issues) -> {
            if (binding == null) return;
            StringBuilder message = new StringBuilder()
                    .append("Verified: ").append(verified)
                    .append("\nChanged: ").append(changed)
                    .append("\nMissing: ").append(missing)
                    .append("\nNot indexed: ").append(notIndexed)
                    .append("\n\n")
                    .append(getString(R.string.documents_vault_backup_covered));
            if (!issues.isEmpty()) {
                message.append("\n\nNeeds attention:");
                int limit = Math.min(issues.size(), 12);
                for (int index = 0; index < limit; index++) {
                    message.append("\n• ").append(issues.get(index));
                }
                if (issues.size() > limit) {
                    message.append("\n• +").append(issues.size() - limit).append(" more");
                }
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.documents_vault_integrity_title)
                    .setMessage(message.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void shareInventory(@NonNull java.io.File file, boolean pdf) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".backupfiles", file);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(pdf ? "application/pdf" : "application/vnd.ms-excel")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.documents_vault_report_title)));
    }

    private void confirmDelete(@NonNull DocumentEntry document) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.document_remove_title)
                .setMessage(R.string.document_remove_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(
                                document,
                                new DocumentRepository.ActionCallback() {
                                    @Override
                                    public void onComplete() {
                                        preferences.removeFavorite(document.id);
                                        loadDocuments();
                                        showMessage(R.string.documents_vault_moved_to_trash);
                                    }

                                    @Override
                                    public void onError(@NonNull Exception error) {
                                        showMessage(R.string.backup_error_generic);
                                    }
                                }
                        )
                )
                .show();
    }

    private void restoreDocument(@NonNull DocumentEntry document) {
        repository.restore(document, new DocumentRepository.ActionCallback() {
            @Override public void onComplete() {
                loadDocuments();
                showMessage(R.string.documents_vault_restored);
            }
            @Override public void onError(@NonNull Exception error) {
                showMessage(R.string.backup_error_generic);
            }
        });
    }

    private void confirmPermanentDelete(@NonNull DocumentEntry document) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.documents_vault_delete_forever_title)
                .setMessage(R.string.documents_vault_delete_forever_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.documents_vault_delete_forever, (dialog, which) ->
                        repository.permanentlyDelete(document, new DocumentRepository.ActionCallback() {
                            @Override public void onComplete() {
                                preferences.removeFavorite(document.id);
                                releasePermission(document.contentUri);
                                loadDocuments();
                                showMessage(R.string.documents_vault_deleted_forever);
                            }
                            @Override public void onError(@NonNull Exception error) {
                                showMessage(R.string.backup_error_generic);
                            }
                        }))
                .show();
    }

    private void releasePermission(@NonNull String uriValue) {
        if (getContext() == null) return;
        releasePermission(requireContext(), uriValue);
    }

    private static void releasePermission(
            @NonNull android.content.Context context,
            @NonNull String uriValue
    ) {
        try {
            context.getContentResolver()
                    .releasePersistableUriPermission(
                            Uri.parse(uriValue),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
        } catch (Exception ignored) {
            // Restored and camera FileProvider URIs are app-owned.
        }
    }

    @NonNull
    private String displayFileName(@NonNull String uriValue) {
        Uri uri;
        try {
            uri = Uri.parse(uriValue);
        } catch (RuntimeException error) {
            return getString(R.string.documents_vault_file_selected);
        }
        try (Cursor cursor = requireContext().getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to a safe non-sensitive label.
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty()
                ? getString(R.string.documents_vault_file_selected)
                : last;
    }

    @NonNull
    private String currentQuery() {
        return binding == null
                ? ""
                : textOf(binding.documentSearchInput);
    }

    @NonNull
    private String reminderLabel(int days) {
        if (days == 7) {
            return getString(R.string.documents_vault_days_7);
        }
        if (days == 15) {
            return getString(R.string.documents_vault_days_15);
        }
        if (days == 60) {
            return getString(R.string.documents_vault_days_60);
        }
        return getString(R.string.documents_vault_days_30);
    }

    @NonNull
    private static String textOf(@NonNull EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    private void showMessage(int messageRes) {
        if (binding == null) {
            return;
        }
        Snackbar.make(
                binding.getRoot(),
                messageRes,
                Snackbar.LENGTH_LONG
        ).show();
    }

    private void dismissEditor() {
        if (editorDialog != null) {
            editorDialog.dismiss();
        }
        editorDialog = null;
        editorBinding = null;
    }

    @Override
    public void onDestroyView() {
        dismissEditor();
        authenticationSuccessAction = null;
        DocumentCaptureStorage.delete(captureTarget);
        captureTarget = null;
        pendingDocument = null;
        if (binding != null) {
            binding.documentRecyclerView.setAdapter(null);
        }
        binding = null;
        super.onDestroyView();
    }

    private static final class PendingDocument {
        @NonNull
        final DocumentEntry document;
        final boolean replacingFile;

        PendingDocument(
                @NonNull DocumentEntry document,
                boolean replacingFile
        ) {
            this.document = document;
            this.replacingFile = replacingFile;
        }
    }
}
