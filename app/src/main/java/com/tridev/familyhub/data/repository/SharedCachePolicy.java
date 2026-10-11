package com.tridev.familyhub.data.repository;

import java.util.Set;

/** Only acknowledged family copies can be removed by a complete online snapshot. */
final class SharedCachePolicy {
    private SharedCachePolicy() { }
    static boolean canRemove(boolean shared, String family, String cachedFamily, String id,
                             Set<String> remoteIds, boolean pending) {
        return shared && !family.isEmpty() && family.equals(cachedFamily) && !id.isEmpty()
                && !remoteIds.contains(id) && !pending;
    }
}
