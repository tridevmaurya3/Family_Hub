package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FamilyDeviceSafetyAlertPolicyTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    public void noUpdateRequiresTwentyMinutesAndActiveSharing() {
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagNoUpdate(
                false,
                false,
                NOW - FamilyDeviceSafetyAlertPolicy.NO_UPDATE_THRESHOLD_MS,
                NOW
        ));
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagNoUpdate(
                true,
                false,
                NOW - FamilyDeviceSafetyAlertPolicy.NO_UPDATE_THRESHOLD_MS + 1L,
                NOW
        ));
        assertTrue(FamilyDeviceSafetyAlertPolicy.shouldFlagNoUpdate(
                true,
                false,
                NOW - FamilyDeviceSafetyAlertPolicy.NO_UPDATE_THRESHOLD_MS,
                NOW
        ));
    }

    @Test
    public void confirmedOfflineSuppressesNoUpdateCondition() {
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagNoUpdate(
                true,
                true,
                NOW - FamilyDeviceSafetyAlertPolicy.NO_UPDATE_THRESHOLD_MS,
                NOW
        ));
    }

    @Test
    public void offlineRequiresFiveMinutesOfEvidence() {
        long almostConfirmed = NOW
                - FamilyDeviceSafetyAlertPolicy.OFFLINE_CONFIRMATION_MS
                + 1L;
        long confirmed = NOW
                - FamilyDeviceSafetyAlertPolicy.OFFLINE_CONFIRMATION_MS;

        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagOffline(
                true,
                false,
                almostConfirmed,
                0L,
                0L,
                NOW
        ));
        assertTrue(FamilyDeviceSafetyAlertPolicy.shouldFlagOffline(
                true,
                false,
                confirmed,
                0L,
                0L,
                NOW
        ));
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagOffline(
                true,
                true,
                confirmed,
                0L,
                0L,
                NOW
        ));
    }

    @Test
    public void lowBatteryRequiresFreshDataAndNotCharging() {
        long freshUpdate = NOW - 10_000L;
        long oldUpdate = NOW
                - FamilyDeviceSafetyAlertPolicy.LOW_BATTERY_DATA_MAX_AGE_MS
                - 1L;

        assertTrue(FamilyDeviceSafetyAlertPolicy.shouldFlagLowBattery(
                true,
                15,
                false,
                freshUpdate,
                NOW
        ));
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagLowBattery(
                true,
                15,
                true,
                freshUpdate,
                NOW
        ));
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagLowBattery(
                true,
                16,
                false,
                freshUpdate,
                NOW
        ));
        assertFalse(FamilyDeviceSafetyAlertPolicy.shouldFlagLowBattery(
                true,
                15,
                false,
                oldUpdate,
                NOW
        ));
    }

    @Test
    public void memberPlaceIdRoundTripsUid() {
        String uid = "member_123-ABC";
        String placeId = FamilyDeviceSafetyAlertPolicy.memberPlaceId(uid);

        assertTrue(FamilyDeviceSafetyAlertPolicy.isMemberPlaceId(placeId));
        assertEquals(
                uid,
                FamilyDeviceSafetyAlertPolicy.memberUidFromPlaceId(placeId)
        );
    }

    @Test
    public void deduplicationBucketUsesAlertSpecificCooldown() {
        long occurredAt = 24L * 60L * 60L * 1000L;

        assertEquals(
                occurredAt
                        / FamilyDeviceSafetyAlertPolicy
                        .NO_UPDATE_REMINDER_COOLDOWN_MS,
                FamilyDeviceSafetyAlertPolicy.deduplicationBucket(
                        FamilyDeviceSafetyAlertPolicy.ALERT_NO_UPDATE,
                        occurredAt
                )
        );
        assertEquals(
                occurredAt
                        / FamilyDeviceSafetyAlertPolicy
                        .LOW_BATTERY_REMINDER_COOLDOWN_MS,
                FamilyDeviceSafetyAlertPolicy.deduplicationBucket(
                        FamilyDeviceSafetyAlertPolicy.ALERT_LOW_BATTERY,
                        occurredAt
                )
        );
    }
}
