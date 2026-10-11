package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.database.DataSnapshot;
import com.tridev.familyhub.core.FamilyHubApplication;
import com.tridev.familyhub.core.security.AccountScopePolicy;

final class FamilySyncVersions {
    private FamilySyncVersions() { }
    private static SharedPreferences store(String uid) {
        Context context = FamilyHubApplication.getAppContextOrNull();
        return context == null ? null : context.getSharedPreferences("family_server_versions_" + AccountScopePolicy.key(uid),Context.MODE_PRIVATE);
    }
    static boolean shouldApply(String uid, String module, String id, DataSnapshot snapshot, boolean missing, long localTime) {
        SharedPreferences prefs = store(uid);
        Number server = snapshot.child("serverUpdatedAt").getValue(Number.class);
        Number updated = snapshot.child("updatedAt").getValue(Number.class);
        return FamilySyncMergePolicy.shouldApply(FamilySyncOutbox.pending(uid,module,id),missing,
                server == null ? 0L : server.longValue(),prefs == null ? 0L : prefs.getLong(module + "/" + id,0L),
                updated == null ? 0L : updated.longValue(),localTime);
    }
    static void applied(String uid, String module, String id, DataSnapshot snapshot) {
        Number server = snapshot.child("serverUpdatedAt").getValue(Number.class);
        SharedPreferences prefs = store(uid);
        if (prefs != null && server != null && server.longValue() > 0L) prefs.edit().putLong(module + "/" + id,server.longValue()).apply();
    }
}
