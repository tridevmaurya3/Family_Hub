package com.tridev.familyhub.feature.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Caches only the signed-in member's own rules for boot/offline evaluation. */
public final class FamilyAutomationRuleCache {

    private static final String PREFS = "family_automation_rule_cache";
    private static final String KEY_PREFIX = "rules_";

    private FamilyAutomationRuleCache() {
    }

    public static void save(
            @NonNull Context context,
            @NonNull String uid,
            @NonNull List<FamilyAutomationRule> rules
    ) {
        JSONArray array = new JSONArray();
        for (FamilyAutomationRule rule : rules) {
            if (!uid.equals(rule.targetUid)
                    || !FamilyAutomationPolicy.validRule(rule)) {
                continue;
            }
            JSONObject value = new JSONObject();
            try {
                value.put("ruleId", rule.ruleId);
                value.put("familyId", rule.familyId);
                value.put("targetUid", rule.targetUid);
                value.put("targetName", rule.targetName);
                value.put("createdByUid", rule.createdByUid);
                value.put("title", rule.title);
                value.put("type", rule.type);
                value.put("placeName", rule.placeName);
                value.put("latitude", rule.latitude);
                value.put("longitude", rule.longitude);
                value.put("radiusMeters", rule.radiusMeters);
                value.put("daysMask", rule.daysMask);
                value.put("startMinute", rule.startMinute);
                value.put("endMinute", rule.endMinute);
                value.put("graceMinutes", rule.graceMinutes);
                value.put("enabled", rule.enabled);
                value.put("notifyTrustedViewers",
                        rule.notifyTrustedViewers);
                value.put("createdAt", rule.createdAt);
                value.put("updatedAt", rule.updatedAt);
                array.put(value);
            } catch (Exception ignored) {
                // Invalid individual rule is skipped; other rules remain safe.
            }
        }
        preferences(context).edit()
                .putString(KEY_PREFIX + uid, array.toString())
                .apply();
    }

    @NonNull
    public static List<FamilyAutomationRule> load(
            @NonNull Context context,
            @NonNull String uid
    ) {
        List<FamilyAutomationRule> rules = new ArrayList<>();
        String json = preferences(context).getString(KEY_PREFIX + uid, "[]");
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject value = array.optJSONObject(index);
                if (value == null) {
                    continue;
                }
                FamilyAutomationRule rule = new FamilyAutomationRule();
                rule.ruleId = value.optString("ruleId", "");
                rule.familyId = value.optString("familyId", "");
                rule.targetUid = value.optString("targetUid", "");
                rule.targetName = value.optString("targetName", "");
                rule.createdByUid = value.optString("createdByUid", "");
                rule.title = value.optString("title", "");
                rule.type = value.optString("type",
                        FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL);
                rule.placeName = value.optString("placeName", "");
                rule.latitude = value.optDouble("latitude", 0D);
                rule.longitude = value.optDouble("longitude", 0D);
                rule.radiusMeters = value.optDouble("radiusMeters", 150D);
                rule.daysMask = value.optInt("daysMask",
                        FamilyAutomationPolicy.ALL_DAYS_MASK);
                rule.startMinute = value.optInt("startMinute", 8 * 60);
                rule.endMinute = value.optInt("endMinute", 18 * 60);
                rule.graceMinutes = value.optInt("graceMinutes", 30);
                rule.enabled = value.optBoolean("enabled", true);
                rule.notifyTrustedViewers = value.optBoolean(
                        "notifyTrustedViewers", true);
                rule.createdAt = value.optLong("createdAt", 0L);
                rule.updatedAt = value.optLong("updatedAt", 0L);
                if (uid.equals(rule.targetUid)
                        && FamilyAutomationPolicy.validRule(rule)) {
                    rules.add(rule);
                }
            }
        } catch (Exception ignored) {
            // Corrupt cache behaves as empty and will refresh from Firebase.
        }
        return rules;
    }

    public static void clear(@NonNull Context context, @NonNull String uid) {
        preferences(context).edit().remove(KEY_PREFIX + uid).apply();
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }
}
