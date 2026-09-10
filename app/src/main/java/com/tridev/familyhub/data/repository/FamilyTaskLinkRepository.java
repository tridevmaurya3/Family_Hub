package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.tridev.familyhub.data.local.entity.FamilyTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Realtime-safe persistence for optional Task -> Finance / Loan references.
 *
 * The references remain metadata only: this repository never creates, edits,
 * completes or deletes Finance/Loan records. Stage 12 keeps the existing
 * SharedPreferences keys for backward compatibility and mirrors the same
 * identifiers into the already-authorised Family Task cloud record.
 */
public final class FamilyTaskLinkRepository {
    private static final String PREFS = "family_task_optional_links";
    private static final String SYNC_PREFIX = "cloudSynced:";

    public static final class Link {
        @NonNull public final String financeCloudId;
        @NonNull public final String financeLabel;
        @NonNull public final String loanId;
        @NonNull public final String loanName;

        public Link(@NonNull String financeCloudId, @NonNull String financeLabel,
                    @NonNull String loanId, @NonNull String loanName) {
            this.financeCloudId = limit(clean(financeCloudId), 160);
            this.financeLabel = limit(clean(financeLabel), 180);
            this.loanId = limit(clean(loanId), 160);
            this.loanName = limit(clean(loanName), 120);
        }

        public boolean hasFinance() { return !financeCloudId.isEmpty(); }
        public boolean hasLoan() { return !loanId.isEmpty(); }
        public boolean hasAny() { return hasFinance() || hasLoan(); }
    }

    @NonNull private final SharedPreferences preferences;

    public FamilyTaskLinkRepository(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public Link load(@NonNull FamilyTask task) {
        Link exact = read(taskKey(task));
        if (exact.hasAny()) return exact;
        String series = seriesKey(task);
        return series.isEmpty() ? exact : read(series);
    }

    public void save(@NonNull FamilyTask task, @NonNull Link link) {
        writeLocal(task, link);
        markSynced(task, false);
        publishReference(task, link);
    }

    /** Imports reference metadata delivered through the existing Task realtime snapshot. */
    public void mergeRemote(@NonNull FamilyTask task, @NonNull DataSnapshot snapshot) {
        boolean hasCloudFields = snapshot.child("linkedFinanceCloudId").exists()
                || snapshot.child("linkedFinanceLabel").exists()
                || snapshot.child("linkedLoanId").exists()
                || snapshot.child("linkedLoanName").exists();
        if (hasCloudFields) {
            Link cloud = new Link(
                    text(snapshot, "linkedFinanceCloudId"),
                    text(snapshot, "linkedFinanceLabel"),
                    text(snapshot, "linkedLoanId"),
                    text(snapshot, "linkedLoanName")
            );
            writeLocal(task, cloud);
            markSynced(task, true);
            return;
        }

        // Stage 9 migration path: a pre-existing local link is promoted once the
        // task has a confirmed family/cloud identity. No financial data is copied.
        promoteIfNeeded(task);
    }

    /** Called after a task receives/retains a confirmed family cloud identity. */
    public void promoteIfNeeded(@NonNull FamilyTask task) {
        if (task.cloudId.isEmpty() || task.familyId.isEmpty() || isSynced(task)) return;
        Link local = load(task);
        if (local.hasAny()) publishReference(task, local);
        else markSynced(task, true);
    }

    private void publishReference(@NonNull FamilyTask task, @NonNull Link link) {
        if (task.cloudId.isEmpty() || task.familyId.isEmpty()) return;
        Map<String, Object> values = new HashMap<>();
        values.put("linkedFinanceCloudId", link.financeCloudId);
        values.put("linkedFinanceLabel", link.financeLabel);
        values.put("linkedLoanId", link.loanId);
        values.put("linkedLoanName", link.loanName);
        values.put("updatedByName", displayName());
        FamilyCollaborationPublisher.publish("tasks", task.cloudId, values,
                (cloudId, familyId, uid) -> markSynced(task, true));
    }

    private void writeLocal(@NonNull FamilyTask task, @NonNull Link link) {
        SharedPreferences.Editor editor = preferences.edit();
        write(editor, taskKey(task), link);
        String series = seriesKey(task);
        if (!series.isEmpty()) write(editor, series, link);
        editor.apply();
    }

    @NonNull
    private Link read(@NonNull String key) {
        return new Link(
                preferences.getString(key + ".financeCloudId", ""),
                preferences.getString(key + ".financeLabel", ""),
                preferences.getString(key + ".loanId", ""),
                preferences.getString(key + ".loanName", "")
        );
    }

    private static void write(@NonNull SharedPreferences.Editor editor,
                              @NonNull String key, @NonNull Link link) {
        editor.putString(key + ".financeCloudId", link.financeCloudId);
        editor.putString(key + ".financeLabel", link.financeLabel);
        editor.putString(key + ".loanId", link.loanId);
        editor.putString(key + ".loanName", link.loanName);
    }

    private boolean isSynced(@NonNull FamilyTask task) {
        return preferences.getBoolean(SYNC_PREFIX + taskKey(task), false);
    }

    private void markSynced(@NonNull FamilyTask task, boolean synced) {
        preferences.edit().putBoolean(SYNC_PREFIX + taskKey(task), synced).apply();
    }

    @NonNull
    private static String taskKey(@NonNull FamilyTask task) {
        if (!task.cloudId.isEmpty()) return "task:" + task.cloudId;
        return "local:" + task.id;
    }

    @NonNull
    private static String seriesKey(@NonNull FamilyTask task) {
        if (!task.sourceRecordId.isEmpty()) return "series:" + task.sourceRecordId;
        if (!FamilyTask.REPEAT_NONE.equals(task.repeatType) && !task.cloudId.isEmpty()) {
            return "series:" + task.cloudId;
        }
        return "";
    }

    @NonNull
    private static String text(@NonNull DataSnapshot snapshot, @NonNull String key) {
        String value = snapshot.child(key).getValue(String.class);
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String displayName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.getDisplayName() == null
                || user.getDisplayName().trim().isEmpty()) return "Family member";
        return limit(user.getDisplayName().trim(), 100);
    }

    @NonNull
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String limit(@NonNull String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).trim();
    }
}
