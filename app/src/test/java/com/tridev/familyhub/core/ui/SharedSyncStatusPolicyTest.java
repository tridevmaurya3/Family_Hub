package com.tridev.familyhub.core.ui;

import com.tridev.familyhub.R;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SharedSyncStatusPolicyTest {
    @Test public void offlineWithPendingChangesNeverClaimsToBeLive() {
        assertEquals(R.string.shared_sync_offline_pending,SharedSyncStatusPolicy.message(false,2,1));
    }
    @Test public void connectedButRejectedUploadShowsRetryRatherThanSyncSuccess() {
        assertEquals(R.string.shared_sync_failed,SharedSyncStatusPolicy.message(true,3,1));
    }
    @Test public void serverAcknowledgementMovesPendingBackToConnectionStatus() {
        assertEquals(R.string.shared_sync_pending,SharedSyncStatusPolicy.message(true,1,0));
        assertEquals(R.string.shared_sync_connected,SharedSyncStatusPolicy.message(true,0,0));
    }
}
