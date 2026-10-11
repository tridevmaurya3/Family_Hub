package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.room.InvalidationTracker;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.core.planner.PlannerScheduler;
import com.tridev.familyhub.core.reminders.ReminderScheduler;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.*;
import java.util.Set;

/** Keeps visible dashboard summaries current without first opening each module. */
public final class DashboardRealtimeSync {
    private final Context context;
    private final String uid = AccountDataScope.uid();
    private final FamilyHubDatabase database;
    private final GroceryRepository grocery;
    private final NotesRepository notes;
    private final PlannerRepository planner;
    private final ReminderRepository reminders;
    private final FinanceRepository finance;
    private final HealthRepository health;
    private final VehicleRepository vehicles;
    private final PropertyRepository properties;
    private final FamilyMemberRepository members;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;
    private Runnable changed;
    private final Runnable refresh = () -> { if (active && uid.equals(AccountDataScope.uid()) && changed != null) changed.run(); };
    private final InvalidationTracker.Observer observer = new InvalidationTracker.Observer(
            "grocery_items", "notes", "planner_items", "reminders", "finance_entries", "health_records", "vehicles", "properties", "documents", "family_members") {
        @Override public void onInvalidated(@NonNull Set<String> tables) {
            handler.post(() -> { handler.removeCallbacks(refresh); if (active) handler.postDelayed(refresh, 400L); });
        }
    };

    public DashboardRealtimeSync(Context context) {
        this.context = context.getApplicationContext();
        database = FamilyHubDatabase.getInstance(this.context);
        grocery = new GroceryRepository(this.context); notes = new NotesRepository(this.context);
        planner = new PlannerRepository(this.context); reminders = new ReminderRepository(this.context);
        finance = new FinanceRepository(this.context); health = new HealthRepository(this.context);
        vehicles = new VehicleRepository(this.context); properties = new PropertyRepository(this.context); members = new FamilyMemberRepository(this.context);
    }

    public void start(Runnable changed) {
        stop();
        if (uid.isEmpty() || !uid.equals(AccountDataScope.uid())) return;
        this.changed = changed; active = true;
        database.getInvalidationTracker().addObserver(observer);
        members.startRealtimeSync(() -> { if (current()) refresh.run(); });
        grocery.startRealtimeSync(() -> { }); notes.startRealtimeSync(() -> { }); finance.startSharedSync(() -> { });
        health.startRealtimeSync(new HealthRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull HealthRecord record) { }
            @Override public void onRemoved(long localId) { }
        });
        vehicles.startRealtimeSync(() -> { }); properties.startRealtimeSync(() -> { });
        planner.startRealtimeSync(new PlannerRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull PlannerItem item) {
                if (!current()) return;
                if (item.isReminderEnabled && !item.isCompleted) PlannerScheduler.schedule(context,item); else PlannerScheduler.cancel(context,item.id);
            }
            @Override public void onRemoved(long localId) { if (current()) PlannerScheduler.cancel(context,localId); }
        });
        reminders.startRealtimeSync(new ReminderRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull Reminder reminder) {
                if (!current()) return;
                if (reminder.isEnabled) ReminderScheduler.schedule(context,reminder); else ReminderScheduler.cancel(context,reminder.id);
            }
            @Override public void onRemoved(long localId) { if (current()) ReminderScheduler.cancel(context,localId); }
        });
    }

    private boolean current() { return active && uid.equals(AccountDataScope.uid()); }
    public void stop() {
        active = false; changed = null;
        database.getInvalidationTracker().removeObserver(observer); handler.removeCallbacksAndMessages(null);
        grocery.stopRealtimeSync(); notes.stopRealtimeSync(); planner.stopRealtimeSync(); reminders.stopRealtimeSync();
        finance.stopSharedSync(); health.stopRealtimeSync(); vehicles.stopRealtimeSync(); properties.stopRealtimeSync(); members.stopRealtimeSync();
    }
}
