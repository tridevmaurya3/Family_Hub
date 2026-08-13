package com.tridev.familyhub.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Complete UI for portable encrypted backup, restore and scheduling. */
public final class BackupRestoreActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);

    private BackupPreferences preferences;

    private View progressOverlay;
    private ProgressBar progressBar;
    private TextView progressStage;
    private TextView readinessText;
    private TextView destinationText;
    private TextInputLayout passwordLayout;
    private TextInputLayout confirmPasswordLayout;
    private EditText passwordInput;
    private EditText confirmPasswordInput;
    private TextView passwordSavedText;
    private MaterialButton savePasswordButton;
    private MaterialButton backupNowButton;
    private MaterialSwitch automaticSwitch;
    private MaterialSwitch wifiOnlySwitch;
    private MaterialSwitch chargingOnlySwitch;
    private AutoCompleteTextView frequencyInput;
    private TextView lastBackupTime;
    private TextView lastBackupFile;
    private TextView lastBackupSummary;
    private TextView lastBackupError;

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    this::handleFolderSelected
            );

    private final ActivityResultLauncher<String[]> restorePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::handleRestoreFileSelected
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_restore);
        preferences = new BackupPreferences(this);

        bindViews();
        bindActions();
        configureFrequencyDropdown();
        renderAll();

        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!operationRunning.get()) {
                            finish();
                        }
                    }
                }
        );
    }

    private void bindViews() {
        progressOverlay = findViewById(R.id.backupProgressOverlay);
        progressBar = findViewById(R.id.progressBackupOperation);
        progressStage = findViewById(R.id.textBackupProgressStage);
        readinessText = findViewById(R.id.textBackupReadiness);
        destinationText = findViewById(R.id.textBackupDestination);
        passwordLayout = findViewById(R.id.layoutBackupPassword);
        confirmPasswordLayout = findViewById(
                R.id.layoutBackupPasswordConfirm
        );
        passwordInput = findViewById(R.id.inputBackupPassword);
        confirmPasswordInput = findViewById(
                R.id.inputBackupPasswordConfirm
        );
        passwordSavedText = findViewById(R.id.textBackupPasswordSaved);
        savePasswordButton = findViewById(R.id.buttonSaveBackupPassword);
        backupNowButton = findViewById(R.id.buttonBackupNow);
        automaticSwitch = findViewById(R.id.switchAutomaticBackup);
        wifiOnlySwitch = findViewById(R.id.switchBackupWifiOnly);
        chargingOnlySwitch = findViewById(R.id.switchBackupChargingOnly);
        frequencyInput = findViewById(R.id.inputBackupFrequency);
        lastBackupTime = findViewById(R.id.textLastBackupTime);
        lastBackupFile = findViewById(R.id.textLastBackupFile);
        lastBackupSummary = findViewById(R.id.textLastBackupSummary);
        lastBackupError = findViewById(R.id.textLastBackupError);
    }

    private void bindActions() {
        findViewById(R.id.buttonBackupBack).setOnClickListener(
                ignored -> finish()
        );
        findViewById(R.id.buttonChooseBackupFolder).setOnClickListener(
                ignored -> folderPicker.launch(preferences.destinationTreeUri())
        );
        savePasswordButton.setOnClickListener(
                ignored -> saveBackupPassword()
        );
        backupNowButton.setOnClickListener(
                ignored -> createManualBackup()
        );
        findViewById(R.id.buttonRestoreBackup).setOnClickListener(
                ignored -> restorePicker.launch(new String[]{
                        BackupArchiveManager.MIME_TYPE,
                        "application/zip",
                        "*/*"
                })
        );
        findViewById(R.id.buttonSaveBackupSchedule).setOnClickListener(
                ignored -> saveSchedule()
        );
    }

    private void configureFrequencyDropdown() {
        String[] labels = new String[]{
                getString(R.string.backup_frequency_daily),
                getString(R.string.backup_frequency_weekly)
        };
        frequencyInput.setAdapter(new ArrayAdapter<>(
                this,
                R.layout.item_form_dropdown,
                labels
        ));
    }

    private void renderAll() {
        renderReadiness();
        renderPasswordState();
        renderSchedule();
        renderLastBackup();
    }

    private void renderReadiness() {
        Uri destinationUri = preferences.destinationTreeUri();
        boolean hasFolder = destinationUri != null;
        boolean hasPassword = preferences.hasPassword();
        if (hasFolder && hasPassword) {
            readinessText.setText(R.string.backup_status_ready);
        } else if (!hasFolder && !hasPassword) {
            readinessText.setText(R.string.backup_status_need_both);
        } else if (!hasFolder) {
            readinessText.setText(R.string.backup_status_need_folder);
        } else {
            readinessText.setText(R.string.backup_status_need_password);
        }

        String label = preferences.destinationLabel();
        destinationText.setText(hasFolder
                ? getString(
                R.string.backup_status_destination,
                label.isEmpty() ? destinationUri.toString() : label
        )
                : getString(R.string.backup_destination_not_selected));
        backupNowButton.setEnabled(hasFolder && hasPassword);
    }

    private void renderPasswordState() {
        boolean saved = preferences.hasPassword();
        passwordSavedText.setVisibility(saved ? View.VISIBLE : View.GONE);
        savePasswordButton.setText(saved
                ? R.string.backup_change_password
                : R.string.backup_save_password);
    }

    private void renderSchedule() {
        automaticSwitch.setChecked(preferences.autoBackupEnabled());
        wifiOnlySwitch.setChecked(preferences.wifiOnly());
        chargingOnlySwitch.setChecked(preferences.chargingOnly());
        frequencyInput.setText(
                BackupPreferences.FREQUENCY_DAILY.equals(
                        preferences.frequency()
                )
                        ? getString(R.string.backup_frequency_daily)
                        : getString(R.string.backup_frequency_weekly),
                false
        );
    }

    private void renderLastBackup() {
        long completedAt = preferences.lastSuccessAt();
        if (completedAt <= 0L) {
            lastBackupTime.setText(R.string.backup_never);
            lastBackupFile.setVisibility(View.GONE);
            lastBackupSummary.setVisibility(View.GONE);
        } else {
            lastBackupTime.setText(getString(
                    R.string.backup_last_time,
                    DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT
                    ).format(new Date(completedAt))
            ));
            lastBackupFile.setVisibility(View.VISIBLE);
            lastBackupFile.setText(getString(
                    R.string.backup_last_file,
                    preferences.lastFileName()
            ));
            lastBackupSummary.setVisibility(View.VISIBLE);
            lastBackupSummary.setText(getString(
                    R.string.backup_last_summary,
                    preferences.lastRecordCount(),
                    preferences.lastAttachmentCount(),
                    Formatter.formatFileSize(
                            this,
                            preferences.lastByteCount()
                    )
            ));
        }

        String error = preferences.lastError();
        lastBackupError.setVisibility(
                error.isEmpty() ? View.GONE : View.VISIBLE
        );
        if (!error.isEmpty()) {
            lastBackupError.setText(getString(
                    R.string.backup_last_error,
                    error
            ));
        }
    }

    private void handleFolderSelected(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            String label = folder == null || folder.getName() == null
                    ? uri.getLastPathSegment()
                    : folder.getName();
            preferences.setDestination(
                    uri,
                    label == null ? "Selected folder" : label
            );
            BackupScheduler.sync(this);
            renderAll();
            Toast.makeText(
                    this,
                    R.string.backup_folder_selected,
                    Toast.LENGTH_SHORT
            ).show();
        } catch (SecurityException error) {
            showError(R.string.backup_error_folder);
        }
    }

    private void saveBackupPassword() {
        char[] password = charsOf(passwordInput);
        char[] confirmation = charsOf(confirmPasswordInput);
        try {
            passwordLayout.setError(null);
            confirmPasswordLayout.setError(null);
            if (!BackupPasswordPolicy.isValid(password)) {
                passwordLayout.setError(
                        getString(R.string.backup_password_weak)
                );
                return;
            }
            if (!BackupPasswordPolicy.matches(password, confirmation)) {
                confirmPasswordLayout.setError(
                        getString(R.string.backup_password_mismatch)
                );
                return;
            }
            preferences.savePassword(password);
            passwordInput.setText("");
            confirmPasswordInput.setText("");
            BackupScheduler.sync(this);
            renderAll();
            Toast.makeText(
                    this,
                    R.string.backup_password_saved_message,
                    Toast.LENGTH_SHORT
            ).show();
        } finally {
            BackupPreferences.wipe(password);
            BackupPreferences.wipe(confirmation);
        }
    }

    private void saveSchedule() {
        boolean enabled = automaticSwitch.isChecked();
        if (enabled && (preferences.destinationTreeUri() == null
                || !preferences.hasPassword())) {
            automaticSwitch.setChecked(false);
            preferences.setAutoBackupEnabled(false);
            BackupScheduler.disable(this);
            Toast.makeText(
                    this,
                    R.string.backup_schedule_incomplete,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        preferences.setAutoBackupEnabled(enabled);
        preferences.setWifiOnly(wifiOnlySwitch.isChecked());
        preferences.setChargingOnly(chargingOnlySwitch.isChecked());
        preferences.setFrequency(getString(
                R.string.backup_frequency_daily
        ).contentEquals(frequencyInput.getText())
                ? BackupPreferences.FREQUENCY_DAILY
                : BackupPreferences.FREQUENCY_WEEKLY);
        BackupScheduler.sync(this);
        renderAll();
        Toast.makeText(
                this,
                R.string.backup_schedule_saved,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void createManualBackup() {
        Uri treeUri = preferences.destinationTreeUri();
        char[] password = preferences.readPassword();
        if (treeUri == null || password == null) {
            BackupPreferences.wipe(password);
            renderAll();
            Toast.makeText(
                    this,
                    R.string.backup_schedule_incomplete,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (!beginOperation(
                getString(R.string.backup_progress_preparing)
        )) {
            BackupPreferences.wipe(password);
            return;
        }

        executor.execute(() -> {
            DocumentFile created = null;
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(
                        this,
                        treeUri
                );
                if (folder == null || !folder.exists() || !folder.canWrite()) {
                    throw new SecurityException("BACKUP_FOLDER_UNAVAILABLE");
                }
                String requestedName = BackupArchiveManager.createFileName();
                created = folder.createFile(
                        BackupArchiveManager.MIME_TYPE,
                        requestedName
                );
                if (created == null) {
                    throw new IllegalStateException(
                            "Unable to create backup file"
                    );
                }

                BackupArchiveManager.BackupSummary summary;
                try (OutputStream output = getContentResolver()
                        .openOutputStream(created.getUri(), "w")) {
                    if (output == null) {
                        throw new IllegalStateException(
                                "Unable to write backup file"
                        );
                    }
                    summary = BackupArchiveManager.createBackup(
                            this,
                            output,
                            password,
                            this::postProgress
                    );
                }

                String finalName = created.getName() == null
                        ? requestedName
                        : created.getName();
                preferences.recordSuccess(
                        System.currentTimeMillis(),
                        finalName,
                        summary.totalRecords,
                        summary.attachmentCount,
                        Math.max(0L, created.length())
                );
                BackupArchiveManager.BackupSummary finalSummary = summary;
                runOnUiThread(() -> {
                    finishOperation();
                    renderAll();
                    String skipped = finalSummary.skippedAttachmentCount > 0
                            ? getString(
                            R.string.backup_skipped_suffix,
                            finalSummary.skippedAttachmentCount
                    )
                            : "";
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.backup_success_title)
                            .setMessage(getString(
                                    R.string.backup_success_message,
                                    finalSummary.totalRecords,
                                    finalSummary.attachmentCount,
                                    skipped
                            ))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } catch (Exception error) {
                if (created != null) {
                    created.delete();
                }
                preferences.recordFailure(safeMessage(error));
                runOnUiThread(() -> {
                    finishOperation();
                    renderAll();
                    showMappedError(error, false);
                });
            } finally {
                BackupPreferences.wipe(password);
            }
        });
    }

    private void handleRestoreFileSelected(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        showRestorePasswordDialog(uri);
    }

    private void showRestorePasswordDialog(@NonNull Uri uri) {
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_backup_password,
                null,
                false
        );
        TextInputLayout layout = content.findViewById(
                R.id.layoutRestorePassword
        );
        EditText input = content.findViewById(R.id.inputRestorePassword);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_restore_password_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.backup_continue, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener(clicked -> {
            char[] password = charsOf(input);
            if (password.length < BackupPasswordPolicy.MIN_LENGTH) {
                layout.setError(getString(
                        R.string.backup_error_wrong_password
                ));
                BackupPreferences.wipe(password);
                return;
            }
            layout.setError(null);
            dialog.dismiss();
            inspectRestoreFile(uri, password);
        }));
        dialog.show();
    }

    private void inspectRestoreFile(
            @NonNull Uri uri,
            @NonNull char[] password
    ) {
        if (!beginOperation(
                getString(R.string.backup_progress_restoring)
        )) {
            BackupPreferences.wipe(password);
            return;
        }
        executor.execute(() -> {
            try (InputStream input = getContentResolver()
                    .openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("Unable to open backup");
                }
                BackupArchiveManager.BackupPreview preview =
                        BackupArchiveManager.inspectBackup(
                                this,
                                input,
                                password
                        );
                runOnUiThread(() -> {
                    finishOperation();
                    showRestorePreview(uri, password, preview);
                });
            } catch (Exception error) {
                BackupPreferences.wipe(password);
                runOnUiThread(() -> {
                    finishOperation();
                    showMappedError(error, true);
                });
            }
        });
    }

    private void showRestorePreview(
            @NonNull Uri uri,
            @NonNull char[] password,
            @NonNull BackupArchiveManager.BackupPreview preview
    ) {
        String created = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT
        ).format(new Date(preview.createdAt));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_restore_preview_title)
                .setMessage(getString(
                        R.string.backup_restore_preview_message,
                        created,
                        preview.appVersion.isEmpty()
                                ? "Unknown"
                                : preview.appVersion,
                        preview.databaseVersion,
                        preview.totalRecords,
                        preview.attachmentCount,
                        preview.skippedAttachmentCount
                ))
                .setNegativeButton(
                        R.string.backup_restore_cancel,
                        (ignored, which) -> BackupPreferences.wipe(password)
                )
                .setPositiveButton(R.string.backup_restore_confirm, null)
                .create();
        dialog.setOnCancelListener(
                ignored -> BackupPreferences.wipe(password)
        );
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener(clicked -> {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
            executeRestore(uri, password);
        }));
        dialog.show();
    }

    private void executeRestore(
            @NonNull Uri uri,
            @NonNull char[] password
    ) {
        if (!beginOperation(
                getString(R.string.backup_progress_restoring)
        )) {
            BackupPreferences.wipe(password);
            return;
        }
        executor.execute(() -> {
            try (InputStream input = getContentResolver()
                    .openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("Unable to open backup");
                }
                BackupArchiveManager.RestoreResult result =
                        BackupArchiveManager.restoreBackup(
                                this,
                                input,
                                password,
                                this::postProgress
                        );
                postProgress(96, "Rebuilding reminders and safe places");
                BackupPostRestoreCoordinator.rebuild(this);
                runOnUiThread(() -> {
                    finishOperation();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.backup_restore_success_title)
                            .setMessage(getString(
                                    R.string.backup_restore_success_message,
                                    result.restoredRecords,
                                    result.restoredAttachments
                            ))
                            .setCancelable(false)
                            .setPositiveButton(
                                    android.R.string.ok,
                                    (dialog, which) -> restartApplication()
                            )
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    finishOperation();
                    showMappedError(error, true);
                });
            } finally {
                BackupPreferences.wipe(password);
            }
        });
    }

    private void restartApplication() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );
        startActivity(intent);
        finish();
    }

    private boolean beginOperation(@NonNull String initialStage) {
        if (!operationRunning.compareAndSet(false, true)) {
            return false;
        }
        progressBar.setProgress(2);
        progressStage.setText(initialStage);
        progressOverlay.setVisibility(View.VISIBLE);
        return true;
    }

    private void postProgress(int percent, @NonNull String stage) {
        runOnUiThread(() -> {
            if (!operationRunning.get()) {
                return;
            }
            progressBar.setProgress(percent);
            progressStage.setText(stage);
        });
    }

    private void finishOperation() {
        operationRunning.set(false);
        progressOverlay.setVisibility(View.GONE);
    }

    private void showMappedError(
            @NonNull Exception error,
            boolean restoreOperation
    ) {
        String message = error.getMessage();
        int messageRes;
        if (message != null && (message.contains("WRONG_PASSWORD")
                || message.contains("AEADBadTag")
                || message.contains("Tag mismatch"))) {
            messageRes = R.string.backup_error_wrong_password;
        } else if (message != null && (message.contains("NOT_FAMILY_HUB")
                || message.contains("MISSING_manifest"))) {
            messageRes = R.string.backup_error_not_family_hub;
        } else if (message != null
                && message.contains("BACKUP_FROM_NEWER_APP")) {
            messageRes = R.string.backup_error_newer_app;
        } else if (message != null
                && message.toLowerCase().contains("vault")) {
            messageRes = R.string.backup_error_vault;
        } else if (message != null
                && message.contains("FOLDER")) {
            messageRes = R.string.backup_error_folder;
        } else if (restoreOperation && message != null
                && (message.contains("RECORD_COUNT_MISMATCH")
                || message.contains("RESTORE_INSERT_FAILED"))) {
            messageRes = R.string.backup_restore_rollback;
        } else {
            messageRes = R.string.backup_error_generic;
        }
        showError(messageRes);
    }

    private void showError(int messageRes) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_error_title)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private static char[] charsOf(@NonNull EditText input) {
        return input.getText() == null
                ? new char[0]
                : input.getText().toString().toCharArray();
    }

    @NonNull
    private static String safeMessage(@NonNull Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Encrypted backup failed";
        }
        return message.length() > 160
                ? message.substring(0, 160)
                : message;
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
