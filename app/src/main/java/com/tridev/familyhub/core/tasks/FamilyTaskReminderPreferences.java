package com.tridev.familyhub.core.tasks;

import android.content.Context;
import android.content.SharedPreferences;
import com.tridev.familyhub.data.local.entity.FamilyTask;

/** Device-only presentation settings; canonical task and realtime schema stay unchanged. */
public final class FamilyTaskReminderPreferences {
    private FamilyTaskReminderPreferences() { }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("family_task_reminder_options", Context.MODE_PRIVATE);
    }
    private static String key(FamilyTask task) {
        return "RECURRING_TASK".equals(task.sourceType) && !task.sourceRecordId.isEmpty()
                ? task.sourceRecordId : (task.cloudId.isEmpty() ? "local:" + task.id : task.cloudId);
    }
    public static boolean important(Context c, FamilyTask task) { return prefs(c).getBoolean(key(task) + ":important", false); }
    public static boolean followUp(Context c, FamilyTask task) { return prefs(c).getBoolean(key(task) + ":follow", false); }
    public static void save(Context c, FamilyTask task, boolean important, boolean follow) {
        prefs(c).edit().putBoolean(key(task) + ":important", important).putBoolean(key(task) + ":follow", follow).apply();
    }
    public static long snooze(Context c, long id, long due) {
        SharedPreferences p = prefs(c);
        return p.getLong(id + ":snoozeDue", -1) == due ? p.getLong(id + ":snooze", 0) : 0;
    }
    public static void snooze(Context c, long id, long due, long time) {
        prefs(c).edit().putLong(id + ":snoozeDue", due).putLong(id + ":snooze", time).apply();
    }
    public static void clearSnooze(Context c, long id) { prefs(c).edit().remove(id + ":snooze").remove(id + ":snoozeDue").apply(); }
    public static boolean updateSchedule(Context c, long id, String signature) {
        SharedPreferences p = prefs(c);
        if (signature.equals(p.getString(id + ":schedule", ""))) return false;
        p.edit().putString(id + ":schedule", signature).apply();
        return true;
    }
    public static boolean delivered(Context c, long id, long due, int stage, long occurrence) {
        SharedPreferences p = prefs(c);
        String key = id + ":delivered:" + stage;
        return p.getLong(key + ":due", -1) == due && p.getLong(key, -1) == occurrence;
    }
    public static void markDelivered(Context c, long id, long due, int stage, long occurrence) {
        String key = id + ":delivered:" + stage;
        prefs(c).edit().putLong(key + ":due", due).putLong(key, occurrence).apply();
    }
    public static void clearDelivery(Context c, long id) {
        SharedPreferences.Editor editor = prefs(c).edit().remove(id + ":schedule");
        for (int stage = 0; stage <= 3; stage++) {
            String key = id + ":delivered:" + stage;
            editor.remove(key).remove(key + ":due");
        }
        editor.apply();
    }

}

