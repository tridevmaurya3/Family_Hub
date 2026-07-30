package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyLiveStatusDao;
import com.tridev.familyhub.data.local.entity.FamilyLiveStatus;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline-first Family Live repository with lifecycle-aware Firebase listeners.
 */
public class FamilyLiveRepository {

    public interface MemberStatusListCallback {
        void onMemberStatusesLoaded(
                @NonNull List<FamilyLiveMemberData> memberStatuses
        );
    }

    public interface CloudMemberListCallback {
        void onMembersChanged(
                @NonNull List<FamilyLiveCloudMember> members
        );
    }

    public interface ErrorCallback {
        void onError(@NonNull Throwable error);
    }

    public interface StatusListCallback {
        void onStatusListLoaded(@NonNull List<FamilyLiveStatus> statusList);
    }

    public interface StatusCallback {
        void onStatusLoaded(@Nullable FamilyLiveStatus status);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final FamilyLiveStatusDao familyLiveStatusDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DatabaseReference firebaseRoot =
            FirebaseDatabase.getInstance().getReference();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, MemberProfile> cloudProfiles = new HashMap<>();
    private final Map<String, CloudLocation> cloudLocations = new HashMap<>();

    @Nullable private DatabaseReference membershipReference;
    @Nullable private DatabaseReference locationReference;
    @Nullable private ValueEventListener membershipListener;
    @Nullable private ValueEventListener locationListener;
    @Nullable private CloudMemberListCallback cloudCallback;
    private int observerGeneration;
    private boolean initialMembershipsLoaded;
    private boolean initialLocationsLoaded;

    public FamilyLiveRepository(@NonNull Context context) {
        familyLiveStatusDao = FamilyHubDatabase
                .getInstance(context)
                .familyLiveStatusDao();
    }

    public void observeCloudMembers(
            @NonNull CloudMemberListCallback callback,
            @NonNull ErrorCallback errorCallback
    ) {
        stopObservingCloudMembers();
        if (closed.get()) {
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            errorCallback.onError(new IllegalStateException("AUTH_REQUIRED"));
            return;
        }

        cloudCallback = callback;
        int generation = ++observerGeneration;
        firebaseRoot.child("users").child(user.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    if (closed.get() || generation != observerGeneration) {
                        return;
                    }
                    String familyId =
                            snapshot.child("familyId").getValue(String.class);
                    String status =
                            snapshot.child("status").getValue(String.class);
                    if (familyId == null
                            || familyId.trim().isEmpty()
                            || !"ACTIVE".equals(status)) {
                        errorCallback.onError(
                                new IllegalStateException("ACTIVE_FAMILY_REQUIRED")
                        );
                        return;
                    }
                    attachFamilyListeners(
                            familyId,
                            generation,
                            errorCallback
                    );
                })
                .addOnFailureListener(error -> {
                    if (!closed.get() && generation == observerGeneration) {
                        errorCallback.onError(error);
                    }
                });
    }

