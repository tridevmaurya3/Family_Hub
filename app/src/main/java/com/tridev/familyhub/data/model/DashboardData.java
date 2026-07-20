package com.tridev.familyhub.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    public DashboardData() {
        stats = new DashboardStats();
        nextReminder = null;
        nextReminderTriggerAt = -1L;
    }

    public DashboardData(
            @NonNull DashboardStats stats,
            @Nullable Reminder nextReminder,
            long nextReminderTriggerAt
    ) {
        this.stats = stats;
        this.nextReminder = nextReminder;
        this.nextReminderTriggerAt = nextReminderTriggerAt;
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