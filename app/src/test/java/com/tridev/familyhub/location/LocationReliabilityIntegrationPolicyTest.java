package com.tridev.familyhub.location;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Cross-policy checks for the final Phase 2 reliability contract.
 */
public class LocationReliabilityIntegrationPolicyTest {

    @Test
    public void rapidStopStartCannotReplayPreviousSessionPoint() {
        long previousSessionPoint = 10_000L;
        long newSessionStartedAt = 20_000L;
        long remoteTimestamp = 5_000L;

        assertTrue(LocationSyncPolicy.shouldUpload(
                remoteTimestamp,
                previousSessionPoint
        ));
        assertFalse(LocationSyncPolicy.belongsToCurrentSharingSession(
                previousSessionPoint,
                newSessionStartedAt
        ));
    }

    @Test
    public void currentSessionPointCanSyncWhenRemoteIsOlder() {
        long currentSessionStartedAt = 20_000L;
        long queuedPoint = 25_000L;
        long remoteTimestamp = 22_000L;

        assertTrue(LocationSyncPolicy.belongsToCurrentSharingSession(
                queuedPoint,
                currentSessionStartedAt
        ));
        assertTrue(LocationSyncPolicy.shouldUpload(
                remoteTimestamp,
                queuedPoint
        ));
    }

    @Test
    public void stalledServiceTriggersOnlyOneImmediateRecoveryWindow() {
        long now = 1_000_000L;
        long firstAttempt = now - 1_000L;

        assertTrue(LocationHeartbeatPolicy.shouldRecover(true, false));
        assertFalse(LocationHeartbeatPolicy.canAttemptRecovery(
                firstAttempt,
                now
        ));
        assertTrue(LocationHeartbeatPolicy.canAttemptRecovery(
                now - LocationHeartbeatPolicy.RECOVERY_ATTEMPT_COOLDOWN_MS,
                now
        ));
    }

    @Test
    public void recoveryPendingStateIsTruthfullyReportedAsStale() {
        assertTrue(LocationHeartbeatPolicy.isCloudHeartbeatStale(
                LocationHeartbeatPolicy.STATE_RECOVERY_PENDING,
                1_000L,
                1_000L
        ));
    }
}
