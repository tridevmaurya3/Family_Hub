package com.tridev.familyhub.backup;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.tridev.familyhub.BuildConfig;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.FamilyHubDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates and restores portable Family Hub archives.
 *
 * The outer archive is AES-256-GCM encrypted. Database rows are exported in a
 * schema-aware JSON format so Android-Keystore encrypted Password Vault values
 * can be re-encrypted after a reinstall. Restore is transactional and verifies
 * every table count before committing.
 */
public final class BackupArchiveManager {

    public static final String FILE_EXTENSION = ".fhbackup";
    public static final String MIME_TYPE = "application/octet-stream";
    public static final int ARCHIVE_VERSION = 1;
    public static final int CURRENT_DATABASE_VERSION = 16;

    private static final String ENTRY_MANIFEST = "manifest.json";
    private static final String ENTRY_DATA = "data.json";
    private static final String ATTACHMENT_PREFIX = "attachments/";
    private static final String ATTACHMENT_URI_PREFIX =
            "fhbackup://attachment/";
    private static final String RESTORED_FILES_DIRECTORY =
            "restored_backup_files";
    private static final String SAFETY_PREFERENCES =
            "family_hub_safety_alert_preferences";
    private static final long MAX_ATTACHMENT_BYTES = 250L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final List<String> TABLES = Collections.unmodifiableList(
            Arrays.asList(
                    "family_members",
                    "finance_entries",
                    "reminders",
                    "family_live_status",
                    "documents",
                    "password_entries",
                    "health_records",
                    "vehicles",
                    "properties",
                    "grocery_items",
                    "notes",
                    "planner_items",
                    "safe_places",
                    "safe_place_alerts"
            )
    );

    private static final Set<String> PORTABLE_VAULT_COLUMNS = Set.of(
            "usernameEncrypted",
            "passwordEncrypted",
            "notesEncrypted"
    );

    private BackupArchiveManager() {
    }

    public interface ProgressListener {
        void onProgress(int percent, @NonNull String stage);
    }

    @NonNull
    public static String createFileName() {
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
        return "FamilyHub_" + timestamp + FILE_EXTENSION;
    }

    @NonNull
    public static BackupSummary createBackup(
            @NonNull Context context,
            @NonNull OutputStream destination,
            @NonNull char[] password,
            @Nullable ProgressListener progressListener
    ) throws Exception {
        validatePassword(password);
        Context appContext = context.getApplicationContext();
        File stagingDirectory = new File(
                appContext.getCacheDir(),
                "encrypted_backup_staging"
        );
        ensureDirectory(stagingDirectory);
        File plainArchive = new File(
                stagingDirectory,
                UUID.randomUUID() + ".zip"
        );

        try {
            notifyProgress(progressListener, 5, "Preparing local data");
            MutableSummary mutableSummary = writePlainArchive(
                    appContext,
                    plainArchive,
                    progressListener
            );
            notifyProgress(progressListener, 88, "Encrypting backup");
            BackupCrypto.encrypt(plainArchive, destination, password);
            notifyProgress(progressListener, 100, "Backup complete");
            return mutableSummary.toSummary();
        } finally {
            secureDelete(plainArchive);
        }
    }

    @NonNull
    public static BackupPreview inspectBackup(
            @NonNull Context context,
            @NonNull InputStream encryptedSource,
            @NonNull char[] password
    ) throws Exception {
        validatePasswordLengthOnly(password);
        File plainArchive = createTemporaryPlainArchive(context);
        try {
            BackupCrypto.decrypt(encryptedSource, plainArchive, password);
            JSONObject manifest = readJsonEntry(plainArchive, ENTRY_MANIFEST);
            validateManifest(manifest);
            return BackupPreview.fromManifest(manifest);
        } finally {
            secureDelete(plainArchive);
        }
    }

