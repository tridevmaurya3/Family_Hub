package com.tridev.familyhub.data.repository;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FamilyMutationPolicyTest {
    @Test public void offlineTitleEditPreservesPendingActivityAndFinanceLink() {
        Map<String,Object> first = Map.of("title","Old", "activity/event-1",Map.of("action","STARTED"),"linkedFinanceCloudId","finance-1");
        Map<String,Object> merged = FamilyMutationPolicy.merge(first,false,Map.of("title","Latest"));
        assertEquals("Latest",merged.get("title"));
        assertEquals("finance-1",merged.get("linkedFinanceCloudId"));
        assertEquals(first.get("activity/event-1"),merged.get("activity/event-1"));
        assertEquals("Old",first.get("title"));
    }
    @Test public void oldWriteAcknowledgementCannotClearNewEditOrDeletion() {
        assertFalse(FamilyMutationPolicy.acknowledged("edit-1","edit-2"));
        assertFalse(FamilyMutationPolicy.acknowledged("edit-1","delete-1"));
        assertTrue(FamilyMutationPolicy.acknowledged("delete-1","delete-1"));
        assertFalse(FamilyMutationPolicy.acknowledged("",""));
    }
    @Test public void explicitRecreationDoesNotRestoreFieldsFromDeletedRecord() {
        Map<String,Object> recreated = FamilyMutationPolicy.merge(Map.of("title","Deleted","linkedFinanceCloudId","old-link"),true,Map.of("title","New"));
        assertEquals(Map.of("title","New"),recreated);
    }
    @Test public void clearingLinkIsRetainedWhenLaterOfflineEditsAreMerged() {
        Map<String,Object> unlinked = FamilyMutationPolicy.merge(Map.of("title","A","linkedFinanceCloudId","old"),false,Map.of("linkedFinanceCloudId",""));
        assertEquals("",FamilyMutationPolicy.merge(unlinked,false,Map.of("title","B")).get("linkedFinanceCloudId"));
    }
}
