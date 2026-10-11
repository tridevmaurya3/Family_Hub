package com.tridev.familyhub.data.repository;

/** Server revision ordering is independent of a family member's phone clock. */
final class FamilySyncMergePolicy {
    private FamilySyncMergePolicy() { }
    static boolean shouldApply(boolean pending, boolean missing, long serverVersion, long seenVersion, long remoteTime, long localTime) {
        if (pending) return false;
        if (missing) return true;
        return serverVersion > 0L ? serverVersion > seenVersion : remoteTime >= localTime;
    }
}
