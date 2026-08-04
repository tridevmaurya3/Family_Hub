package com.tridev.familyhub.backup;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-portable AES-256-GCM encryption for Family Hub backup archives.
 *
 * The password never leaves the device. A random salt and IV are generated for
 * every backup, and the complete ZIP payload (including its manifest) is
 * authenticated and encrypted.
 */
public final class BackupCrypto {

    private static final int MAGIC = 0x4648424B; // FHBK
    private static final int FORMAT_VERSION = 1;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int BUFFER_SIZE = 64 * 1024;

    private BackupCrypto() {
    }

    public static void encrypt(
            @NonNull File plainArchive,
            @NonNull OutputStream destination,
            @NonNull char[] password
    ) throws IOException, GeneralSecurityException {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] keyBytes = deriveKey(password, salt, PBKDF2_ITERATIONS);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );

            DataOutputStream header = new DataOutputStream(
                    new BufferedOutputStream(destination)
            );
            header.writeInt(MAGIC);
            header.writeInt(FORMAT_VERSION);
            header.writeInt(PBKDF2_ITERATIONS);
            header.writeInt(salt.length);
            header.writeInt(iv.length);
            header.write(salt);
            header.write(iv);
            header.flush();

            try (InputStream input = new BufferedInputStream(
                    new FileInputStream(plainArchive)
            ); CipherOutputStream encrypted = new CipherOutputStream(
                    header,
                    cipher
            )) {
                copy(input, encrypted);
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void decrypt(
            @NonNull InputStream encryptedSource,
            @NonNull File plainArchive,
            @NonNull char[] password
    ) throws IOException, GeneralSecurityException {
        DataInputStream header = new DataInputStream(
                new BufferedInputStream(encryptedSource)
        );

        int magic = header.readInt();
        int version = header.readInt();
        int iterations = header.readInt();
        int saltLength = header.readInt();
        int ivLength = header.readInt();

        if (magic != MAGIC) {
            throw new GeneralSecurityException("NOT_FAMILY_HUB_BACKUP");
        }
        if (version != FORMAT_VERSION) {
            throw new GeneralSecurityException("UNSUPPORTED_BACKUP_VERSION");
        }
        if (iterations < 100_000 || iterations > 1_000_000) {
            throw new GeneralSecurityException("INVALID_KDF_CONFIGURATION");
        }
        if (saltLength != SALT_BYTES || ivLength != IV_BYTES) {
            throw new GeneralSecurityException("INVALID_BACKUP_HEADER");
        }

        byte[] salt = new byte[saltLength];
        byte[] iv = new byte[ivLength];
        header.readFully(salt);
        header.readFully(iv);
        byte[] keyBytes = deriveKey(password, salt, iterations);

        File parent = plainArchive.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create backup staging directory");
        }

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );

            try (CipherInputStream decrypted = new CipherInputStream(
                    header,
                    cipher
            ); OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(plainArchive)
            )) {
                copy(decrypted, output);
            } catch (IOException error) {
                // CipherInputStream reports a wrong password or tampered backup
                // as an IOException when the GCM authentication tag is checked.
                if (plainArchive.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    plainArchive.delete();
                }
                throw new GeneralSecurityException(
                        "WRONG_PASSWORD_OR_CORRUPTED_BACKUP",
                        error
                );
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    @NonNull
    private static byte[] deriveKey(
            @NonNull char[] password,
            @NonNull byte[] salt,
            int iterations
    ) throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(
                password,
                salt,
                iterations,
                KEY_BITS
        );
        try {
            return SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    @NonNull
    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
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
}
