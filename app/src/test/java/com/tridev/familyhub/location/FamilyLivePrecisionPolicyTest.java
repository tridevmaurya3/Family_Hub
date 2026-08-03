package com.tridev.familyhub.location;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FamilyLivePrecisionPolicyTest {

    @Test
    public void recentAuthorisedSessionIsActive() {
        long now = 1_000_000L;
        long requestedAt = now - 5_000L;
        long expiresAt = FamilyLivePrecisionPolicy.safeExpiry(
                requestedAt,
                FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS
        );
        assertTrue(FamilyLivePrecisionPolicy.isSessionActive(
                true,
                requestedAt,
                expiresAt,
                now
        ));
    }

    @Test
    public void expiredSessionIsRejected() {
        long now = 1_000_000L;
        long requestedAt = now - 60_000L;
        long expiresAt = FamilyLivePrecisionPolicy.safeExpiry(
                requestedAt,
                FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS
        );
        assertFalse(FamilyLivePrecisionPolicy.isSessionActive(
                true,
                requestedAt,
                expiresAt,
                now
        ));
    }

    @Test
    public void futureClockAbuseIsRejected() {
        long now = 1_000_000L;
        long requestedAt = now
                + FamilyLivePrecisionPolicy.SESSION_CLOCK_SKEW_MS
                + 1L;
        assertFalse(FamilyLivePrecisionPolicy.isSessionActive(
                true,
                requestedAt,
                FamilyLivePrecisionPolicy.safeExpiry(
                        requestedAt,
                        20_000L
                ),
                now
        ));
    }

    @Test
    public void criticalBatteryBlocksPrecisionUnlessCharging() {
        assertFalse(FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                FamilyLivePrecisionPolicy.CRITICAL_BATTERY_PERCENTAGE,
                false
        ));
        assertTrue(FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                FamilyLivePrecisionPolicy.CRITICAL_BATTERY_PERCENTAGE,
                true
        ));
    }

    @Test
    public void ordinaryBatteryAllowsTemporaryPrecision() {
        assertTrue(FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                19,
                false
        ));
    }
}
