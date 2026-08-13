package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.database.DataSnapshot;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.VehicleDao;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.VehicleWithOwner;
import com.tridev.familyhub.feature.vehicle.VehicleReminderScheduler;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    public interface DocumentsCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntry> documents);
    }

    public interface ResultCallback {
        void onComplete(boolean successful);
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final VehicleDao vehicleDao;
    private final DocumentDao documentDao;
    private final FamilyMemberDao familyMemberDao;
    private final HealthRepository authorisedMemberSource;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context appContext;
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public VehicleRepository(@NonNull Context context) {
        appContext = context.getApplicationContext();
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        vehicleDao = database.vehicleDao();
        documentDao = database.documentDao();
        familyMemberDao = database.familyMemberDao();
        authorisedMemberSource = new HealthRepository(context);
    }

    public void startRealtimeSync(@NonNull Runnable onChanged) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("vehicles",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                                                    @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, onChanged);
                    }
                    @Override public void onRemoved(@NonNull String familyId,
                                                    @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            Vehicle local = vehicleDao.getByCloudId(cloudId);
                            if (local == null || !local.isShared) return;
                            vehicleDao.delete(local);
                            mainHandler.post(onChanged);
                        });
                    }
                });
        subscriber.start();
    }

    public void stopRealtimeSync() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
    }

    public void loadDocuments(@NonNull DocumentsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<DocumentEntry> documents = documentDao.getAll();
            mainHandler.post(() -> callback.onDocumentsLoaded(documents));
        });
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
        authorisedMemberSource.loadMembers(callback::onMembersLoaded);
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
                vehicle.updatedAt = System.currentTimeMillis();
                if (vehicle.id == 0L) {
                    vehicle.id = vehicleDao.insert(vehicle);
                } else {
                    vehicleDao.update(vehicle);
                }
                if (vehicle.isShared) publish(vehicle);
                else {
                    String familyId = vehicle.familyId;
                    String cloudId = vehicle.cloudId;
                    vehicle.familyId = "";
                    vehicle.cloudId = "";
                    vehicle.updatedByUid = "";
                    vehicleDao.update(vehicle);
                    FamilyCollaborationPublisher.remove("vehicles", familyId, cloudId);
                }
                VehicleReminderScheduler.sync(appContext, vehicle);
            } catch (RuntimeException exception) {
                successful = false;
            }
            boolean result = successful;
            mainHandler.post(() -> callback.onComplete(result));
        });
    }

    private void publish(@NonNull Vehicle vehicle) {
        Map<String, Object> values = new HashMap<>();
        values.put("ownerName", vehicle.assignedOwnerName);
        values.put("vehicleType", vehicle.vehicleType);
        values.put("displayName", vehicle.displayName);
        values.put("registrationNumber", vehicle.registrationNumber);
        values.put("manufacturer", vehicle.manufacturer);
        values.put("model", vehicle.model);
        values.put("fuelType", vehicle.fuelType);
        values.put("manufactureYear", vehicle.manufactureYear);
        values.put("insuranceExpiryAt", vehicle.insuranceExpiryAt);
        values.put("pollutionExpiryAt", vehicle.pollutionExpiryAt);
        values.put("serviceDueAt", vehicle.serviceDueAt);
        values.put("notes", vehicle.notes);
        values.put("linkedDocumentTitle", vehicle.linkedDocumentTitle);
        values.put("timelineNote", vehicle.timelineNote);
        values.put("shared", true);
        values.put("createdAt", vehicle.createdAt);
        FamilyCollaborationPublisher.publish("vehicles", vehicle.cloudId, values,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    vehicle.cloudId = cloudId;
                    vehicle.familyId = familyId;
                    vehicle.updatedByUid = uid;
                    vehicleDao.update(vehicle);
                }));
    }

    private void mergeRemote(@NonNull String familyId,
                             @NonNull DataSnapshot snapshot,
                             @NonNull Runnable onChanged) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId = text(snapshot, "cloudId");
            if (cloudId.isEmpty()) return;
            long updatedAt = number(snapshot, "updatedAt");
            Vehicle vehicle = vehicleDao.getByCloudId(cloudId);
            if (vehicle != null && vehicle.updatedAt > updatedAt) return;
            boolean insert = vehicle == null;
            if (insert) vehicle = new Vehicle();
            FamilyMember owner = familyMemberDao.getByName(text(snapshot, "ownerName"));
            if (owner == null) return;
            vehicle.cloudId = cloudId; vehicle.familyId = familyId;
            vehicle.ownerMemberId = owner.id; vehicle.assignedOwnerName = owner.name;
            vehicle.vehicleType = fallback(text(snapshot, "vehicleType"), Vehicle.TYPE_OTHER);
            vehicle.displayName = text(snapshot, "displayName");
            vehicle.registrationNumber = text(snapshot, "registrationNumber");
            vehicle.manufacturer = text(snapshot, "manufacturer");
            vehicle.model = text(snapshot, "model");
            vehicle.fuelType = text(snapshot, "fuelType");
            vehicle.manufactureYear = (int) number(snapshot, "manufactureYear");
            vehicle.insuranceExpiryAt = number(snapshot, "insuranceExpiryAt");
            vehicle.pollutionExpiryAt = number(snapshot, "pollutionExpiryAt");
            vehicle.serviceDueAt = number(snapshot, "serviceDueAt");
            vehicle.notes = text(snapshot, "notes");
            vehicle.linkedDocumentTitle = text(snapshot, "linkedDocumentTitle");
            vehicle.timelineNote = text(snapshot, "timelineNote");
            vehicle.isShared = true; vehicle.updatedAt = updatedAt;
            vehicle.updatedByUid = text(snapshot, "updatedByUid");
            vehicle.createdAt = number(snapshot, "createdAt");
            if (vehicle.createdAt == 0L) vehicle.createdAt = updatedAt;
            try {
                if (insert) vehicle.id = vehicleDao.insert(vehicle);
                else vehicleDao.update(vehicle);
                VehicleReminderScheduler.sync(appContext, vehicle);
                mainHandler.post(onChanged);
            } catch (RuntimeException ignored) { }
        });
    }

    @NonNull private static String text(DataSnapshot snapshot, String key) {
        String value = snapshot.child(key).getValue(String.class);
        return value == null ? "" : value;
    }
    private static long number(DataSnapshot snapshot, String key) {
        Number value = snapshot.child(key).getValue(Number.class);
        return value == null ? 0L : value.longValue();
    }
    @NonNull private static String fallback(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    public void delete(
            @NonNull Vehicle vehicle,
            @NonNull ResultCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean successful = true;
            try {
                FamilyCollaborationPublisher.remove("vehicles", vehicle.familyId, vehicle.cloudId);
                VehicleReminderScheduler.cancelAll(appContext, vehicle.id);
                vehicleDao.delete(vehicle);
            } catch (RuntimeException exception) {
                successful = false;
            }
            boolean result = successful;
            mainHandler.post(() -> callback.onComplete(result));
        });
    }
}
