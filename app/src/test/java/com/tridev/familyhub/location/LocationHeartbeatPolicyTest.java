package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationHeartbeatPolicyTest {

    private static final long NOW = 1_000_000L;

    @Test
    public void sharingWithoutServiceNeedsRecovery() {
        assertTrue(LocationHeartbeatPolicy.shouldRecover(true, false));
        assertFalse(LocationHeartbeatPolicy.shouldRecover(true, true));
        assertFalse(LocationHeartbeatPolicy.shouldRecover(false, false));
    }

    @Test
    public void healthyServiceUsesRegularDelay() {
        assertEquals(
                LocationHeartbeatPolicy.REGULAR_CHECK_DELAY_MS,
                LocationHeartbeatPolicy.nextCheckDelay(true)
        );
        assertEquals(
                LocationHeartbeatPolicy.RECOVERY_RECHECK_DELAY_MS,
                LocationHeartbeatPolicy.nextCheckDelay(false)
        );
    }

    @Test
    public void duplicateRecoveryIsBlockedDuringCooldown() {
        long recentAttempt = NOW
                - LocationHeartbeatPolicy.RECOVERY_ATTEMPT_COOLDOWN_MS
                + 1L;
        assertFalse(LocationHeartbeatPolicy.canAttemptRecovery(
                recentAttempt,
                NOW
        ));
    }

    @Test
    public void recoveryIsAllowedAfterCooldown() {
        long oldAttempt = NOW
                - LocationHeartbeatPolicy.RECOVERY_ATTEMPT_COOLDOWN_MS;
        assertTrue(LocationHeartbeatPolicy.canAttemptRecovery(
                oldAttempt,
                NOW
        ));
        assertTrue(LocationHeartbeatPolicy.canAttemptRecovery(0L, NOW));
    }

    @Test
    public void recoveryPendingIsImmediatelyStale() {
        assertTrue(LocationHeartbeatPolicy.isCloudHeartbeatStale(
                LocationHeartbeatPolicy.STATE_RECOVERY_PENDING,
                NOW,
                NOW
        ));
    }

    @Test
    public void recentRunningHeartbeatIsHealthy() {
        assertFalse(LocationHeartbeatPolicy.isCloudHeartbeatStale(
                LocationHeartbeatPolicy.STATE_RUNNING,
                NOW - 60_000L,
                NOW
        ));
    }

    @Test
    public void oldRunningHeartbeatBecomesStale() {
        assertTrue(LocationHeartbeatPolicy.isCloudHeartbeatStale(
                LocationHeartbeatPolicy.STATE_RUNNING,
                NOW
                        - LocationHeartbeatPolicy
                        .CLOUD_HEARTBEAT_STALE_AFTER_MS
                        - 1L,
                NOW
        ));
    }

    @Test
    public void legacyMemberWithoutHeartbeatIsNotFalsePositive() {
        assertFalse(LocationHeartbeatPolicy.isCloudHeartbeatStale(
                "",
                0L,
                NOW
        ));
    }
}
