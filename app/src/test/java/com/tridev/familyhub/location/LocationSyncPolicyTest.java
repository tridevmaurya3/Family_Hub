package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationSyncPolicyTest {

    @Test
    public void newerQueuedPointCanUpload() {
        assertTrue(LocationSyncPolicy.shouldUpload(1_000L, 2_000L));
    }

    @Test
    public void staleOrDuplicatePointCannotUpload() {
        assertFalse(LocationSyncPolicy.shouldUpload(2_000L, 2_000L));
        assertFalse(LocationSyncPolicy.shouldUpload(3_000L, 2_000L));
    }

    @Test
    public void updateIdIsStableForSamePoint() {
        String first = LocationSyncPolicy.createUpdateId(
                "family",
                "member",
                1_000L,
                25.3176D,
                82.9739D
        );
        String second = LocationSyncPolicy.createUpdateId(
                "family",
                "member",
                1_000L,
                25.3176D,
                82.9739D
        );

        assertEquals(first, second);
    }

    @Test
    public void updateIdChangesForDifferentPoint() {
        String first = LocationSyncPolicy.createUpdateId(
                "family",
                "member",
                1_000L,
                25.3176D,
                82.9739D
        );
        String second = LocationSyncPolicy.createUpdateId(
                "family",
                "member",
                2_000L,
                25.3177D,
                82.9740D
        );

        assertNotEquals(first, second);
    }

    @Test
    public void retryDelayUsesCappedExponentialBackoff() {
        assertEquals(30_000L, LocationSyncPolicy.retryDelay(0));
        assertEquals(60_000L, LocationSyncPolicy.retryDelay(1));
        assertEquals(15L * 60L * 1_000L,
                LocationSyncPolicy.retryDelay(100));
    }
}
