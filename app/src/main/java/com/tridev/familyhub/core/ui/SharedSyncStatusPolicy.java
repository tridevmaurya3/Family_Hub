package com.tridev.familyhub.core.ui;

import com.tridev.familyhub.R;

final class SharedSyncStatusPolicy {
    private SharedSyncStatusPolicy() { }
    static int message(boolean connected, int pending, int failed) {
        if (!connected) return pending > 0 ? R.string.shared_sync_offline_pending : R.string.shared_sync_offline;
        if (failed > 0) return R.string.shared_sync_failed;
        return pending > 0 ? R.string.shared_sync_pending : R.string.shared_sync_connected;
    }
}
