package com.tridev.familyhub.data.repository;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class SharedCachePolicyTest {
    @Test public void reconnectRemovesAnAcknowledgedCopyDeletedByAnotherPhone() {
        assertTrue(SharedCachePolicy.canRemove(true,"A","A","deleted",Set.of("remaining"),false,60_000));
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","remaining",Set.of("remaining"),false,60_000));
    }
    @Test public void reconnectKeepsOfflineEditAndNotYetAcknowledgedCreation() {
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","edit",Set.of(),true,60_000));
        assertFalse(SharedCachePolicy.canRemove(true,"A","","new",Set.of(),false,60_000));
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","recent",Set.of(),false,1_000));
    }
    @Test public void fullFamilySnapshotNeverRemovesPrivateOrOtherFamilyData() {
        assertFalse(SharedCachePolicy.canRemove(false,"A","A","private",Set.of(),false,60_000));
        assertFalse(SharedCachePolicy.canRemove(true,"A","B","other",Set.of(),false,60_000));
        assertFalse(SharedCachePolicy.canRemove(true,"A","A","",Set.of(),false,60_000));
    }
}
