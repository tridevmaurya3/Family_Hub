package com.tridev.familyhub.data.repository;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class SharedCachePolicyTest {
    @Test public void reconnectRemovesAnAcknowledgedCopyDeletedByAnotherPhone() {
        assertTrue(SharedCachePolicy.canRemove(true,"A","A","deleted",Set.of("remaining"),false));
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","remaining",Set.of("remaining"),false));
    }
    @Test public void acknowledgedDeletionDoesNotDependOnPhoneClock() {
        assertTrue(SharedCachePolicy.canRemove(true,"A","A","deleted",Set.of(),false));
    }
    @Test public void reconnectProtectsPendingEditsButRemovesAcknowledgedDeletedCopies() {
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","edit",Set.of(),true));
        assertFalse(SharedCachePolicy.canRemove(true,"A","","new",Set.of(),false));
        assertTrue(SharedCachePolicy.canRemove(true,"A","A","recent",Set.of(),false));
    }
    @Test public void fullFamilySnapshotNeverRemovesPrivateOrOtherFamilyData() {
        assertFalse(SharedCachePolicy.canRemove(false,"A","A","private",Set.of(),false));
        assertFalse(SharedCachePolicy.canRemove(true,"A","B","other",Set.of(),false));
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","",Set.of(),false));
    }
}
