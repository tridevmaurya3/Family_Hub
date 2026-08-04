package com.tridev.familyhub.backup;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

public final class BackupCryptoTest {

    @Test
    public void encryptAndDecryptPreservesArchiveBytes() throws Exception {
        byte[] original = (
                "Family Hub portable encrypted backup\n"
                        + "records=42\nfiles=3"
        ).getBytes(StandardCharsets.UTF_8);
        File source = File.createTempFile("family_hub_source", ".zip");
        File restored = File.createTempFile("family_hub_restored", ".zip");
        char[] password = "Family123".toCharArray();

        try {
            Files.write(source.toPath(), original);
            ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
            BackupCrypto.encrypt(source, encrypted, password);

            BackupCrypto.decrypt(
                    new ByteArrayInputStream(encrypted.toByteArray()),
                    restored,
                    password
            );

            assertArrayEquals(original, Files.readAllBytes(restored.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            //noinspection ResultOfMethodCallIgnored
            restored.delete();
            BackupPreferences.wipe(password);
        }
    }

    @Test
    public void wrongPasswordCannotDecryptBackup() throws Exception {
        File source = File.createTempFile("family_hub_source", ".zip");
        File restored = File.createTempFile("family_hub_restored", ".zip");
        char[] correctPassword = "Family123".toCharArray();
        char[] wrongPassword = "Another456".toCharArray();

        try {
            Files.write(
                    source.toPath(),
                    "secret backup".getBytes(StandardCharsets.UTF_8)
            );
            ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
            BackupCrypto.encrypt(source, encrypted, correctPassword);

            assertThrows(Exception.class, () -> BackupCrypto.decrypt(
                    new ByteArrayInputStream(encrypted.toByteArray()),
                    restored,
                    wrongPassword
            ));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            //noinspection ResultOfMethodCallIgnored
            restored.delete();
            BackupPreferences.wipe(correctPassword);
            BackupPreferences.wipe(wrongPassword);
        }
    }
}
