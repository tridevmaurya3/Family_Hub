package com.tridev.familyhub.data.repository;

import org.junit.Test;
import static org.junit.Assert.*;

public class FamilySyncMergePolicyTest {
    @Test public void newerServerEditWinsEvenIfLocalPhoneClockWasFarAhead() {
        assertTrue(FamilySyncMergePolicy.shouldApply(false,false,200,100,1_000,9_000_000));
    }
    @Test public void olderServerEditCannotWinBecauseItsPhoneClockWasAhead() {
        assertFalse(FamilySyncMergePolicy.shouldApply(false,false,100,200,9_000_000,1_000));
        assertFalse(FamilySyncMergePolicy.shouldApply(false,false,200,200,9_000_000,1_000));
    }
    @Test public void pendingOfflineEditIsProtectedUntilItsAcknowledgement() {
        assertFalse(FamilySyncMergePolicy.shouldApply(true,false,300,100,9_000,1_000));
        assertTrue(FamilySyncMergePolicy.shouldApply(false,false,300,100,9_000,1_000));
    }
    @Test public void missingCopyCanBeRestoredAndLegacyPayloadKeepsCompatibleOrdering() {
        assertTrue(FamilySyncMergePolicy.shouldApply(false,true,100,200,100,200));
        assertFalse(FamilySyncMergePolicy.shouldApply(true,true,100,200,100,200));
        assertTrue(FamilySyncMergePolicy.shouldApply(false,false,0,0,200,100));
        assertFalse(FamilySyncMergePolicy.shouldApply(false,false,0,0,100,200));
    }
}
