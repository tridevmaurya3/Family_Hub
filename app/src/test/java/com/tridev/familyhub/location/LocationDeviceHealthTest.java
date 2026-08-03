package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationDeviceHealthTest {

    @Test
    public void permissionFailureHasHighestPriority() {
        assertEquals(
                LocationDeviceHealth.PERMISSION_OFF,
                LocationDeviceHealth.resolve(false, false, false)
        );
    }

    @Test
    public void gpsFailureComesBeforeInternetFailure() {
        assertEquals(
                LocationDeviceHealth.GPS_OFF,
                LocationDeviceHealth.resolve(true, false, false)
        );
    }

    @Test
    public void internetFailureKeepsLocationCollectionAvailable() {
        String state = LocationDeviceHealth.resolve(true, true, false);

        assertEquals(LocationDeviceHealth.INTERNET_OFF, state);
        assertFalse(LocationDeviceHealth.blocksLocationUpdates(state));
        assertTrue(LocationDeviceHealth.shouldQueueOffline(state));
    }

    @Test
    public void allRequirementsProduceReadyState() {
        assertEquals(
                LocationDeviceHealth.READY,
                LocationDeviceHealth.resolve(true, true, true)
        );
    }
}
