package com.tridev.familyhub.location;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Pure timing and state rules for Family Live service diagnostics.
 *
 * WorkManager checks are best-effort and Android may defer them, so the cloud
 * heartbeat is considered stale only after a generous safety window.
 */
public final class LocationHeartbeatPolicy {

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_RECOVERY_PENDING = "RECOVERY_PENDING";
    public static final String STATE_STALLED = "STALLED";
    public static final String STATE_STOPPED = "STOPPED";
    public static final String STATE_UNKNOWN = "UNKNOWN";

    public static final long REGULAR_CHECK_DELAY_MS =
            5L * 60L * 1000L;
    public static final long RECOVERY_RECHECK_DELAY_MS =
            2L * 60L * 1000L;
    public static final long RECOVERY_ATTEMPT_COOLDOWN_MS =
            90L * 1000L;
    public static final long CLOUD_HEARTBEAT_STALE_AFTER_MS =
            12L * 60L * 1000L;

    private LocationHeartbeatPolicy() {
    }

    public static boolean shouldRecover(
            boolean sharingEnabled,
            boolean serviceRunning
    ) {
        return sharingEnabled && !serviceRunning;
    }

    public static boolean canAttemptRecovery(
            long lastRecoveryAt,
            long now
    ) {
        if (lastRecoveryAt <= 0L || now < lastRecoveryAt) {
            return true;
        }
        return now - lastRecoveryAt >= RECOVERY_ATTEMPT_COOLDOWN_MS;
    }

    public static long nextCheckDelay(boolean serviceRunning) {
        return serviceRunning
                ? REGULAR_CHECK_DELAY_MS
                : RECOVERY_RECHECK_DELAY_MS;
    }

    public static boolean isCloudHeartbeatStale(
            String serviceState,
            long serviceHeartbeatAt,
            long now
    ) {
        String normalized = normalizeState(serviceState);
        if (STATE_RECOVERY_PENDING.equals(normalized)
                || STATE_STALLED.equals(normalized)
                || STATE_STOPPED.equals(normalized)) {
            return true;
        }

        if (serviceHeartbeatAt <= 0L || now <= serviceHeartbeatAt) {
            return false;
        }

        return now - serviceHeartbeatAt
                > CLOUD_HEARTBEAT_STALE_AFTER_MS;
    }

    @NonNull
    public static String normalizeState(String value) {
        if (value == null) {
            return STATE_UNKNOWN;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case STATE_RUNNING:
            case STATE_RECOVERY_PENDING:
            case STATE_STALLED:
            case STATE_STOPPED:
                return normalized;
            default:
                return STATE_UNKNOWN;
        }
    }
}
