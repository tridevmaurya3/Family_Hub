package com.tridev.familyhub.core.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** Non-destructive ownership for the legacy database and queued notifications. */
public final class AccountDataScope {
    public static final String EXTRA_UID = "familyhub_account_uid";
    private AccountDataScope() { }
    public static String uid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "" : user.getUid();
    }
    public static synchronized String legacyOwner(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("familyhub_database_ownership", Context.MODE_PRIVATE);
        String owner = prefs.getString("legacyOwner", "");
        String current = uid();
        if (!prefs.getBoolean("initialized", false)) {
            // Claim only the account present on the first upgraded process launch.
            // If signed out, preserve the unowned legacy file without assigning it to a later login.
            if (!prefs.edit().putBoolean("initialized", true).putString("legacyOwner", current).commit())
                throw new IllegalStateException("Account ownership could not be saved");
            owner = current;
        }
        return owner;
    }
    public static String databaseName(Context context) {
        return AccountScopePolicy.databaseName(uid(), legacyOwner(context));
    }
    public static String preferencesName(Context context, String name) {
        return preferencesName(context,name,uid());
    }
    public static String preferencesName(Context context, String name, String current) {
        return !current.isEmpty() && current.equals(legacyOwner(context))
                ? name : name + "_" + AccountScopePolicy.key(current);
    }
    public static String localName(Context context, String name) {
        return preferencesName(context, name);
    }
    public static Intent isolate(Context context, Intent intent) {
        if (!uid().equals(legacyOwner(context))) {
            android.net.Uri data = intent.getData();
            if (data == null) data = android.net.Uri.parse("familyhub://account-action");
            intent.setData(data.buildUpon().appendQueryParameter("account", AccountScopePolicy.key(uid())).build());
        }
        return intent;
    }
    public static Intent stamp(Context context, Intent intent) {
        return intent.putExtra(EXTRA_UID, uid());
    }
    public static boolean canDeliver(Context context, String expectedUid) {
        return AccountScopePolicy.canDeliver(uid(), expectedUid, legacyOwner(context));
    }
}
