package com.tridev.familyhub.backup;

import androidx.annotation.NonNull;

/** Validation rules shared by manual and scheduled encrypted backups. */
public final class BackupPasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private BackupPasswordPolicy() {
    }

    public static boolean isValid(@NonNull char[] password) {
        if (password.length < MIN_LENGTH || password.length > MAX_LENGTH) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char value : password) {
            if (Character.isLetter(value)) {
                hasLetter = true;
            } else if (Character.isDigit(value)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }

    public static boolean matches(
            @NonNull char[] first,
            @NonNull char[] second
    ) {
        if (first.length != second.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < first.length; index++) {
            difference |= first[index] ^ second[index];
        }
        return difference == 0;
    }
}
