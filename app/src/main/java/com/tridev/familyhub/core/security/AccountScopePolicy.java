package com.tridev.familyhub.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable, path-safe account identities; existing data stays with its first owner. */
public final class AccountScopePolicy {
    private AccountScopePolicy() { }
    public static String key(String uid) {
        if (uid == null || uid.isEmpty()) return "signed_out";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static String databaseName(String uid, String legacyOwner) {
        return uid != null && !uid.isEmpty() && uid.equals(legacyOwner)
                ? "family_hub.db" : "family_hub_" + key(uid) + ".db";
    }
    public static boolean canDeliver(String uid, String expectedUid, String legacyOwner) {
        if (uid == null || uid.isEmpty()) return false;
        return expectedUid == null ? uid.equals(legacyOwner) : uid.equals(expectedUid);
    }

    public static boolean accountNotificationChannel(String channel) {
        if (channel == null) return false;
        switch (channel) {
            case "family_hub_planner": case "family_hub_reminders": case "family_hub_urgent_reminders":
            case "family_reminder_alarm_screen_v1": case "family_hub_tasks": case "family_hub_tasks_important":
            case "family_task_alarm_screen_v2": case "note_reminders": case "family_hub_document_expiry":
            case "family_hub_health_reminders": case "family_hub_vehicle_reminders": case "family_grocery_updates":
                return true;
            default: return false;
        }
    }
}