    @NonNull
    public static RestoreResult restoreBackup(
            @NonNull Context context,
            @NonNull InputStream encryptedSource,
            @NonNull char[] password,
            @Nullable ProgressListener progressListener
    ) throws Exception {
        validatePasswordLengthOnly(password);
        Context appContext = context.getApplicationContext();
        File plainArchive = createTemporaryPlainArchive(appContext);
        String restoreId = "restore_" + System.currentTimeMillis()
                + "_" + UUID.randomUUID().toString().substring(0, 8);
        File finalAttachmentRoot = new File(
                new File(appContext.getFilesDir(), RESTORED_FILES_DIRECTORY),
                restoreId
        );
        List<File> createdFiles = new ArrayList<>();

        try {
            notifyProgress(progressListener, 5, "Decrypting backup");
            BackupCrypto.decrypt(encryptedSource, plainArchive, password);
            JSONObject manifest = readJsonEntry(plainArchive, ENTRY_MANIFEST);
            validateManifest(manifest);
            BackupPreview preview = BackupPreview.fromManifest(manifest);
            if (preview.databaseVersion > CURRENT_DATABASE_VERSION) {
                throw new BackupException("BACKUP_FROM_NEWER_APP");
            }

            notifyProgress(progressListener, 22, "Checking backup contents");
            JSONObject data = readJsonEntry(plainArchive, ENTRY_DATA);
            validateData(data);

            notifyProgress(progressListener, 32, "Restoring secure files");
            ensureDirectory(finalAttachmentRoot);
            Map<String, String> attachmentUris = extractAttachments(
                    appContext,
                    plainArchive,
                    finalAttachmentRoot,
                    createdFiles
            );

            notifyProgress(progressListener, 48, "Replacing local records");
            restoreDatabaseTransactionally(
                    appContext,
                    data,
                    attachmentUris,
                    preview,
                    progressListener
            );

            restoreSafetyPreferences(appContext, data);
            cleanupOtherRestoredDirectories(finalAttachmentRoot);
            notifyProgress(progressListener, 100, "Restore complete");
            return new RestoreResult(
                    preview.totalRecords,
                    preview.attachmentCount,
                    preview.skippedAttachmentCount
            );
        } catch (Exception error) {
            deleteRecursively(finalAttachmentRoot);
            throw error;
        } finally {
            secureDelete(plainArchive);
        }
    }

