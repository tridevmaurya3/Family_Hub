package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardStats;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Central read-only data source for the dashboard. */
public class DashboardRepository {

    public interface DashboardDataCallback {
        void onLoaded(@NonNull DashboardData dashboardData);
    }

    private final FinanceRepository financeRepository;
    private final ReminderRepository reminderRepository;
    private final FamilyHubDatabase database;
    private final ExecutorService databaseExecutor;
    private final Handler mainHandler;

    public DashboardRepository(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        financeRepository = new FinanceRepository(applicationContext);
        reminderRepository = new ReminderRepository(applicationContext);
        database = FamilyHubDatabase.getInstance(applicationContext);
        databaseExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
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

                loadLocalCounts(dashboardData, callback);
            });

        });
    }

    private void loadLocalCounts(
            @NonNull DashboardData dashboardData,
            @NonNull DashboardDataCallback callback
    ) {
        databaseExecutor.execute(() -> {
            DashboardStats stats = dashboardData.getStats();
            stats.setTotalMembers(database.familyMemberDao().count());
            stats.setDocuments(database.documentDao().count());
            stats.setHealthAlerts(database.healthRecordDao().count());

            // These fields become live when the corresponding modules exist.
            stats.setMaleMembers(0);
            stats.setFemaleMembers(0);
            stats.setChildren(0);

            mainHandler.post(() -> callback.onLoaded(dashboardData));
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

    public void close() {
        databaseExecutor.shutdown();
    }
}
