package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdaptiveLocationPolicyTest {

    @Test
    public void lowBatteryOverridesMovement() {
        AdaptiveLocationPolicy.Config config =
                AdaptiveLocationPolicy.resolve(
                        "TRAVELLING",
                        10,
                        false,
                        false
                );

        assertEquals(
                AdaptiveLocationPolicy.PROFILE_LOW_BATTERY,
                config.profile
        );
    }

    @Test
    public void powerSaverProtectsBattery() {
        AdaptiveLocationPolicy.Config config =
                AdaptiveLocationPolicy.resolve(
                        "WALKING",
                        60,
                        false,
                        true
                );

        assertEquals(
                AdaptiveLocationPolicy.PROFILE_POWER_SAVER,
                config.profile
        );
    }

    @Test
    public void travellingGetsFastestProfile() {
        AdaptiveLocationPolicy.Config travelling =
                AdaptiveLocationPolicy.resolve(
                        "TRAVELLING",
                        80,
                        false,
                        false
                );
        AdaptiveLocationPolicy.Config normal =
                AdaptiveLocationPolicy.resolve(
                        "UNKNOWN",
                        80,
                        false,
                        false
                );

        assertEquals(
                AdaptiveLocationPolicy.PROFILE_TRAVELLING,
                travelling.profile
        );
        assertTrue(travelling.intervalMs < normal.intervalMs);
    }

    @Test
    public void stationaryUsesBatterySavingProfile() {
        AdaptiveLocationPolicy.Config stationary =
                AdaptiveLocationPolicy.resolve(
                        "STATIONARY",
                        80,
                        false,
                        false
                );
        AdaptiveLocationPolicy.Config normal =
                AdaptiveLocationPolicy.resolve(
                        "UNKNOWN",
                        80,
                        false,
                        false
                );

        assertEquals(
                AdaptiveLocationPolicy.PROFILE_STATIONARY,
                stationary.profile
        );
        assertTrue(stationary.intervalMs > normal.intervalMs);
    }

    @Test
    public void chargingAllowsActiveTracking() {
        AdaptiveLocationPolicy.Config config =
                AdaptiveLocationPolicy.resolve(
                        "WALKING",
                        12,
                        true,
                        false
                );

        assertEquals(
                AdaptiveLocationPolicy.PROFILE_ACTIVE,
                config.profile
        );
    }
}
