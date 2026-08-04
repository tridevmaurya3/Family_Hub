package com.tridev.familyhub.feature.passwordvault;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.entity.PasswordEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chrome/Edge compatible CSV reader and writer. Secrets are never logged. */
public final class PasswordCsvTransfer {

    public static final class PlainCredential {
        @NonNull public final String title;
        @NonNull public final String website;
        @NonNull public final String username;
        @NonNull public final String password;
        @NonNull public final String notes;

        PlainCredential(
                @NonNull String title,
                @NonNull String website,
                @NonNull String username,
                @NonNull String password,
                @NonNull String notes
        ) {
            this.title = title;
            this.website = website;
            this.username = username;
            this.password = password;
            this.notes = notes;
        }
    }

    private PasswordCsvTransfer() {
    }

    @NonNull
    public static List<PlainCredential> read(
            @NonNull Context context,
            @NonNull Uri uri
    ) throws Exception {
        InputStream stream = context.getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new IllegalArgumentException("Unable to open CSV file");
        }
        StringBuilder csv = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                csv.append(buffer, 0, count);
            }
        }

        List<List<String>> rows = parseRows(csv.toString());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty");
        }
        Map<String, Integer> columns = headerMap(rows.get(0));
        int passwordColumn = column(columns, "password", "pass");
        if (passwordColumn < 0) {
            throw new IllegalArgumentException("Password column not found");
        }
        int titleColumn = column(columns, "name", "title");
        int websiteColumn = column(
                columns,
                "url",
                "origin",
                "website"
        );
        int usernameColumn = column(
                columns,
                "username",
                "user",
                "login_username"
        );
        int notesColumn = column(columns, "note", "notes");

        List<PlainCredential> credentials = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String password = value(row, passwordColumn).trim();
            if (password.isEmpty()) {
                continue;
            }
            String website = value(row, websiteColumn).trim();
            String title = value(row, titleColumn).trim();
            if (title.isEmpty()) {
                title = website.isEmpty() ? "Imported credential" : website;
            }
            credentials.add(new PlainCredential(
                    title,
                    website,
                    value(row, usernameColumn).trim(),
                    password,
                    value(row, notesColumn).trim()
            ));
        }
        return credentials;
    }

    public static void write(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull List<PasswordEntry> entries
    ) throws Exception {
        OutputStream stream = context.getContentResolver().openOutputStream(
                uri,
                "wt"
        );
        if (stream == null) {
            throw new IllegalArgumentException("Unable to create CSV file");
        }
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(stream, StandardCharsets.UTF_8)
        )) {
            writer.write("name,url,username,password,note\r\n");
            for (PasswordEntry entry : entries) {
                writer.write(csvCell(entry.title));
                writer.write(',');
                writer.write(csvCell(entry.website));
                writer.write(',');
                writer.write(csvCell(VaultCipher.decryptOrThrow(
                        entry.usernameEncrypted
                )));
                writer.write(',');
                writer.write(csvCell(VaultCipher.decryptOrThrow(
                        entry.passwordEncrypted
                )));
                writer.write(',');
                writer.write(csvCell(VaultCipher.decryptOrThrow(
                        entry.notesEncrypted
                )));
                writer.write("\r\n");
            }
        }
    }

    @NonNull
    public static PasswordEntry encryptedEntry(
            @NonNull PlainCredential credential
    ) {
        PasswordEntry entry = new PasswordEntry();
        entry.title = credential.title;
        entry.website = credential.website;
        entry.usernameEncrypted = VaultCipher.encrypt(credential.username);
        entry.passwordEncrypted = VaultCipher.encrypt(credential.password);
        entry.notesEncrypted = VaultCipher.encrypt(credential.notes);
        entry.createdAt = System.currentTimeMillis();
        return entry;
    }

    @NonNull
    public static String duplicateKey(
            @NonNull String title,
            @NonNull String website,
            @NonNull String username
    ) {
        return (title.trim() + "\u0000" + website.trim() + "\u0000"
                + username.trim()).toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static Map<String, Integer> headerMap(
            @NonNull List<String> headers
    ) {
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index)
                    .replace("\uFEFF", "")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            columns.put(header, index);
        }
        return columns;
    }

    private static int column(
            @NonNull Map<String, Integer> columns,
            @NonNull String... names
    ) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    @NonNull
    private static String value(@NonNull List<String> row, int column) {
        return column >= 0 && column < row.size() ? row.get(column) : "";
    }

    @NonNull
    private static String csvCell(@NonNull String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @NonNull
    private static List<List<String>> parseRows(@NonNull String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < csv.length(); index++) {
            char character = csv.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < csv.length()
                        && csv.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((character == '\n' || character == '\r') && !quoted) {
                if (character == '\r' && index + 1 < csv.length()
                        && csv.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!isBlank(row)) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(character);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!isBlank(row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isBlank(@NonNull List<String> row) {
        for (String value : row) {
            if (!value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
