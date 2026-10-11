package com.tridev.familyhub.core.security;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountScopePolicyTest {
    @Test public void originalOwnerRetainsExistingDatabaseAndOtherAccountsCannotOpenIt() {
        assertEquals("family_hub.db", AccountScopePolicy.databaseName("owner", "owner"));
        assertNotEquals("family_hub.db", AccountScopePolicy.databaseName("second", "owner"));
        assertNotEquals(AccountScopePolicy.databaseName("second", "owner"),
                AccountScopePolicy.databaseName("third", "owner"));
        assertEquals(AccountScopePolicy.databaseName("second", "owner"),
                AccountScopePolicy.databaseName("second", "owner"));
    }
    @Test public void signedOutStateCannotReadLegacyAccountOrDeliverAlerts() {
        assertNotEquals("family_hub.db", AccountScopePolicy.databaseName("", "owner"));
        assertFalse(AccountScopePolicy.canDeliver("", "owner", "owner"));
    }
    @Test public void oldAndNewNotificationsStayWithTheirAccount() {
        assertTrue(AccountScopePolicy.canDeliver("owner", null, "owner"));
        assertFalse(AccountScopePolicy.canDeliver("second", null, "owner"));
        assertTrue(AccountScopePolicy.canDeliver("second", "second", "owner"));
        assertFalse(AccountScopePolicy.canDeliver("owner", "second", "owner"));
    }
    @Test public void unownedLegacyDataIsNeverAssignedToAnArbitraryLogin() {
        assertNotEquals("family_hub.db", AccountScopePolicy.databaseName("new-account", ""));
        assertFalse(AccountScopePolicy.canDeliver("new-account", null, ""));
    }
    @Test public void filenamesDoNotExposeUidOrAllowPathTraversal() {
        String name = AccountScopePolicy.databaseName("../a/b:private", "owner");
        assertTrue(name.matches("family_hub_[0-9a-f]{64}\\.db"));
        assertNotEquals(AccountScopePolicy.key("abc"), AccountScopePolicy.key("ABC"));
    }
    @Test public void accountChangeClearsPersonalAlertsAndPreservesLiveSafetyChannels() {
        assertTrue(AccountScopePolicy.accountNotificationChannel("family_reminder_alarm_screen_v1"));
        assertTrue(AccountScopePolicy.accountNotificationChannel("family_hub_document_expiry"));
        assertFalse(AccountScopePolicy.accountNotificationChannel("family_sos_alerts"));
        assertFalse(AccountScopePolicy.accountNotificationChannel("family_live_location"));
        assertFalse(AccountScopePolicy.accountNotificationChannel(null));
    }
}
