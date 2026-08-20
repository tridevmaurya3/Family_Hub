package com.tridev.familyhub.feature.grocery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.GroceryItemDao;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetProvider;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Calendar-month recurrence for Grocery master items.
 *
 * <p>The master row is never moved or deleted. When its cycle is due, exactly one
 * DAILY/PENDING occurrence is created for that calendar month. The occurrence
 * uses a deterministic cloud id, so multiple family devices converge on the
 * same due item instead of producing duplicates.</p>
 *
 * <p>No Room schema change is required: LIST_MONTHLY/LIST_TWO_MONTH/
 * LIST_THREE_MONTH use the existing listType column and lastResetMonth stores
 * the most recently generated due-month key.</p>
 */
public final class GroceryRecurrenceEngine {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String lastScheduledDay = "";

    private GroceryRecurrenceEngine() { }

    /** Run once at process start and once per local calendar day on Activity resume. */
    public static void register(@NonNull Application application) {
        schedule(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity activity,
                                                    @Nullable Bundle savedInstanceState) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityResumed(@NonNull Activity activity) {
                String today = dayKey(System.currentTimeMillis());
                if (!today.equals(lastScheduledDay)) schedule(application);
            }
            @Override public void onActivityPaused(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                               @NonNull Bundle outState) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** Safe to call repeatedly; concurrent calls collapse into one database pass. */
    public static void schedule(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        lastScheduledDay = dayKey(System.currentTimeMillis());
        if (!RUNNING.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                ensureDueOccurrences(appContext, System.currentTimeMillis());
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** Returns true when an item belongs in the selected planning-cycle view. */
    public static boolean matchesCycle(@NonNull GroceryItem item,
                                       @NonNull String selectedCycle,
                                       long now) {
        String selected = normalizeCycle(selectedCycle);
        String own = normalizeCycle(item.listType);
        if (GroceryItem.LIST_DAILY.equals(selected)) {
            return GroceryItem.LIST_DAILY.equals(own);
        }
        if (own.equals(selected)) return true;

        int remaining = monthsUntilNextDue(item, now);
        if (GroceryItem.LIST_MONTHLY.equals(selected)) {
            return isRecurringType(own)
                    && !GroceryItem.LIST_MONTHLY.equals(own)
                    && remaining <= 1;
        }
        if (GroceryItem.LIST_TWO_MONTH.equals(selected)) {
            return GroceryItem.LIST_THREE_MONTH.equals(own) && remaining <= 2;
        }
        return false;
    }

    /** Months remaining until the next due calendar month (0 means due now). */
    public static int monthsUntilNextDue(@NonNull GroceryItem item, long now) {
        int interval = intervalMonths(item.listType);
        if (interval <= 0) return Integer.MAX_VALUE;

        int createdMonth = monthIndex(item.createdAt > 0L ? item.createdAt : now);
        int currentMonth = monthIndex(now);
        int elapsed = Math.max(0, currentMonth - createdMonth);
        if (elapsed < interval) return interval - elapsed;

        int remainder = elapsed % interval;
        if (remainder != 0) return interval - remainder;

        String monthKey = monthKey(now);
        return monthKey.equals(item.lastResetMonth) ? interval : 0;
    }

    public static boolean isRecurringType(@Nullable String listType) {
        String clean = normalizeCycle(listType);
        return GroceryItem.LIST_MONTHLY.equals(clean)
                || GroceryItem.LIST_TWO_MONTH.equals(clean)
                || GroceryItem.LIST_THREE_MONTH.equals(clean);
    }

    public static int intervalMonths(@Nullable String listType) {
        String clean = normalizeCycle(listType);
        if (GroceryItem.LIST_MONTHLY.equals(clean)) return 1;
        if (GroceryItem.LIST_TWO_MONTH.equals(clean)) return 2;
        if (GroceryItem.LIST_THREE_MONTH.equals(clean)) return 3;
        return 0;
    }

    @NonNull
    public static String normalizeCycle(@Nullable String listType) {
        if (GroceryItem.LIST_MONTHLY.equals(listType)) return GroceryItem.LIST_MONTHLY;
        if (GroceryItem.LIST_TWO_MONTH.equals(listType)) return GroceryItem.LIST_TWO_MONTH;
        if (GroceryItem.LIST_THREE_MONTH.equals(listType)) return GroceryItem.LIST_THREE_MONTH;
        return GroceryItem.LIST_DAILY;
    }

    private static void ensureDueOccurrences(@NonNull Context context, long now) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        GroceryItemDao dao = database.groceryItemDao();
        List<GroceryItem> items = dao.getAll();
        String currentMonthKey = monthKey(now);
        boolean changed = false;

        for (GroceryItem master : items) {
            int interval = intervalMonths(master.listType);
            if (interval <= 0) continue;

            int createdMonth = monthIndex(master.createdAt > 0L ? master.createdAt : now);
            int currentMonth = monthIndex(now);
            int elapsed = currentMonth - createdMonth;
            if (elapsed < interval || elapsed % interval != 0) continue;
            if (currentMonthKey.equals(master.lastResetMonth)) continue;

            String dueCloudId = dueCloudId(master, currentMonthKey);
            GroceryItem existingDue = dao.getByCloudId(dueCloudId);
            if (existingDue == null) {
                GroceryItem occurrence = dailyOccurrence(master, dueCloudId, now);
                dao.insert(occurrence);
                changed = true;
            }

            // Mark only the generated month; do not move, purchase or delete the master.
            master.lastResetMonth = currentMonthKey;
            master.updatedAt = Math.max(master.updatedAt, now);
            dao.update(master);
            changed = true;
        }

        if (changed) GroceryWidgetProvider.refreshAll(context);
    }

    @NonNull
    private static GroceryItem dailyOccurrence(@NonNull GroceryItem master,
                                               @NonNull String dueCloudId,
                                               long now) {
        GroceryItem item = new GroceryItem();
        item.name = master.name;
        item.category = master.category;
        item.quantity = master.quantity;
        item.estimatedCost = master.estimatedCost;
        item.actualCost = 0D;
        item.storeName = master.storeName;
        item.autoPriceEnabled = master.autoPriceEnabled;
        item.priceLocationKey = master.priceLocationKey;
        item.priceConfidence = master.priceConfidence;
        item.priority = master.priority;
        item.isPurchased = false;
        item.buyingStatus = GroceryItem.STATUS_PENDING;
        item.isMonthlyMaster = false;
        item.lastResetMonth = "";
        item.purchaseCount = 0;
        item.financeEntryId = 0L;
        item.notes = master.notes;
        item.listType = GroceryItem.LIST_DAILY;
        item.assignedMemberId = master.assignedMemberId;
        item.assignedMemberName = master.assignedMemberName;
        item.purchasedByName = "";
        item.createdAt = firstDayOfMonth(now);
        item.purchasedAt = 0L;
        item.cloudId = dueCloudId;
        // Empty familyId makes the existing Grocery sync upload this local occurrence.
        item.familyId = "";
        item.updatedAt = now;
        item.updatedByUid = master.updatedByUid;
        item.updatedByName = master.updatedByName;
        return item;
    }

    @NonNull
    private static String dueCloudId(@NonNull GroceryItem master,
                                     @NonNull String dueMonth) {
        String stableMasterId = master.cloudId == null ? "" : master.cloudId.trim();
        if (stableMasterId.isEmpty()) {
            stableMasterId = master.id + "|" + master.createdAt + "|" + master.name;
        }
        UUID uuid = UUID.nameUUIDFromBytes(
                ("grocery-recurrence|" + stableMasterId + "|" + dueMonth)
                        .getBytes(StandardCharsets.UTF_8));
        return "recurrence-" + uuid;
    }

    private static int monthIndex(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH);
    }

    private static long firstDayOfMonth(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @NonNull
    private static String monthKey(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(Locale.ENGLISH, "%04d-%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1);
    }

    @NonNull
    private static String dayKey(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(Locale.ENGLISH, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }
}
