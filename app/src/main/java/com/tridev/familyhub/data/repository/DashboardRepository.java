package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.data.model.DashboardData.Entry;
import com.tridev.familyhub.data.model.DashboardData.Destination;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.local.entity.PlannerItem;
import java.util.ArrayList;
import java.util.Comparator;
import com.tridev.familyhub.data.model.DashboardStats;
import com.tridev.familyhub.data.model.NotesDashboardSummary;
import com.tridev.familyhub.feature.grocery.GroceryRecurrenceEngine;

import java.util.Calendar;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
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
                List<Entry> upcoming = new ArrayList<>();
                List<Entry> bills = new ArrayList<>();
                for (Reminder reminder : reminders) {
                    long next = nextTriggerTime(reminder);
                    addEntry(upcoming, reminder.title, "Reminder", next, Destination.REMINDERS);
                    if (isBill(reminder)) addEntry(bills, reminder.title, "Bill reminder", next, Destination.REMINDERS);
                }
                dashboardData.overview = upcoming;
                dashboardData.highlights = bills;

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
                int activeGroceryPending = 0;
                long groceryNow = System.currentTimeMillis();
                for (GroceryItem item : database.groceryItemDao().getAll()) {
                    if (item.isPurchased) continue;
                    String cycle = GroceryRecurrenceEngine.effectiveCycle(
                            item, groceryNow);
                    if (GroceryRecurrenceEngine.matchesCycle(
                            item, cycle, groceryNow)) {
                        activeGroceryPending++;
                    }
                }
                stats.setGroceryPending(activeGroceryPending);
                stats.setGroceryPurchased(
                        database.groceryItemDao().countPurchased()
                );
                Set<String> priorityOwners = new LinkedHashSet<>();
                boolean wholeFamilyPriority = false;
                for (com.tridev.familyhub.data.local.entity.PlannerItem item
                        : database.plannerItemDao().getAll()) {
                    if (item.isCompleted) continue;
                    String owner = item.assignedMemberName.trim();
                    if (owner.isEmpty()) wholeFamilyPriority = true;
                    else priorityOwners.add(owner);
                }
                for (com.tridev.familyhub.data.local.entity.GroceryItem item
                        : database.groceryItemDao().getAll()) {
                    if (item.isPurchased) continue;
                    String owner = item.assignedMemberName.trim();
                    if (owner.isEmpty()) wholeFamilyPriority = true;
                    else priorityOwners.add(owner);
                }
                if (wholeFamilyPriority) priorityOwners.add("Whole family");
                dashboardData.setPriorityOwners(
                        android.text.TextUtils.join(", ", priorityOwners));
                stats.setDocumentsExpiringSoon(
                        database.documentDao().countExpiringBy(thirtyDaysFromNow)
                );
                stats.setVehiclesDueSoon(
                        database.vehicleDao().countDueBy(thirtyDaysFromNow)
                );
                NotesDashboardSummary.from(database.noteDao().getActive()).applyTo(stats);
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

                populateDashboardLists(dashboardData, members);
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
            long trigger = nextTriggerTime(reminder);
            if (isBill(reminder) && trigger > 0L && trigger < nearest) {
                nearest = trigger;
                closest = reminder;
            }
        }
        return closest;
    }

    private boolean isBill(Reminder reminder) {
        String searchable = (reminder.title + " " + reminder.note)
                .toLowerCase(Locale.ROOT);
        return searchable.contains("bill")
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
    }

    private void populateDashboardLists(DashboardData data, List<FamilyMember> members) {
        long now = System.currentTimeMillis();
        List<Entry> recent = new ArrayList<>();
        for (PlannerItem item : database.plannerItemDao().getAll()) {
            if (!item.isCompleted && item.startAt >= now) {
                addEntry(data.overview, item.title, "Planner", item.startAt, Destination.PLANNER);
            }
            addEntry(recent, item.title, "Planner", Math.max(item.createdAt, item.updatedAt), Destination.PLANNER);
        }
        for (FamilyTask task : database.familyTaskDao().getAll()) {
            if (FamilyTask.STATUS_PENDING.equals(task.status) && task.dueAt >= now) {
                addEntry(data.overview, task.title, "To-Do", task.dueAt, Destination.TASKS);
            }
            addEntry(recent, task.title, "To-Do", Math.max(task.completedAt,
                    Math.max(task.createdAt, task.updatedAt)), Destination.TASKS);
        }
        for (GroceryItem item : database.groceryItemDao().getAll()) {
            addEntry(recent, item.name, "Grocery", Math.max(item.purchasedAt,
                    Math.max(item.createdAt, item.updatedAt)), Destination.GROCERY);
        }
        for (Reminder reminder : database.reminderDao().getAll()) {
            addEntry(recent, reminder.title, "Reminder", Math.max(reminder.completedAt,
                    Math.max(reminder.createdAt, reminder.updatedAt)), Destination.REMINDERS);
        }
        List<Entry> memberUpdates = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (FamilyMember member : members) {
            if (member.cloudRemoved) continue;
            addEntry(data.highlights, member.name, "Birthday", nextBirthdayAt(member, today), Destination.FAMILY);
            addEntry(memberUpdates, member.name, "Family member", Math.max(member.createdAt, member.updatedAt), Destination.FAMILY);
        }
        recent.addAll(memberUpdates);
        Comparator<Entry> byDate = Comparator.comparingLong(entry -> entry.at);
        data.overview = firstFive(data.overview, byDate);
        data.highlights = firstFive(data.highlights, byDate);
        List<Entry> updates = firstFive(memberUpdates, byDate.reversed());
        data.highlights.addAll(updates.subList(0, Math.min(5 - data.highlights.size(), updates.size())));
        data.recentActivity = firstFive(recent, byDate.reversed());
    }

    private static void addEntry(List<Entry> items, String title, String category, long at, Destination destination) {
        if (at > 0 && title != null && !title.trim().isEmpty()) items.add(new Entry(title, category, at, destination));
    }

    private static List<Entry> firstFive(List<Entry> items, Comparator<Entry> order) {
        items.sort(order);
        return new ArrayList<>(items.subList(0, Math.min(5, items.size())));
    }

    @Nullable
    private BirthdayCandidate findNextBirthday(@NonNull List<FamilyMember> members) {
        LocalDate today = LocalDate.now();
        BirthdayCandidate closest = null;
        for (FamilyMember member : members) {
            long when = nextBirthdayAt(member, today);
            if (when > 0 && (closest == null || when < closest.when)) {
                closest = new BirthdayCandidate(member, when);
            }
        }
        return closest;
    }

    private long nextBirthdayAt(FamilyMember member, LocalDate today) {
        if (member.cloudRemoved || member.dateOfBirth == null || member.dateOfBirth.trim().isEmpty()) return -1L;
        try {
            LocalDate birthDate = LocalDate.parse(member.dateOfBirth, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate next = birthdayInYear(birthDate, today.getYear());
            if (next.isBefore(today)) next = birthdayInYear(birthDate, today.getYear() + 1);
            return next.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            return -1L;
        }
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
        if(!reminder.isEnabled || "COMPLETED".equalsIgnoreCase(reminder.collaborationStatus))return -1L;

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

        int field=Reminder.REPEAT_WEEKLY.equals(reminder.repeatType)?Calendar.WEEK_OF_YEAR:Reminder.REPEAT_MONTHLY.equals(reminder.repeatType)?Calendar.MONTH:Reminder.REPEAT_YEARLY.equals(reminder.repeatType)?Calendar.YEAR:Calendar.DATE;
        while (calendar.getTimeInMillis() <= now) {
            calendar.add(field, 1);
        }

        return calendar.getTimeInMillis();
    }

    public void close() {
        closed.set(true);
        familyLiveRepository.close();
        mainHandler.removeCallbacksAndMessages(null);
    }
}