    @NonNull
    private static MutableSummary writePlainArchive(
            @NonNull Context context,
            @NonNull File plainArchive,
            @Nullable ProgressListener progressListener
    ) throws Exception {
        MutableSummary summary = new MutableSummary();
        summary.createdAt = System.currentTimeMillis();
        summary.backupId = UUID.randomUUID().toString();

        FamilyHubDatabase room = FamilyHubDatabase.getInstance(context);
        SupportSQLiteDatabase database = room.getOpenHelper()
                .getWritableDatabase();

        JSONObject data = new JSONObject();
        JSONArray tablesJson = new JSONArray();
        File attachmentStaging = new File(
                plainArchive.getParentFile(),
                "attachments_" + UUID.randomUUID()
        );
        ensureDirectory(attachmentStaging);

        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(plainArchive))
        )) {
            database.beginTransaction();
            try {
                int tableIndex = 0;
                for (String table : TABLES) {
                    tableIndex++;
                    int percent = 8 + (tableIndex * 48 / TABLES.size());
                    notifyProgress(
                            progressListener,
                            percent,
                            "Backing up " + friendlyTableName(table)
                    );
                    JSONObject exported = exportTable(
                            context,
                            database,
                            table,
                            zip,
                            attachmentStaging,
                            summary
                    );
                    tablesJson.put(exported);
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }

            data.put("archiveVersion", ARCHIVE_VERSION);
            data.put("databaseVersion", currentDatabaseVersion(database));
            data.put("tables", tablesJson);
            data.put("safetyPreferences", exportSafetyPreferences(context));

            JSONObject manifest = buildManifest(summary, data);
            notifyProgress(progressListener, 72, "Writing encrypted archive");
            writeTextEntry(zip, ENTRY_DATA, data.toString());
            writeTextEntry(zip, ENTRY_MANIFEST, manifest.toString());
        } finally {
            deleteRecursively(attachmentStaging);
        }

        return summary;
    }

    @NonNull
    private static JSONObject exportTable(
            @NonNull Context context,
            @NonNull SupportSQLiteDatabase database,
            @NonNull String table,
            @NonNull ZipOutputStream zip,
            @NonNull File attachmentStaging,
            @NonNull MutableSummary summary
    ) throws Exception {
        JSONObject result = new JSONObject();
        result.put("name", table);
        JSONArray columnsJson = new JSONArray();
        Map<String, String> columnTypes = readColumnTypes(database, table);
        for (Map.Entry<String, String> entry : columnTypes.entrySet()) {
            JSONObject column = new JSONObject();
            column.put("name", entry.getKey());
            column.put("type", entry.getValue());
            columnsJson.put(column);
        }
        result.put("columns", columnsJson);

        JSONArray rows = new JSONArray();
        try (Cursor cursor = database.query(
                "SELECT * FROM " + quoteIdentifier(table)
        )) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                long rowId = valueAsLong(cursor, "id", cursor.getPosition() + 1L);
                for (int index = 0; index < cursor.getColumnCount(); index++) {
                    String column = cursor.getColumnName(index);
                    Object value = cursorValue(cursor, index);

                    if ("password_entries".equals(table)
                            && PORTABLE_VAULT_COLUMNS.contains(column)
                            && value instanceof String) {
                        value = VaultCipher.decryptOrThrow((String) value);
                    }

                    if (isAttachmentColumn(table, column)
                            && value instanceof String
                            && !((String) value).trim().isEmpty()) {
                        String mimeType = resolveAttachmentMimeType(
                                context,
                                table,
                                row,
                                cursor,
                                (String) value
                        );
                        String token = backupAttachment(
                                context,
                                zip,
                                attachmentStaging,
                                table,
                                rowId,
                                (String) value,
                                mimeType,
                                summary
                        );
                        if (token != null) {
                            value = ATTACHMENT_URI_PREFIX + token;
                        }
                    }

                    putJsonValue(row, column, value);
                }
                rows.put(row);
            }
        }

        result.put("rows", rows);
        result.put("count", rows.length());
        summary.tableCounts.put(table, rows.length());
        summary.totalRecords += rows.length();
        return result;
    }

    @Nullable
    private static String backupAttachment(
            @NonNull Context context,
            @NonNull ZipOutputStream zip,
            @NonNull File attachmentStaging,
            @NonNull String table,
            long rowId,
            @NonNull String uriValue,
            @NonNull String mimeType,
            @NonNull MutableSummary summary
    ) {
        Uri uri;
        try {
            uri = Uri.parse(uriValue);
        } catch (RuntimeException error) {
            summary.skippedAttachmentCount++;
            return null;
        }

        String extension = extensionFor(mimeType, uriValue);
        String entryName = ATTACHMENT_PREFIX
                + safeFilePart(table)
                + "/"
                + Math.max(0L, rowId)
                + "_"
                + UUID.randomUUID().toString().substring(0, 8)
                + extension;
        File staged = new File(
                attachmentStaging,
                UUID.randomUUID() + extension
        );

        try (InputStream input = context.getContentResolver()
                .openInputStream(uri);
             OutputStream output = new BufferedOutputStream(
                     new FileOutputStream(staged)
             )) {
            if (input == null) {
                summary.skippedAttachmentCount++;
                return null;
            }
            long bytes = copyWithLimit(input, output, MAX_ATTACHMENT_BYTES);
            if (bytes <= 0L) {
                summary.skippedAttachmentCount++;
                return null;
            }

            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream stagedInput = new BufferedInputStream(
                    new FileInputStream(staged)
            )) {
                copy(stagedInput, zip);
            }
            zip.closeEntry();
            summary.attachmentCount++;
            summary.attachmentBytes += bytes;
            return entryName;
        } catch (Exception error) {
            summary.skippedAttachmentCount++;
            return null;
        } finally {
            secureDelete(staged);
        }
    }

    @NonNull
    private static JSONObject buildManifest(
            @NonNull MutableSummary summary,
            @NonNull JSONObject data
    ) throws JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("product", "Family Hub");
        manifest.put("packageName", BuildConfig.APPLICATION_ID);
        manifest.put("archiveVersion", ARCHIVE_VERSION);
        manifest.put("databaseVersion", data.getInt("databaseVersion"));
        manifest.put("appVersion", BuildConfig.VERSION_NAME);
        manifest.put("createdAt", summary.createdAt);
        manifest.put("backupId", summary.backupId);
        manifest.put("totalRecords", summary.totalRecords);
        manifest.put("attachmentCount", summary.attachmentCount);
        manifest.put(
                "skippedAttachmentCount",
                summary.skippedAttachmentCount
        );
        manifest.put("attachmentBytes", summary.attachmentBytes);
        JSONObject counts = new JSONObject();
        for (Map.Entry<String, Integer> entry
                : summary.tableCounts.entrySet()) {
            counts.put(entry.getKey(), entry.getValue());
        }
        manifest.put("tableCounts", counts);
        manifest.put("portableVault", true);
        manifest.put("authenticatedEncryption", "AES-256-GCM");
        return manifest;
    }

    private static void restoreDatabaseTransactionally(
            @NonNull Context context,
            @NonNull JSONObject data,
            @NonNull Map<String, String> attachmentUris,
            @NonNull BackupPreview preview,
            @Nullable ProgressListener progressListener
    ) throws Exception {
        Map<String, JSONObject> backupTables = tableMap(data);
        FamilyHubDatabase room = FamilyHubDatabase.getInstance(context);
        SupportSQLiteDatabase database = room.getOpenHelper()
                .getWritableDatabase();

        database.execSQL("PRAGMA foreign_keys=OFF");
        database.beginTransaction();
        try {
            List<String> deletionOrder = new ArrayList<>(TABLES);
            Collections.reverse(deletionOrder);
            for (String table : deletionOrder) {
                database.execSQL(
                        "DELETE FROM " + quoteIdentifier(table)
                );
            }
            // Offline retry payloads are device/keystore specific and are
            // deliberately not carried across restore.
            database.execSQL("DELETE FROM pending_location_uploads");

            int index = 0;
            for (String table : TABLES) {
                index++;
                JSONObject tableJson = backupTables.get(table);
                if (tableJson == null) {
                    continue;
                }
                notifyProgress(
                        progressListener,
                        48 + (index * 36 / TABLES.size()),
                        "Restoring " + friendlyTableName(table)
                );
                insertTable(
                        database,
                        table,
                        tableJson,
                        attachmentUris
                );
            }

            verifyCounts(database, preview.tableCounts);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
            database.execSQL("PRAGMA foreign_keys=ON");
        }
    }

    private static void insertTable(
            @NonNull SupportSQLiteDatabase database,
            @NonNull String table,
            @NonNull JSONObject tableJson,
            @NonNull Map<String, String> attachmentUris
    ) throws Exception {
        if (!TABLES.contains(table)) {
            throw new BackupException("UNKNOWN_TABLE");
        }

        Map<String, String> currentColumns = readColumnTypes(database, table);
        JSONArray rows = tableJson.getJSONArray("rows");
        for (int rowIndex = 0; rowIndex < rows.length(); rowIndex++) {
            JSONObject row = rows.getJSONObject(rowIndex);
            ContentValues values = new ContentValues();
            for (String column : currentColumns.keySet()) {
                if (!row.has(column) || row.isNull(column)) {
                    continue;
                }
                Object value = row.get(column);
                if (value instanceof String
                        && ((String) value).startsWith(
                        ATTACHMENT_URI_PREFIX
                )) {
                    String entryName = ((String) value).substring(
                            ATTACHMENT_URI_PREFIX.length()
                    );
                    String restoredUri = attachmentUris.get(entryName);
                    value = restoredUri == null ? "" : restoredUri;
                }

                if ("password_entries".equals(table)
                        && PORTABLE_VAULT_COLUMNS.contains(column)) {
                    value = VaultCipher.encrypt(String.valueOf(value));
                }

                putContentValue(
                        values,
                        column,
                        currentColumns.get(column),
                        value
                );
            }
            long inserted = database.insert(
                    table,
                    SupportSQLiteDatabase.CONFLICT_REPLACE,
                    values
            );
            if (inserted == -1L) {
                throw new BackupException("RESTORE_INSERT_FAILED");
            }
        }
    }

    private static void verifyCounts(
            @NonNull SupportSQLiteDatabase database,
            @NonNull Map<String, Integer> expectedCounts
    ) throws Exception {
        for (String table : TABLES) {
            int expected = expectedCounts.getOrDefault(table, 0);
            int actual = 0;
            try (Cursor cursor = database.query(
                    "SELECT COUNT(*) FROM " + quoteIdentifier(table)
            )) {
                if (cursor.moveToFirst()) {
                    actual = cursor.getInt(0);
                }
            }
            if (actual != expected) {
                throw new BackupException(
                        "RECORD_COUNT_MISMATCH:" + table
                );
            }
        }
    }

    @NonNull
    private static Map<String, String> extractAttachments(
            @NonNull Context context,
            @NonNull File plainArchive,
            @NonNull File destinationRoot,
            @NonNull List<File> createdFiles
    ) throws Exception {
        Map<String, String> result = new HashMap<>();
        String rootCanonical = destinationRoot.getCanonicalPath()
                + File.separator;

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(plainArchive))
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.startsWith(ATTACHMENT_PREFIX)) {
                    zip.closeEntry();
                    continue;
                }

                String relative = name.substring(ATTACHMENT_PREFIX.length());
                File target = new File(destinationRoot, relative);
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(rootCanonical)) {
                    throw new BackupException("UNSAFE_ARCHIVE_PATH");
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    ensureDirectory(parent);
                }
                try (OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(target)
                )) {
                    copyWithLimit(zip, output, MAX_ATTACHMENT_BYTES);
                }
                createdFiles.add(target);
                Uri uri = FileProvider.getUriForFile(
                        context,
                        BuildConfig.APPLICATION_ID + ".backupfiles",
                        target
                );
                result.put(name, uri.toString());
                zip.closeEntry();
            }
        }
        return result;
    }

    @NonNull
    private static JSONObject readJsonEntry(
            @NonNull File plainArchive,
            @NonNull String requestedEntry
    ) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(plainArchive))
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && requestedEntry.equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    copyWithLimit(zip, output, 64L * 1024L * 1024L);
                    return new JSONObject(output.toString(
                            StandardCharsets.UTF_8.name()
                    ));
                }
                zip.closeEntry();
            }
        }
        throw new BackupException("MISSING_" + requestedEntry);
    }

    private static void validateManifest(@NonNull JSONObject manifest)
            throws Exception {
        if (!"Family Hub".equals(manifest.optString("product"))) {
            throw new BackupException("NOT_FAMILY_HUB_BACKUP");
        }
        if (manifest.optInt("archiveVersion", -1) != ARCHIVE_VERSION) {
            throw new BackupException("UNSUPPORTED_BACKUP_VERSION");
        }
        if (!manifest.optBoolean("portableVault", false)) {
            throw new BackupException("NON_PORTABLE_VAULT_BACKUP");
        }
        if (manifest.optLong("createdAt", 0L) <= 0L) {
            throw new BackupException("INVALID_BACKUP_MANIFEST");
        }
    }

    private static void validateData(@NonNull JSONObject data)
            throws Exception {
        if (data.optInt("archiveVersion", -1) != ARCHIVE_VERSION) {
            throw new BackupException("UNSUPPORTED_BACKUP_VERSION");
        }
        if (!data.has("tables") || !(data.get("tables") instanceof JSONArray)) {
            throw new BackupException("INVALID_BACKUP_DATA");
        }
        Map<String, JSONObject> tables = tableMap(data);
        for (String table : tables.keySet()) {
            if (!TABLES.contains(table)) {
                throw new BackupException("UNKNOWN_TABLE");
            }
        }
    }

    @NonNull
    private static Map<String, JSONObject> tableMap(@NonNull JSONObject data)
            throws JSONException {
        Map<String, JSONObject> result = new HashMap<>();
        JSONArray tables = data.getJSONArray("tables");
        for (int index = 0; index < tables.length(); index++) {
            JSONObject table = tables.getJSONObject(index);
            String name = table.getString("name");
            if (result.put(name, table) != null) {
                throw new JSONException("Duplicate backup table: " + name);
            }
        }
        return result;
    }

    @NonNull
    private static JSONObject exportSafetyPreferences(
            @NonNull Context context
    ) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(
                SAFETY_PREFERENCES,
                Context.MODE_PRIVATE
        );
        JSONObject result = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof String) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static void restoreSafetyPreferences(
            @NonNull Context context,
            @NonNull JSONObject data
    ) throws JSONException {
        JSONObject source = data.optJSONObject("safetyPreferences");
        if (source == null) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(
                SAFETY_PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear();
        for (String key : source.keySet()) {
            Object value = source.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                editor.putFloat(key, ((Double) value).floatValue());
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        if (!editor.commit()) {
            throw new JSONException("Unable to restore safety preferences");
        }
    }

    @NonNull
    private static Map<String, String> readColumnTypes(
            @NonNull SupportSQLiteDatabase database,
            @NonNull String table
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        try (Cursor cursor = database.query(
                "PRAGMA table_info(" + quoteIdentifier(table) + ")"
        )) {
            int nameIndex = cursor.getColumnIndex("name");
            int typeIndex = cursor.getColumnIndex("type");
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                String type = cursor.getString(typeIndex);
                result.put(
                        name,
                        type == null ? "TEXT" : type.toUpperCase(Locale.ROOT)
                );
            }
        }
        return result;
    }

    private static int currentDatabaseVersion(
            @NonNull SupportSQLiteDatabase database
    ) {
        try (Cursor cursor = database.query("PRAGMA user_version")) {
            return cursor.moveToFirst()
                    ? cursor.getInt(0)
                    : CURRENT_DATABASE_VERSION;
        }
    }

    @Nullable
    private static Object cursorValue(@NonNull Cursor cursor, int index) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_NULL:
                return null;
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT:
                return cursor.getDouble(index);
            case Cursor.FIELD_TYPE_BLOB:
                return "fhblob:" + Base64.encodeToString(
                        cursor.getBlob(index),
                        Base64.NO_WRAP
                );
            case Cursor.FIELD_TYPE_STRING:
            default:
                return cursor.getString(index);
        }
    }

    private static void putJsonValue(
            @NonNull JSONObject object,
            @NonNull String key,
            @Nullable Object value
    ) throws JSONException {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    private static void putContentValue(
            @NonNull ContentValues values,
            @NonNull String column,
            @Nullable String declaredType,
            @NonNull Object value
    ) {
        String type = declaredType == null
                ? "TEXT"
                : declaredType.toUpperCase(Locale.ROOT);
        if (value instanceof String
                && ((String) value).startsWith("fhblob:")) {
            values.put(
                    column,
                    Base64.decode(
                            ((String) value).substring("fhblob:".length()),
                            Base64.NO_WRAP
                    )
            );
        } else if (type.contains("INT")) {
            values.put(column, ((Number) value).longValue());
        } else if (type.contains("REAL")
                || type.contains("FLOA")
                || type.contains("DOUB")) {
            values.put(column, ((Number) value).doubleValue());
        } else if (type.contains("BLOB") && value instanceof String) {
            values.put(column, Base64.decode((String) value, Base64.NO_WRAP));
        } else {
            values.put(column, String.valueOf(value));
        }
    }

    private static boolean isAttachmentColumn(
            @NonNull String table,
            @NonNull String column
    ) {
        return ("documents".equals(table) && "contentUri".equals(column))
                || ("family_members".equals(table)
                && "profilePhotoUri".equals(column));
    }

    @NonNull
    private static String resolveAttachmentMimeType(
            @NonNull Context context,
            @NonNull String table,
            @NonNull JSONObject partialRow,
            @NonNull Cursor cursor,
            @NonNull String uriValue
    ) {
        if ("documents".equals(table)) {
            int mimeIndex = cursor.getColumnIndex("mimeType");
            if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                String mime = cursor.getString(mimeIndex);
                if (mime != null && !mime.trim().isEmpty()) {
                    return mime.trim();
                }
            }
        }
        try {
            String resolved = context.getContentResolver().getType(
                    Uri.parse(uriValue)
            );
            return resolved == null ? "application/octet-stream" : resolved;
        } catch (RuntimeException ignored) {
            return "application/octet-stream";
        }
    }

    @NonNull
    private static String extensionFor(
            @NonNull String mimeType,
            @NonNull String uriValue
    ) {
        String extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType);
        if (extension == null || extension.trim().isEmpty()) {
            String last = Uri.parse(uriValue).getLastPathSegment();
            if (last != null) {
                int dot = last.lastIndexOf('.');
                if (dot >= 0 && dot < last.length() - 1) {
                    extension = last.substring(dot + 1);
                }
            }
        }
        if (extension == null || !extension.matches("[A-Za-z0-9]{1,10}")) {
            return ".bin";
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    private static long valueAsLong(
            @NonNull Cursor cursor,
            @NonNull String column,
            long fallback
    ) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index)
                ? cursor.getLong(index)
                : fallback;
    }

    @NonNull
    private static File createTemporaryPlainArchive(
            @NonNull Context context
    ) throws IOException {
        File directory = new File(
                context.getCacheDir(),
                "encrypted_backup_restore"
        );
        ensureDirectory(directory);
        return new File(directory, UUID.randomUUID() + ".zip");
    }

    private static void writeTextEntry(
            @NonNull ZipOutputStream zip,
            @NonNull String name,
            @NonNull String value
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void validatePassword(@NonNull char[] password)
            throws BackupException {
        if (!BackupPasswordPolicy.isValid(password)) {
            throw new BackupException("WEAK_BACKUP_PASSWORD");
        }
    }

    private static void validatePasswordLengthOnly(@NonNull char[] password)
            throws BackupException {
        if (password.length < BackupPasswordPolicy.MIN_LENGTH
                || password.length > BackupPasswordPolicy.MAX_LENGTH) {
            throw new BackupException("INVALID_BACKUP_PASSWORD");
        }
    }

    private static void notifyProgress(
            @Nullable ProgressListener listener,
            int percent,
            @NonNull String stage
    ) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, percent)), stage);
        }
    }

    private static void ensureDirectory(@Nullable File directory)
            throws IOException {
        if (directory == null) {
            throw new IOException("Missing directory");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create directory: " + directory);
        }
        if (!directory.isDirectory()) {
            throw new IOException("Not a directory: " + directory);
        }
    }

    private static long copyWithLimit(
            @NonNull InputStream input,
            @NonNull OutputStream output,
            long maximumBytes
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("BACKUP_ENTRY_TOO_LARGE");
            }
            output.write(buffer, 0, read);
        }
        output.flush();
        return total;
    }

    private static void copy(
            @NonNull InputStream input,
            @NonNull OutputStream output
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    @NonNull
    private static String quoteIdentifier(@NonNull String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe SQL identifier");
        }
        return "`" + identifier + "`";
    }

    @NonNull
    private static String safeFilePart(@NonNull String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @NonNull
    private static String friendlyTableName(@NonNull String table) {
        return table.replace('_', ' ');
    }

    private static void cleanupOtherRestoredDirectories(
            @NonNull File currentDirectory
    ) {
        File root = currentDirectory.getParentFile();
        if (root == null) {
            return;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.equals(currentDirectory)) {
                deleteRecursively(child);
            }
        }
    }

    private static void secureDelete(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            try (OutputStream output = new FileOutputStream(file, false)) {
                byte[] zeros = new byte[8192];
                long remaining = file.length();
                while (remaining > 0L) {
                    int size = (int) Math.min(zeros.length, remaining);
                    output.write(zeros, 0, size);
                    remaining -= size;
                }
                output.flush();
            } catch (IOException ignored) {
                // Best-effort overwrite; encrypted storage remains protected.
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static final class BackupSummary {
        public final long createdAt;
        public final long totalRecords;
        public final int attachmentCount;
        public final int skippedAttachmentCount;
        public final long attachmentBytes;
        @NonNull public final Map<String, Integer> tableCounts;

        private BackupSummary(
                long createdAt,
                long totalRecords,
                int attachmentCount,
                int skippedAttachmentCount,
                long attachmentBytes,
                @NonNull Map<String, Integer> tableCounts
        ) {
            this.createdAt = createdAt;
            this.totalRecords = totalRecords;
            this.attachmentCount = attachmentCount;
            this.skippedAttachmentCount = skippedAttachmentCount;
            this.attachmentBytes = attachmentBytes;
            this.tableCounts = Collections.unmodifiableMap(
                    new LinkedHashMap<>(tableCounts)
            );
        }
    }

    public static final class BackupPreview {
        public final int archiveVersion;
        public final int databaseVersion;
        @NonNull public final String appVersion;
        public final long createdAt;
        @NonNull public final String backupId;
        public final long totalRecords;
        public final int attachmentCount;
        public final int skippedAttachmentCount;
        public final long attachmentBytes;
        @NonNull public final Map<String, Integer> tableCounts;

        private BackupPreview(
                int archiveVersion,
                int databaseVersion,
                @NonNull String appVersion,
                long createdAt,
                @NonNull String backupId,
                long totalRecords,
                int attachmentCount,
                int skippedAttachmentCount,
                long attachmentBytes,
                @NonNull Map<String, Integer> tableCounts
        ) {
            this.archiveVersion = archiveVersion;
            this.databaseVersion = databaseVersion;
            this.appVersion = appVersion;
            this.createdAt = createdAt;
            this.backupId = backupId;
            this.totalRecords = totalRecords;
            this.attachmentCount = attachmentCount;
            this.skippedAttachmentCount = skippedAttachmentCount;
            this.attachmentBytes = attachmentBytes;
            this.tableCounts = Collections.unmodifiableMap(tableCounts);
        }

        @NonNull
        private static BackupPreview fromManifest(
                @NonNull JSONObject manifest
        ) throws JSONException {
            Map<String, Integer> counts = new LinkedHashMap<>();
            JSONObject countObject = manifest.optJSONObject("tableCounts");
            if (countObject != null) {
                for (String key : countObject.keySet()) {
                    if (TABLES.contains(key)) {
                        counts.put(key, Math.max(0, countObject.optInt(key, 0)));
                    }
                }
            }
            for (String table : TABLES) {
                counts.putIfAbsent(table, 0);
            }
            return new BackupPreview(
                    manifest.getInt("archiveVersion"),
                    manifest.optInt("databaseVersion", 0),
                    manifest.optString("appVersion", ""),
                    manifest.getLong("createdAt"),
                    manifest.optString("backupId", ""),
                    Math.max(0L, manifest.optLong("totalRecords", 0L)),
                    Math.max(0, manifest.optInt("attachmentCount", 0)),
                    Math.max(
                            0,
                            manifest.optInt("skippedAttachmentCount", 0)
                    ),
                    Math.max(0L, manifest.optLong("attachmentBytes", 0L)),
                    counts
            );
        }
    }

    public static final class RestoreResult {
        public final long restoredRecords;
        public final int restoredAttachments;
        public final int skippedAttachments;

        private RestoreResult(
                long restoredRecords,
                int restoredAttachments,
                int skippedAttachments
        ) {
            this.restoredRecords = restoredRecords;
            this.restoredAttachments = restoredAttachments;
            this.skippedAttachments = skippedAttachments;
        }
    }

    public static final class BackupException extends Exception {
        public BackupException(@NonNull String message) {
            super(message);
        }
    }

    private static final class MutableSummary {
        long createdAt;
        long totalRecords;
        int attachmentCount;
        int skippedAttachmentCount;
        long attachmentBytes;
        String backupId = "";
        final Map<String, Integer> tableCounts = new LinkedHashMap<>();

        @NonNull
        BackupSummary toSummary() {
            return new BackupSummary(
                    createdAt,
                    totalRecords,
                    attachmentCount,
                    skippedAttachmentCount,
                    attachmentBytes,
                    tableCounts
            );
        }
    }
}
