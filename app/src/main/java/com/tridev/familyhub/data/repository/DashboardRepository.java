package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardStats;

import java.util.Calendar;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Central read-only data source for the dashboard. */
public class DashboardRepository {

    public interface DashboardDataCallback {
        void onLoaded(@NonNull DashboardData dashboardData);
    }

    public interface DashboardErrorCallback {
        void onError(@NonNull Throwable error);
    }

    private final FinanceRepository financeRepository;
    private final ReminderRepository reminderRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final FamilyLiveRepository familyLiveRepository;
    private final FamilyHubDatabase database;
    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DashboardRepository(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        financeRepository = new FinanceRepository(applicationContext);
        reminderRepository = new ReminderRepository(applicationContext);
        familyMemberRepository = new FamilyMemberRepository(applicationContext);
        familyLiveRepository = new FamilyLiveRepository(applicationContext);
        database = FamilyHubDatabase.getInstance(applicationContext);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void loadDashboardData(
            @NonNull DashboardDataCallback callback,
            @NonNull DashboardErrorCallback errorCallback
    ) {
        if (closed.get()) {
            return;
        }

        DashboardStats stats = new DashboardStats();
        DashboardData dashboardData = new DashboardData();
        dashboardData.setStats(stats);

        financeRepository.loadCurrentMonthSummary(summary -> {
            if (closed.get()) {
                return;
            }

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
                if (closed.get()) {
                    return;
                }

                stats.setUpcomingReminders(reminders.size());

                Reminder nextReminder =
                        findNextReminder(reminders);

                dashboardData.setNextReminder(nextReminder);

                if (nextReminder != null) {
                    dashboardData.setNextReminderTriggerAt(
                            nextTriggerTime(nextReminder)
                    );
                }

                Reminder nextBill = findNextBillReminder(reminders);
                dashboardData.setNextBillReminder(nextBill);
                if (nextBill != null) {
                    dashboardData.setNextBillTriggerAt(
                            nextTriggerTime(nextBill)
                    );
                }

                familyMemberRepository.loadMembers("", members ->
                        loadAuthorisedCounts(
                                dashboardData, callback, errorCallback
                        ));
            });

        });
    }

    private void loadAuthorisedCounts(
            @NonNull DashboardData dashboardData,
            @NonNull DashboardDataCallback callback,
            @NonNull DashboardErrorCallback errorCallback
    ) {
        familyLiveRepository.observeCloudMembers(members -> {
            if (closed.get()) {
                return;
            }
            familyLiveRepository.stopObservingCloudMembers();
            dashboardData.getStats().setTotalMembers(members.size());
            int sharing = 0;
            for (com.tridev.familyhub.data.model.FamilyLiveCloudMember member
                    : members) {
                if (member.sharingEnabled) {
                    sharing++;
                }
            }
            dashboardData.getStats().setFamilyLiveSharing(sharing);
            loadLocalCounts(dashboardData, callback, errorCallback, true);
        }, error -> {
            if (closed.get()) {
                return;
            }
            familyLiveRepository.stopObservingCloudMembers();
            loadLocalCounts(dashboardData, callback, errorCallback, false);
        });
    }

    private void loadLocalCounts(
            @NonNull DashboardData dashboardData,
            @NonNull DashboardDataCallback callback,
            @NonNull DashboardErrorCallback errorCallback,
            boolean cloudMembershipsLoaded
    ) {
        if (closed.get()) {
            return;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                DashboardStats stats = dashboardData.getStats();
                long thirtyDaysFromNow = System.currentTimeMillis()
                        + (30L * 24L * 60L * 60L * 1000L);
                List<FamilyMember> members = database.familyMemberDao().getAll();
                if (!cloudMembershipsLoaded) {
                    stats.setTotalMembers(members.size());
                }
                stats.setDocuments(database.documentDao().count());
                stats.setHealthAlerts(database.healthRecordDao().count());
                stats.setPlannerOpen(database.plannerItemDao().countOpen());
                stats.setPlannerCompleted(
                        database.plannerItemDao().countCompleted()
                );
                stats.setGroceryPending(
                        database.groceryItemDao().countPending()
                );
                stats.setGroceryPurchased(
                        database.groceryItemDao().countPurchased()
                );
                stats.setDocumentsExpiringSoon(
                        database.documentDao().countExpiringBy(thirtyDaysFromNow)
                );
                stats.setVehiclesDueSoon(
                        database.vehicleDao().countDueBy(thirtyDaysFromNow)
                );
                stats.setActiveNotes(database.noteDao().countActive());
                stats.setPinnedNotes(database.noteDao().countPinned());
                if (!cloudMembershipsLoaded) {
                    stats.setFamilyLiveSharing(
                            database.familyLiveStatusDao()
                                    .countSharingEnabled()
                    );
                }
                stats.setMaleMembers(
                        database.familyMemberDao().countByGender("Male")
                );
                stats.setFemaleMembers(
                        database.familyMemberDao().countByGender("Female")
                );
                stats.setChildren(
                        database.familyMemberDao().countByRole(
                                FamilyMember.ROLE_CHILD
                        )
                );

                BirthdayCandidate birthday = findNextBirthday(members);
                if (birthday != null) {
                    dashboardData.setNextBirthdayMember(birthday.member);
                    dashboardData.setNextBirthdayAt(birthday.when);
                }

                mainHandler.post(() -> {
                    if (!closed.get()) {
                        callback.onLoaded(dashboardData);
                    }
                });
            } catch (RuntimeException error) {
                mainHandler.post(() -> {
                    if (!closed.get()) {
                        errorCallback.onError(error);
                    }
                });
            }
        });
    }

    @Nullable
    private Reminder findNextBillReminder(@NonNull List<Reminder> reminders) {
        Reminder closest = null;
        long nearest = Long.MAX_VALUE;

        for (Reminder reminder : reminders) {
            String searchable = (reminder.title + " " + reminder.note)
                    .toLowerCase(Locale.ROOT);
            boolean isBill = searchable.contains("bill")
                    || searchable.contains("payment")
                    || searchable.contains("emi")
                    || searchable.contains("electricity")
                    || searchable.contains("insurance")
                    || searchable.contains("fee")
                    || searchable.contains("recharge")
                    || searchable.contains("बिल")
                    || searchable.contains("भुगतान")
                    || searchable.contains("किश्त")
                    || searchable.contains("ईएमआई");
            long trigger = nextTriggerTime(reminder);
            if (isBill && trigger > 0L && trigger < nearest) {
                nearest = trigger;
                closest = reminder;
            }
        }
        return closest;
    }

    @Nullable
    private BirthdayCandidate findNextBirthday(
            @NonNull List<FamilyMember> members
    ) {
        LocalDate today = LocalDate.now();
        BirthdayCandidate closest = null;

        for (FamilyMember member : members) {
            if (member.dateOfBirth.trim().isEmpty()) {
                continue;
            }
            try {
                LocalDate birthDate = LocalDate.parse(
                        member.dateOfBirth,
                        DateTimeFormatter.ISO_LOCAL_DATE
                );
                LocalDate next = birthdayInYear(birthDate, today.getYear());
                if (next.isBefore(today)) {
                    next = birthdayInYear(birthDate, today.getYear() + 1);
                }
                long when = next.atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
                if (closest == null || when < closest.when) {
                    closest = new BirthdayCandidate(member, when);
                }
            } catch (DateTimeException ignored) {
                // Invalid legacy dates are skipped instead of breaking Dashboard.
            }
        }
        return closest;
    }

    @NonNull
    private LocalDate birthdayInYear(
            @NonNull LocalDate birthDate,
            int year
    ) {
        try {
            return birthDate.withYear(year);
        } catch (DateTimeException leapDay) {
            return LocalDate.of(year, 2, 28);
        }
    }

    private static final class BirthdayCandidate {
        @NonNull final FamilyMember member;
        final long when;

        BirthdayCandidate(@NonNull FamilyMember member, long when) {
            this.member = member;
            this.when = when;
        }
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
        closed.set(true);
        familyLiveRepository.close();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
