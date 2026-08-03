package com.tridev.familyhub.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationPointPolicyTest {

    private static final long NOW_WALL = 1_000_000L;
    private static final long NOW_ELAPSED = 10_000_000_000L;

    @Test
    public void rejectsInvalidCoordinates() {
        assertEquals(
                LocationPointPolicy.REJECT_INVALID_COORDINATES,
                evaluate(point(0D, 0D, 10F, 0L), null, false, false)
        );
    }

    @Test
    public void rejectsMockLocation() {
        assertEquals(
                LocationPointPolicy.REJECT_MOCK,
                evaluate(point(25.3D, 83.0D, 10F, 0L), null, false, true)
        );
    }

    @Test
    public void rejectsStaleLastKnownPoint() {
        LocationPointPolicy.Point stale = new LocationPointPolicy.Point(
                25.3D,
                83.0D,
                20F,
                NOW_WALL - LocationPointPolicy.MAX_LAST_KNOWN_AGE_MS - 1L,
                0L
        );

        assertEquals(
                LocationPointPolicy.REJECT_STALE,
                evaluate(stale, null, true, false)
        );
    }

    @Test
    public void rejectsPoorAccuracy() {
        assertEquals(
                LocationPointPolicy.REJECT_POOR_ACCURACY,
                evaluate(point(25.3D, 83.0D, 600F, 0L), null, false, false)
        );
    }

    @Test
    public void rejectsOutOfOrderPoint() {
        LocationPointPolicy.Point previous = point(
                25.3D,
                83.0D,
                10F,
                -5_000_000_000L
        );
        LocationPointPolicy.Point current = point(
                25.3001D,
                83.0001D,
                10F,
                -6_000_000_000L
        );

        assertEquals(
                LocationPointPolicy.REJECT_OUT_OF_ORDER,
                evaluate(current, previous, false, false)
        );
    }

    @Test
    public void suspiciousJumpRequiresConfirmation() {
        LocationPointPolicy.Point previous = point(
                25.3D,
                83.0D,
                10F,
                -10_000_000_000L
        );
        LocationPointPolicy.Point jump = point(
                26.3D,
                84.0D,
                15F,
                -9_000_000_000L
        );

        assertEquals(
                LocationPointPolicy.REQUIRE_JUMP_CONFIRMATION,
                evaluate(jump, previous, false, false)
        );
    }

    @Test
    public void nearbySecondPointConfirmsSuspiciousJump() {
        LocationPointPolicy.Point candidate = point(
                26.3D,
                84.0D,
                20F,
                -2_000_000_000L
        );
        LocationPointPolicy.Point confirmation = point(
                26.3008D,
                84.0008D,
                20F,
                -1_000_000_000L
        );
        LocationPointPolicy.Point farAway = point(
                27.3D,
                85.0D,
                20F,
                -1_000_000_000L
        );

        assertTrue(LocationPointPolicy.confirmsSuspiciousJump(
                candidate,
                confirmation
        ));
        assertFalse(LocationPointPolicy.confirmsSuspiciousJump(
                candidate,
                farAway
        ));
    }

    @Test
    public void reasonableFreshMovementIsAccepted() {
        LocationPointPolicy.Point previous = point(
                25.3D,
                83.0D,
                15F,
                -60_000_000_000L
        );
        LocationPointPolicy.Point current = point(
                25.305D,
                83.005D,
                15F,
                0L
        );

        assertEquals(
                LocationPointPolicy.ACCEPT,
                evaluate(current, previous, false, false)
        );
    }

    private String evaluate(
            LocationPointPolicy.Point current,
            LocationPointPolicy.Point previous,
            boolean lastKnown,
            boolean mock
    ) {
        return LocationPointPolicy.evaluate(
                current,
                previous,
                NOW_WALL,
                NOW_ELAPSED,
                lastKnown,
                mock
        );
    }

    private LocationPointPolicy.Point point(
            double latitude,
            double longitude,
            float accuracy,
            long elapsedOffsetNanos
    ) {
        long elapsed = NOW_ELAPSED + elapsedOffsetNanos;
        long wallOffsetMs = elapsedOffsetNanos / 1_000_000L;
        return new LocationPointPolicy.Point(
                latitude,
                longitude,
                accuracy,
                NOW_WALL + wallOffsetMs,
                elapsed
        );
    }
}
