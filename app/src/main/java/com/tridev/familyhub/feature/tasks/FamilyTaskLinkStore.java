package com.tridev.familyhub.feature.tasks;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskLinkRepository;

/**
 * UI compatibility wrapper for optional Task -> Finance / Loan references.
 * Stage 12 preserves every Stage 9 local key while the repository mirrors the
 * same non-destructive references through the existing Family Task sync.
 */
final class FamilyTaskLinkStore {
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
        FamilyTaskLinkRepository.Link link =
                new FamilyTaskLinkRepository(context).load(task);
        return new Link(link.financeCloudId, link.financeLabel,
                link.loanId, link.loanName);
    }

    static void save(@NonNull Context context, @NonNull FamilyTask task,
                     @NonNull Link link) {
        new FamilyTaskLinkRepository(context).save(task,
                new FamilyTaskLinkRepository.Link(
                        link.financeCloudId, link.financeLabel,
                        link.loanId, link.loanName));
    }
}
