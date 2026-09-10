package com.tridev.familyhub.feature.tasks;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.entity.FamilyTask;

/**
 * Device-local persistence for optional Task -> Finance / Loan references.
 *
 * Stage 9 deliberately keeps these links outside the Finance/Loan tables so a
 * task can never post, edit, complete, or delete money records by accident.
 * Stage 12 can promote the same cloud-id based references to multi-device sync
 * without changing the existing Finance or Loan ownership rules.
 */
final class FamilyTaskLinkStore {
    private static final String PREFS = "family_task_optional_links";

    static final class Link {
        @NonNull final String financeCloudId;
        @NonNull final String financeLabel;
        @NonNull final String loanId;
        @NonNull final String loanName;

        Link(@NonNull String financeCloudId, @NonNull String financeLabel,
             @NonNull String loanId, @NonNull String loanName) {
            this.financeCloudId = financeCloudId;
            this.financeLabel = financeLabel;
            this.loanId = loanId;
            this.loanName = loanName;
        }

        boolean hasFinance() { return !financeCloudId.isEmpty(); }
        boolean hasLoan() { return !loanId.isEmpty(); }
        boolean hasAny() { return hasFinance() || hasLoan(); }
    }

    private FamilyTaskLinkStore() { }

    @NonNull
    static Link load(@NonNull Context context, @NonNull FamilyTask task) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Link exact = read(prefs, taskKey(task));
        if (exact.hasAny()) return exact;
        String series = seriesKey(task);
        return series.isEmpty() ? exact : read(prefs, series);
    }

    static void save(@NonNull Context context, @NonNull FamilyTask task,
                     @NonNull Link link) {
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        write(editor, taskKey(task), link);
        String series = seriesKey(task);
        if (!series.isEmpty()) write(editor, series, link);
        editor.apply();
    }

    @NonNull
    private static Link read(@NonNull SharedPreferences prefs, @NonNull String key) {
        return new Link(
                prefs.getString(key + ".financeCloudId", ""),
                prefs.getString(key + ".financeLabel", ""),
                prefs.getString(key + ".loanId", ""),
                prefs.getString(key + ".loanName", "")
        );
    }

    private static void write(@NonNull SharedPreferences.Editor editor,
                              @NonNull String key, @NonNull Link link) {
        editor.putString(key + ".financeCloudId", link.financeCloudId);
        editor.putString(key + ".financeLabel", link.financeLabel);
        editor.putString(key + ".loanId", link.loanId);
        editor.putString(key + ".loanName", link.loanName);
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
}
