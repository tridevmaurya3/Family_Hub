package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.VehicleDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.VehicleWithOwner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for private family vehicle profiles. */
public class VehicleRepository {

    public interface VehiclesCallback {
        void onVehiclesLoaded(@NonNull List<VehicleWithOwner> vehicles);
    }

    public interface MembersCallback {
        void onMembersLoaded(@NonNull List<FamilyMember> members);
    }

    public interface ResultCallback {
        void onComplete(boolean successful);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final VehicleDao vehicleDao;
    private final FamilyMemberDao familyMemberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VehicleRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        vehicleDao = database.vehicleDao();
        familyMemberDao = database.familyMemberDao();
    }

    public void loadVehicles(
            @NonNull String query,
            @NonNull VehiclesCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<VehicleWithOwner> vehicles = trimmedQuery.isEmpty()
                    ? vehicleDao.getAllWithOwner()
                    : vehicleDao.searchWithOwner(trimmedQuery);
            mainHandler.post(() -> callback.onVehiclesLoaded(vehicles));
        });
    }

    public void loadMembers(@NonNull MembersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = familyMemberDao.getAll();
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
    }

    public void save(
            @NonNull Vehicle vehicle,
            @NonNull ResultCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean successful = true;
            try {
                if (vehicle.createdAt == 0L) {
                    vehicle.createdAt = System.currentTimeMillis();
                }
                if (vehicle.id == 0L) {
                    vehicle.id = vehicleDao.insert(vehicle);
                } else {
                    vehicleDao.update(vehicle);
                }
            } catch (RuntimeException exception) {
                successful = false;
            }
            boolean result = successful;
            mainHandler.post(() -> callback.onComplete(result));
        });
    }

    public void delete(
            @NonNull Vehicle vehicle,
            @NonNull ResultCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean successful = true;
            try {
                vehicleDao.delete(vehicle);
            } catch (RuntimeException exception) {
                successful = false;
            }
            boolean result = successful;
            mainHandler.post(() -> callback.onComplete(result));
        });
    }
}
