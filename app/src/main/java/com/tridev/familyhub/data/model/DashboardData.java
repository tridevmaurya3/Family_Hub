package com.tridev.familyhub.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.Reminder;

/**
 * Central data model for the Family Hub dashboard.
 *
 * This model contains:
 * - Dashboard statistics
 * - Current month finance information
 * - Upcoming reminder information
 *
 * Future dashboard modules such as Health, Documents,
 * Vehicle and Property can be added here without changing
 * the dashboard data-loading structure.
 */
public class DashboardData {

    @NonNull
    private DashboardStats stats;

    @Nullable
    private Reminder nextReminder;

    private long nextReminderTriggerAt;

    @Nullable
    private FamilyMember nextBirthdayMember;

    private long nextBirthdayAt;

    @Nullable
    private Reminder nextBillReminder;

    private long nextBillTriggerAt;

    public DashboardData() {
        stats = new DashboardStats();
        nextReminder = null;
        nextReminderTriggerAt = -1L;
        nextBirthdayMember = null;
        nextBirthdayAt = -1L;
        nextBillReminder = null;
        nextBillTriggerAt = -1L;
    }

    public DashboardData(
            @NonNull DashboardStats stats,
            @Nullable Reminder nextReminder,
            long nextReminderTriggerAt
    ) {
        this.stats = stats;
        this.nextReminder = nextReminder;
        this.nextReminderTriggerAt = nextReminderTriggerAt;
        nextBirthdayMember = null;
        nextBirthdayAt = -1L;
        nextBillReminder = null;
        nextBillTriggerAt = -1L;
    }

    @NonNull
    public DashboardStats getStats() {
        return stats;
    }

    public void setStats(
            @NonNull DashboardStats stats
    ) {
        this.stats = stats;
    }

    @Nullable
    public Reminder getNextReminder() {
        return nextReminder;
    }

    public void setNextReminder(
            @Nullable Reminder nextReminder
    ) {
        this.nextReminder = nextReminder;
    }

    public long getNextReminderTriggerAt() {
        return nextReminderTriggerAt;
    }

    public void setNextReminderTriggerAt(
            long nextReminderTriggerAt
    ) {
        this.nextReminderTriggerAt =
                nextReminderTriggerAt;
    }

    public boolean hasUpcomingReminder() {
        return nextReminder != null
                && nextReminderTriggerAt > 0L;
    }

    @Nullable
    public FamilyMember getNextBirthdayMember() {
        return nextBirthdayMember;
    }

    public void setNextBirthdayMember(@Nullable FamilyMember member) {
        nextBirthdayMember = member;
    }

    public long getNextBirthdayAt() {
        return nextBirthdayAt;
    }

    public void setNextBirthdayAt(long nextBirthdayAt) {
        this.nextBirthdayAt = nextBirthdayAt;
    }

    public boolean hasUpcomingBirthday() {
        return nextBirthdayMember != null && nextBirthdayAt > 0L;
    }

    @Nullable
    public Reminder getNextBillReminder() {
        return nextBillReminder;
    }

    public void setNextBillReminder(@Nullable Reminder reminder) {
        nextBillReminder = reminder;
    }

    public long getNextBillTriggerAt() {
        return nextBillTriggerAt;
    }

    public void setNextBillTriggerAt(long nextBillTriggerAt) {
        this.nextBillTriggerAt = nextBillTriggerAt;
    }

    public boolean hasUpcomingBill() {
        return nextBillReminder != null && nextBillTriggerAt > 0L;
    }

    public double getIncome() {
        return stats.getIncome();
    }

    public double getExpense() {
        return stats.getExpense();
    }

    public double getBalance() {
        return stats.getBalance();
    }

    public int getUpcomingReminderCount() {
        return stats.getUpcomingReminders();
    }

    public int getTotalMembers() {
        return stats.getTotalMembers();
    }

    public int getHealthAlerts() {
        return stats.getHealthAlerts();
    }

    public int getDocumentCount() {
        return stats.getDocuments();
    }
}
