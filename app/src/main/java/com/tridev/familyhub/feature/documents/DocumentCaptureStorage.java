package com.tridev.familyhub.feature.documents;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.tridev.familyhub.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Creates durable private image targets for document camera scans. */
public final class DocumentCaptureStorage {

    private static final String DIRECTORY = "document_captures";
    private static final String AUTHORITY_SUFFIX = ".backupfiles";

    private DocumentCaptureStorage() {
    }

    @NonNull
    public static CaptureTarget create(@NonNull Context context)
            throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create document capture folder");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Document capture path is unavailable");
        }
        String name = "Document_"
                + System.currentTimeMillis()
                + "_"
                + UUID.randomUUID().toString().substring(0, 8)
                + ".jpg";
        File file = new File(directory, name);
        if (!file.createNewFile()) {
            throw new IOException("Unable to create document capture file");
        }
        Uri uri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX,
                file
        );
        return new CaptureTarget(file, uri);
    }

    public static void delete(@Nullable CaptureTarget target) {
        if (target != null && target.file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.file.delete();
        }
    }

    public static void deleteIfOwned(
            @NonNull Context context,
            @NonNull String uriValue
    ) {
        Uri uri;
        try {
            uri = Uri.parse(uriValue);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!"content".equalsIgnoreCase(uri.getScheme())
                || !(BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX)
                .equals(uri.getAuthority())) {
            return;
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
            return;
        }
        File candidate = new File(
                new File(context.getFilesDir(), DIRECTORY),
                name
        );
        try {
            String root = new File(context.getFilesDir(), DIRECTORY)
                    .getCanonicalPath() + File.separator;
            if (candidate.getCanonicalPath().startsWith(root)) {
                //noinspection ResultOfMethodCallIgnored
                candidate.delete();
            }
        } catch (IOException ignored) {
            // Never delete outside the private capture directory.
        }
    }

    public static final class CaptureTarget {
        @NonNull public final File file;
        @NonNull public final Uri uri;

        private CaptureTarget(@NonNull File file, @NonNull Uri uri) {
            this.file = file;
            this.uri = uri;
        }
    }
}
