package com.tridev.familyhub.location;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationFreshnessPolicyTest {

    private static final long NOW = 1_000_000L;
    private static final long WINDOW = 300_000L;

    @Test
    public void recentTimestamp_isFresh() {
        assertTrue(LocationFreshnessPolicy.isFresh(
                NOW - 60_000L, NOW, WINDOW
        ));
    }

    @Test
    public void oldOrMissingTimestamp_isStale() {
        assertTrue(LocationFreshnessPolicy.isStale(
                NOW - WINDOW - 1L, NOW, WINDOW
        ));
        assertTrue(LocationFreshnessPolicy.isStale(0L, NOW, WINDOW));
    }

    @Test
    public void smallClockSkew_isAccepted() {
        assertTrue(LocationFreshnessPolicy.isFresh(
                NOW + 60_000L, NOW, WINDOW
        ));
    }

    @Test
    public void implausibleFutureTimestamp_isRejected() {
        assertFalse(LocationFreshnessPolicy.isFresh(
                NOW + 120_001L, NOW, WINDOW
        ));
    }
}
