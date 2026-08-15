package com.tridev.familyhub.feature.finance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Exact-package + trust-on-first-use certificate pinning for LoanManagerPro.
 *
 * Family Hub and LoanManagerPro may be independently signed. Requiring both
 * apps to share one signing key is therefore unnecessarily fragile. Binder UID
 * must resolve to the exact LoanManager package and its installed signing
 * certificate is pinned on the first trusted connection. Later calls must match
 * the same certificate.
 */
public final class LoanManagerProjectionTrust {

    public static final String LOAN_MANAGER_PACKAGE = "com.tridev.loanmanagerpro";

    private static final String PREFS = "loan_manager_projection_trust_v1";
    private static final String KEY_CERT_SHA256 = "loan_manager_cert_sha256";

    private LoanManagerProjectionTrust() { }

    public static boolean verifyCaller(@NonNull Context context, int callingUid) {
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        boolean exactPackage = false;
        if (packages != null) {
            for (String packageName : packages) {
                if (LOAN_MANAGER_PACKAGE.equals(packageName)) {
                    exactPackage = true;
                    break;
                }
            }
        }
        if (!exactPackage) return false;

        String installed = installedCertificateSha256(context);
        if (installed == null || installed.isEmpty()) return false;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pinned = clean(prefs.getString(KEY_CERT_SHA256, ""));
        if (pinned.isEmpty()) {
            prefs.edit().putString(KEY_CERT_SHA256, installed).apply();
            return true;
        }
        return pinned.equalsIgnoreCase(installed);
    }

    @Nullable
    private static String installedCertificateSha256(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(
                        LOAN_MANAGER_PACKAGE,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return null;
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo info = pm.getPackageInfo(
                        LOAN_MANAGER_PACKAGE,
                        PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] legacy = info.signatures;
                signatures = legacy;
            }
            if (signatures == null || signatures.length == 0) return null;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(signatures[0].toByteArray());
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                out.append(String.format(Locale.US, "%02x", current & 0xff));
            }
            return out.toString();
        } catch (Exception unavailable) {
            return null;
        }
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
