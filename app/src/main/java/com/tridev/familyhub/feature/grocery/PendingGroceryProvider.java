package com.tridev.familyhub.feature.grocery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;

public final class PendingGroceryProvider extends ContentProvider {
    private static final String MONEY_MANAGER = "com.example.moneymanagerpro";
    // SHA-256 digest of the MoneyManagerPro release signing certificate.
    private static final String MONEY_MANAGER_CERT =
            "4e1d6e232a2a0d32274e312a1f15f560b728d7cabab6db4050a66e995c976eac";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null || !trustedCaller(context)) throw new SecurityException("Untrusted caller");
        if (!"/items".equals(uri.getPath())) throw new IllegalArgumentException("Unknown grocery path");
        long identity = Binder.clearCallingIdentity();
        try {
            MatrixCursor result = new MatrixCursor(new String[]{"name", "quantity"});
            for (GroceryItem item : FamilyHubDatabase.getInstance(context).groceryItemDao().getAll()) {
                if (!item.isPurchased && !item.historyOnly && item.financeEntryId <= 0
                        && item.name != null && !item.name.trim().isEmpty()) {
                    result.addRow(new Object[]{item.name, item.quantity});
                }
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean trustedCaller(Context context) {
        if (!MONEY_MANAGER.equals(getCallingPackage())) return false;
        byte[] digest = new byte[MONEY_MANAGER_CERT.length() / 2];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) Integer.parseInt(MONEY_MANAGER_CERT.substring(i * 2, i * 2 + 2), 16);
        }
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            return pm.hasSigningCertificate(MONEY_MANAGER, digest, PackageManager.CERT_INPUT_SHA256);
        }
        try {
            @SuppressWarnings("deprecation")
            android.content.pm.Signature[] signatures =
                    pm.getPackageInfo(MONEY_MANAGER, PackageManager.GET_SIGNATURES).signatures;
            if (signatures == null || signatures.length != 1) return false;
            return java.security.MessageDigest.isEqual(digest,
                    java.security.MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.familyhub.grocery"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
