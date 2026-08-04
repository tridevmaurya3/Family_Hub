package com.tridev.familyhub.backup;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Writes scheduled encrypted backups to the user-selected SAF folder. */
public final class EncryptedBackupWorker extends Worker {

    private static final int MAX_AUTOMATIC_BACKUPS = 7;

    public EncryptedBackupWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParameters
    ) {
        super(appContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        BackupPreferences preferences = new BackupPreferences(context);
        if (!preferences.isReadyForAutomaticBackup()) {
            return Result.success();
        }

        Uri treeUri = preferences.destinationTreeUri();
        char[] password = preferences.readPassword();
        if (treeUri == null || password == null) {
            BackupScheduler.disable(context);
            return Result.failure();
        }

        DocumentFile createdFile = null;
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(context, treeUri);
            if (folder == null || !folder.exists() || !folder.canWrite()) {
                preferences.recordFailure("Backup folder is no longer writable");
                BackupScheduler.disable(context);
                return Result.failure();
            }

            String fileName = BackupArchiveManager.createFileName();
            createdFile = folder.createFile(
                    BackupArchiveManager.MIME_TYPE,
                    fileName
            );
            if (createdFile == null) {
                throw new IOException("Unable to create backup file");
            }

            BackupArchiveManager.BackupSummary summary;
            try (OutputStream output = context.getContentResolver()
                    .openOutputStream(createdFile.getUri(), "w")) {
                if (output == null) {
                    throw new IOException("Unable to open backup destination");
                }
                summary = BackupArchiveManager.createBackup(
                        context,
                        output,
                        password,
                        null
                );
            }

            long completedAt = System.currentTimeMillis();
            preferences.recordSuccess(
                    completedAt,
                    createdFile.getName() == null
                            ? fileName
                            : createdFile.getName(),
                    summary.totalRecords,
                    summary.attachmentCount,
                    Math.max(0L, createdFile.length())
            );
            pruneOldBackups(folder, createdFile.getUri());
            return Result.success();
        } catch (SecurityException error) {
            if (createdFile != null) {
                createdFile.delete();
            }
            preferences.recordFailure("Backup folder permission was removed");
            BackupScheduler.disable(context);
            return Result.failure();
        } catch (Exception error) {
            if (createdFile != null) {
                createdFile.delete();
            }
            preferences.recordFailure(safeMessage(error));
            return getRunAttemptCount() < 3
                    ? Result.retry()
                    : Result.failure();
        } finally {
            BackupPreferences.wipe(password);
        }
    }

    private void pruneOldBackups(
            @NonNull DocumentFile folder,
            @NonNull Uri newestUri
    ) {
        DocumentFile[] files = folder.listFiles();
        List<DocumentFile> backups = new ArrayList<>();
        for (DocumentFile file : files) {
            String name = file.getName();
            if (file.isFile()
                    && name != null
                    && name.startsWith("FamilyHub_")
                    && name.endsWith(BackupArchiveManager.FILE_EXTENSION)) {
                backups.add(file);
            }
        }
        backups.sort(Comparator.comparingLong(DocumentFile::lastModified)
                .reversed());
        for (int index = MAX_AUTOMATIC_BACKUPS;
             index < backups.size();
             index++) {
            DocumentFile candidate = backups.get(index);
            if (!newestUri.equals(candidate.getUri())) {
                candidate.delete();
            }
        }
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
}