    private void attachFamilyListeners(
            @NonNull String familyId,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        membershipReference = firebaseRoot
                .child("memberships")
                .child(familyId);
        locationReference = firebaseRoot
                .child("locations")
                .child(familyId);

        membershipListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (closed.get() || generation != observerGeneration) {
                    return;
                }
                cloudProfiles.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String uid = child.child("uid").getValue(String.class);
                    String status =
                            child.child("status").getValue(String.class);
                    if (uid == null || !"ACTIVE".equals(status)) {
                        continue;
                    }
                    cloudProfiles.put(uid, new MemberProfile(
                            stringValue(child.child("displayName")),
                            stringValue(child.child("role"))
                    ));
                }
                initialMembershipsLoaded = true;
                dispatchCloudMembers();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!closed.get() && generation == observerGeneration) {
                    errorCallback.onError(error.toException());
                }
            }
        };

        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (closed.get() || generation != observerGeneration) {
                    return;
                }
                cloudLocations.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String uid = child.child("uid").getValue(String.class);
                    Double latitude =
                            child.child("latitude").getValue(Double.class);
                    Double longitude =
                            child.child("longitude").getValue(Double.class);
                    Double accuracy =
                            child.child("accuracy").getValue(Double.class);
                    String placeLabel =
                            child.child("placeLabel").getValue(String.class);
                    Long batteryValue =
                            child.child("batteryPercentage").getValue(Long.class);
                    Boolean charging =
                            child.child("charging").getValue(Boolean.class);
                    Double speed =
                            child.child("speedMetersPerSecond")
                                    .getValue(Double.class);
                    String movementType =
                            child.child("movementType").getValue(String.class);
                    Boolean sharing =
                            child.child("sharingEnabled").getValue(Boolean.class);
                    Boolean online =
                            child.child("online").getValue(Boolean.class);
                    String availabilityReason = child
                            .child("availabilityReason")
                            .getValue(String.class);
                    Long updatedAt =
                            child.child("updatedAt").getValue(Long.class);
                    if (uid == null) {
                        continue;
                    }
                    cloudLocations.put(uid, new CloudLocation(
                            latitude,
                            longitude,
                            accuracy,
                            placeLabel == null ? "" : placeLabel.trim(),
                            batteryValue == null
                                    ? -1
                                    : Math.max(-1, Math.min(
                                            100,
                                            batteryValue.intValue()
                                    )),
                            Boolean.TRUE.equals(charging),
                            speed == null ? 0D : Math.max(0D, speed),
                            movementType == null
                                    ? "UNKNOWN"
                                    : movementType,
                            Boolean.TRUE.equals(sharing),
                            Boolean.TRUE.equals(online),
                            availabilityReason == null
                                    ? ""
                                    : availabilityReason,
                            updatedAt == null ? 0L : updatedAt
                    ));
                }
                initialLocationsLoaded = true;
                dispatchCloudMembers();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!closed.get() && generation == observerGeneration) {
                    errorCallback.onError(error.toException());
                }
            }
        };

        membershipReference.addValueEventListener(membershipListener);
        locationReference.addValueEventListener(locationListener);
    }

    private void dispatchCloudMembers() {
        CloudMemberListCallback callback = cloudCallback;
        if (callback == null
                || closed.get()
                || !initialMembershipsLoaded
                || !initialLocationsLoaded) {
            return;
        }

        List<FamilyLiveCloudMember> members = new ArrayList<>();
        for (Map.Entry<String, MemberProfile> entry
                : cloudProfiles.entrySet()) {
            String uid = entry.getKey();
            MemberProfile profile = entry.getValue();
            CloudLocation location = cloudLocations.get(uid);
            boolean hasLocation = location != null
                    && location.latitude != null
                    && location.longitude != null
                    && location.accuracy != null;

            members.add(new FamilyLiveCloudMember(
                    uid,
                    profile.displayName,
                    profile.role,
                    hasLocation,
                    hasLocation ? location.latitude : 0D,
                    hasLocation ? location.longitude : 0D,
                    hasLocation ? location.accuracy : 0D,
                    location == null ? "" : location.placeLabel,
                    location == null ? -1 : location.batteryPercentage,
                    location != null && location.charging,
                    location == null ? 0D : location.speedMetersPerSecond,
                    location == null ? "UNKNOWN" : location.movementType,
                    location != null && location.sharingEnabled,
                    location != null && location.online,
                    location == null ? "" : location.availabilityReason,
                    location == null ? 0L : location.updatedAt
            ));
        }

        members.sort(Comparator.comparing(
                member -> member.displayName.toLowerCase()
        ));
        callback.onMembersChanged(members);
    }

    public void stopObservingCloudMembers() {
        observerGeneration++;
        if (membershipReference != null && membershipListener != null) {
            membershipReference.removeEventListener(membershipListener);
        }
        if (locationReference != null && locationListener != null) {
            locationReference.removeEventListener(locationListener);
        }
        membershipReference = null;
        locationReference = null;
        membershipListener = null;
        locationListener = null;
        cloudCallback = null;
        initialMembershipsLoaded = false;
        initialLocationsLoaded = false;
        cloudProfiles.clear();
        cloudLocations.clear();
    }

    public void loadMemberStatuses(
            @NonNull MemberStatusListCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyLiveMemberData> memberStatuses =
                    familyLiveStatusDao.getMemberStatuses();
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onMemberStatusesLoaded(memberStatuses);
                }
            });
        });
    }

    public void loadAll(@NonNull StatusListCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyLiveStatus> statusList = familyLiveStatusDao.getAll();
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onStatusListLoaded(statusList);
                }
            });
        });
    }

    public void loadSharingEnabled(@NonNull StatusListCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyLiveStatus> statusList =
                    familyLiveStatusDao.getSharingEnabled();
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onStatusListLoaded(statusList);
                }
            });
        });
    }

    public void loadByMemberId(
            long familyMemberId,
            @NonNull StatusCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyLiveStatus status =
                    familyLiveStatusDao.getByMemberId(familyMemberId);
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onStatusLoaded(status);
                }
            });
        });
    }

    public void save(
            @NonNull FamilyLiveStatus status,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (status.lastUpdatedAt == 0L) {
                status.lastUpdatedAt = System.currentTimeMillis();
            }
            familyLiveStatusDao.save(status);
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onComplete();
                }
            });
        });
    }

    public void updateSharingStatus(
            long familyMemberId,
            boolean enabled,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            familyLiveStatusDao.updateSharingStatus(
                    familyMemberId,
                    enabled,
                    System.currentTimeMillis()
            );
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onComplete();
                }
            });
        });
    }

    public void deleteByMemberId(
            long familyMemberId,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            familyLiveStatusDao.deleteByMemberId(familyMemberId);
            mainHandler.post(() -> {
                if (!closed.get()) {
                    callback.onComplete();
                }
            });
        });
    }

    public void close() {
        closed.set(true);
        stopObservingCloudMembers();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static final class MemberProfile {
        @NonNull final String displayName;
        @NonNull final String role;

        MemberProfile(
                @NonNull String displayName,
                @NonNull String role
        ) {
            this.displayName = displayName;
            this.role = role;
        }
    }

    private static final class CloudLocation {
        @Nullable final Double latitude;
        @Nullable final Double longitude;
        @Nullable final Double accuracy;
        @NonNull final String placeLabel;
        final int batteryPercentage;
        final boolean charging;
        final double speedMetersPerSecond;
        @NonNull final String movementType;
        final boolean sharingEnabled;
        final boolean online;
        @NonNull final String availabilityReason;
        final long updatedAt;

        CloudLocation(
                @Nullable Double latitude,
                @Nullable Double longitude,
                @Nullable Double accuracy,
                @NonNull String placeLabel,
                int batteryPercentage,
                boolean charging,
                double speedMetersPerSecond,
                @NonNull String movementType,
                boolean sharingEnabled,
                boolean online,
                @NonNull String availabilityReason,
                long updatedAt
        ) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.placeLabel = placeLabel;
            this.batteryPercentage = batteryPercentage;
            this.charging = charging;
            this.speedMetersPerSecond = speedMetersPerSecond;
            this.movementType = movementType;
            this.sharingEnabled = sharingEnabled;
            this.online = online;
            this.availabilityReason = availabilityReason;
            this.updatedAt = updatedAt;
        }
    }
}
