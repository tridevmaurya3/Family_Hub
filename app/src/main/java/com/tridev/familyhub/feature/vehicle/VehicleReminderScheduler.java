package com.tridev.familyhub.feature.vehicle;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.tridev.familyhub.data.local.entity.Vehicle;
import java.util.concurrent.TimeUnit;

/** Schedules deduplicated reminders seven days before vehicle due dates. */
public final class VehicleReminderScheduler {
    private static final long ADVANCE = TimeUnit.DAYS.toMillis(7);
    private VehicleReminderScheduler() { }

    public static void sync(@NonNull Context context, @NonNull Vehicle vehicle) {
        cancelAll(context, vehicle.id);
        schedule(context, vehicle, "Insurance", vehicle.insuranceExpiryAt);
        schedule(context, vehicle, "Pollution certificate", vehicle.pollutionExpiryAt);
        schedule(context, vehicle, "Service", vehicle.serviceDueAt);
    }

    private static void schedule(@NonNull Context context,
                                 @NonNull Vehicle vehicle,
                                 @NonNull String dueType, long dueAt) {
        long delay = dueAt - ADVANCE - System.currentTimeMillis();
        if (vehicle.id <= 0L || dueAt <= 0L || delay < 0L) return;
        Data data = new Data.Builder()
                .putLong(VehicleReminderWorker.KEY_VEHICLE_ID, vehicle.id)
                .putString(VehicleReminderWorker.KEY_VEHICLE, vehicle.displayName)
                .putString(VehicleReminderWorker.KEY_DUE_TYPE, dueType)
                .putLong(VehicleReminderWorker.KEY_DUE_AT, dueAt).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                VehicleReminderWorker.class).setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(name(vehicle.id, dueType),
                        ExistingWorkPolicy.REPLACE, request);
    }

    public static void cancelAll(@NonNull Context context, long vehicleId) {
        if (vehicleId <= 0L) return;
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(name(vehicleId, "Insurance"));
        manager.cancelUniqueWork(name(vehicleId, "Pollution certificate"));
        manager.cancelUniqueWork(name(vehicleId, "Service"));
    }

    @NonNull private static String name(long id, @NonNull String type) {
        return "vehicle_due_" + id + "_" + type.replace(' ', '_');
    }
}
