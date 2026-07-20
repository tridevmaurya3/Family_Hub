package com.tridev.familyhub.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardStats;

import java.util.Calendar;
import java.util.List;

public class DashboardRepository {

    public interface DashboardDataCallback {
        void onLoaded(@NonNull DashboardData dashboardData);
    }

    private final FinanceRepository financeRepository;
    private final ReminderRepository reminderRepository;

    public DashboardRepository(@NonNull Context context) {
        financeRepository = new FinanceRepository(context);
        reminderRepository = new ReminderRepository(context);
    }

    public void loadDashboardData(
            @NonNull DashboardDataCallback callback
    ) {

        DashboardStats stats = new DashboardStats();
        DashboardData dashboardData = new DashboardData();
        dashboardData.setStats(stats);

        financeRepository.loadCurrentMonthSummary(summary -> {

            FinanceSummary safeSummary =
                    summary == null
                            ? new FinanceSummary()
                            : summary;

            stats.setIncome(safeSummary.income);
            stats.setExpense(safeSummary.expense);
            stats.setBalance(
                    safeSummary.income - safeSummary.expense
            );

            reminderRepository.loadEnabledReminders(reminders -> {

                stats.setUpcomingReminders(reminders.size());

                Reminder nextReminder =
                        findNextReminder(reminders);

                dashboardData.setNextReminder(nextReminder);

                if (nextReminder != null) {
                    dashboardData.setNextReminderTriggerAt(
                            nextTriggerTime(nextReminder)
                    );
                }

                // Placeholder values until other modules are ready
                stats.setTotalMembers(0);
                stats.setMaleMembers(0);
                stats.setFemaleMembers(0);
                stats.setChildren(0);

                stats.setDocuments(0);
                stats.setHealthAlerts(0);

                callback.onLoaded(dashboardData);
            });

        });
    }

    private Reminder findNextReminder(
            @NonNull List<Reminder> reminders
    ) {

        Reminder closest = null;
        long nearest = Long.MAX_VALUE;

        for (Reminder reminder : reminders) {

            long trigger =
                    nextTriggerTime(reminder);

            if (trigger > 0 && trigger < nearest) {

                nearest = trigger;
                closest = reminder;
            }
        }

        return closest;
    }

    private long nextTriggerTime(
            @NonNull Reminder reminder
    ) {

        long now = System.currentTimeMillis();

        if (Reminder.REPEAT_ONCE.equals(
                reminder.repeatType
        )) {

            return reminder.reminderAt > now
                    ? reminder.reminderAt
                    : -1L;
        }

        Calendar calendar =
                Calendar.getInstance();

        calendar.setTimeInMillis(
                reminder.reminderAt
        );

        while (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DATE, 1);
        }

        return calendar.getTimeInMillis();
    }
}