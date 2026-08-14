package com.tridev.familyhub.feature.finance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.repository.FinanceRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * STEP 10 - receives only finalized FAMILY loan-payment projections.
 *
 * LoanManager is verified by Binder UID + exact package + same signing
 * certificate. The deterministic cloudId makes retries idempotent.
 */
public final class LoanManagerProjectionProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.tridev.familyhub.tridev.loanprojection";
    private static final String TRUSTED_PACKAGE = "com.tridev.loanmanagerpro";

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg,
                       @Nullable Bundle extras) {
        Context context = getContext();
        if (context == null) return response("FAILED", "Family Hub unavailable");
        if (!trustedCaller(context)) return response("REJECTED", "LoanManager caller is not trusted");
        if (!"accept_finalized_loan_payment_v1".equals(method) || extras == null) {
            return response("REJECTED", "Unsupported projection request");
        }

        try {
            String eventId = structured(extras.getString("event_id"), 120, false);
            String loanId = structured(extras.getString("loan_id"), 40, false);
            String paymentId = structured(extras.getString("payment_id"), 40, false);
            String loanName = metadata(extras.getString("loan_name"), 120);
            String lenderName = metadata(extras.getString("lender_name"), 120);
            String category = metadata(extras.getString("category"), 80);
            long amount = extras.getLong("amount", 0L);
            long occurredAt = extras.getLong("occurred_at", 0L);
            if (amount <= 0L || occurredAt <= 0L) throw new IllegalArgumentException();

            String cloudId = "loan_projection_" + sha256(eventId).substring(0, 32);
            FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
            FinanceEntry existing = database.financeEntryDao().getByCloudId(cloudId);
            if (existing != null) {
                return response("ACCEPTED", "Family loan payment is already projected");
            }

            long now = System.currentTimeMillis();
            FinanceEntry entry = new FinanceEntry();
            entry.entryType = FinanceEntry.TYPE_EXPENSE;
            entry.amount = amount;
            entry.category = category.isEmpty() ? "Loan EMI" : category;
            entry.note = "[LoanManagerProjection] event=" + eventId
                    + " loan=" + loanId + " payment=" + paymentId;
            entry.transactionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new Date(occurredAt));
            entry.createdAt = now;
            entry.updatedAt = now;
            entry.accountName = loanName.isEmpty() ? "Loan Payment" : loanName;
            entry.paymentMethod = "LoanManagerPro";
            entry.isRecurring = false;
            entry.isShared = true;
            entry.cloudId = cloudId;
            entry.familyId = "";
            entry.updatedByUid = "";
            entry.updatedByName = lenderName;
            entry.recurrenceStatus = "POSTED";

            entry.id = database.financeEntryDao().insert(entry);
            if (entry.id <= 0L) return response("FAILED", "Family finance row was not created");

            // Reuse the existing Family Finance publisher so active household
            // membership/familyId and realtime cloud sharing remain canonical.
            new FinanceRepository(context).save(entry, () -> { });
            return response("ACCEPTED", "Finalized loan payment projected to Family Hub Finance");
        } catch (RuntimeException invalid) {
            return response("REJECTED", "Loan projection failed validation");
        }
    }

    private boolean trustedCaller(@NonNull Context context) {
        int uid = Binder.getCallingUid();
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean packageMatch = false;
        if (packages != null) {
            for (String packageName : packages) {
                if (TRUSTED_PACKAGE.equals(packageName)) {
                    packageMatch = true;
                    break;
                }
            }
        }
        return packageMatch
                && pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
    }

    private Bundle response(@NonNull String status, @NonNull String reason) {
        Bundle out = new Bundle();
        out.putString("status", status);
        out.putString("reason", reason);
        return out;
    }

    private String structured(@Nullable String value, int max, boolean optional) {
        String safe = value == null ? "" : value.trim();
        if (!optional && safe.isEmpty()) throw new IllegalArgumentException();
        if (safe.length() > max || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            throw new IllegalArgumentException();
        }
        return safe;
    }

    private String metadata(@Nullable String value, int max) {
        String safe = value == null ? "" : value.trim();
        safe = safe.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ");
        return safe.length() <= max ? safe : safe.substring(0, max).trim();
    }

    private String sha256(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            return String.format(Locale.US, "%064x", value.hashCode());
        }
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) { return null; }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
