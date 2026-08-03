package com.tridev.familyhub.feature.safety;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FamilySafetyOverviewPolicyTest {

    @Test
    public void activeSos_hasHighestPriority() {
        assertEquals(
                FamilySafetyOverviewPolicy.STATE_EMERGENCY,
                FamilySafetyOverviewPolicy.resolve(1, 4, 7)
        );
    }

    @Test
    public void memberAttention_requiresAttentionState() {
        assertEquals(
                FamilySafetyOverviewPolicy.STATE_ATTENTION,
                FamilySafetyOverviewPolicy.resolve(0, 2, 0)
        );
    }

    @Test
    public void unreadAlerts_requireAttentionState() {
        assertEquals(
                FamilySafetyOverviewPolicy.STATE_ATTENTION,
                FamilySafetyOverviewPolicy.resolve(0, 0, 3)
        );
    }

    @Test
    public void noIssues_returnsAllClear() {
        assertEquals(
                FamilySafetyOverviewPolicy.STATE_ALL_CLEAR,
                FamilySafetyOverviewPolicy.resolve(0, 0, 0)
        );
    }

    @Test
    public void negativeCounts_areTreatedAsZero() {
        assertEquals(
                FamilySafetyOverviewPolicy.STATE_ALL_CLEAR,
                FamilySafetyOverviewPolicy.resolve(-1, -2, -3)
        );
    }
}
