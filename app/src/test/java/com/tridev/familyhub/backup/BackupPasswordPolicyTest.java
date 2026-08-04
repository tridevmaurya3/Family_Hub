package com.tridev.familyhub.backup;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BackupPasswordPolicyTest {

    @Test
    public void validPasswordRequiresLetterDigitAndMinimumLength() {
        assertTrue(BackupPasswordPolicy.isValid(
                "Family123".toCharArray()
        ));
        assertFalse(BackupPasswordPolicy.isValid(
                "12345678".toCharArray()
        ));
        assertFalse(BackupPasswordPolicy.isValid(
                "FamilyHub".toCharArray()
        ));
        assertFalse(BackupPasswordPolicy.isValid(
                "Abc123".toCharArray()
        ));
    }

    @Test
    public void matchingPasswordsAreComparedCompletely() {
        assertTrue(BackupPasswordPolicy.matches(
                "Family123".toCharArray(),
                "Family123".toCharArray()
        ));
        assertFalse(BackupPasswordPolicy.matches(
                "Family123".toCharArray(),
                "Family124".toCharArray()
        ));
        assertFalse(BackupPasswordPolicy.matches(
                "Family123".toCharArray(),
                "Family1234".toCharArray()
        ));
    }
}
