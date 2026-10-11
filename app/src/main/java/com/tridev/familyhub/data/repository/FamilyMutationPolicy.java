package com.tridev.familyhub.data.repository;

import java.util.HashMap;
import java.util.Map;

/** Coalesces partial edits while keeping acknowledgements tied to their saved revision. */
final class FamilyMutationPolicy {
    private FamilyMutationPolicy() { }
    static Map<String,Object> merge(Map<String,Object> pending, boolean pendingDelete, Map<String,Object> edit) {
        Map<String,Object> merged = pendingDelete ? new HashMap<>() : new HashMap<>(pending);
        merged.putAll(edit);
        return merged;
    }
    static boolean acknowledged(String sentRevision, String currentRevision) {
        return sentRevision != null && !sentRevision.isEmpty() && sentRevision.equals(currentRevision);
    }
}
