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
import java.util.HashSet;
import java.util.Set;
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
    private final Map<String, DatabaseReference> restrictedLocationReferences =
            new HashMap<>();
    private final Map<String, ValueEventListener> restrictedLocationListeners =
            new HashMap<>();

    @Nullable private DatabaseReference membershipReference;
    @Nullable private DatabaseReference locationReference;
    @Nullable private ValueEventListener membershipListener;
    @Nullable private ValueEventListener locationListener;
    @Nullable private DatabaseReference privacyReference;
    @Nullable private ValueEventListener privacyListener;
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
                            user.getUid(),
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
            @NonNull String viewerUid,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        membershipReference = firebaseRoot
                .child("memberships")
                .child(familyId);
        DatabaseReference viewerMembershipReference = firebaseRoot
                .child("memberships").child(familyId).child(viewerUid);

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
                    if (uid == null
                            || !uid.equals(child.getKey())
                            || !"ACTIVE".equals(status)) {
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

        viewerMembershipReference.get().addOnSuccessListener(snapshot -> {
            if (closed.get() || generation != observerGeneration) {
                return;
            }
            String uid = stringValue(snapshot.child("uid"));
            String status = stringValue(snapshot.child("status"));
            String role = stringValue(snapshot.child("role"));
            if (!viewerUid.equals(uid) || !"ACTIVE".equals(status)) {
                errorCallback.onError(new IllegalStateException(
                        "ACTIVE_FAMILY_REQUIRED"
                ));
                return;
            }
            if ("OWNER_ADMIN".equals(role) || "GUARDIAN".equals(role)) {
                attachPrivilegedLocationListener(
                        familyId, generation, errorCallback
                );
            } else {
                attachRestrictedLocationListeners(
                        familyId, viewerUid, generation, errorCallback
                );
            }
        }).addOnFailureListener(error -> {
            if (!closed.get() && generation == observerGeneration) {
                errorCallback.onError(error);
            }
        });

        membershipReference.addValueEventListener(membershipListener);
    }

    private void attachPrivilegedLocationListener(
            @NonNull String familyId,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        firebaseRoot.child("memberships").child(familyId).get()
                .addOnSuccessListener(snapshot -> {
                    if (closed.get() || generation != observerGeneration) {
                        return;
                    }
                    for (DataSnapshot member : snapshot.getChildren()) {
                        String uid = stringValue(member.child("uid"));
                        if (!uid.isEmpty()
                                && uid.equals(member.getKey())
                                && "ACTIVE".equals(stringValue(
                                member.child("status")
                        ))) {
                            attachRestrictedLocation(
                                    familyId, uid, generation, errorCallback
                            );
                        }
                    }
                    initialLocationsLoaded = true;
                    dispatchCloudMembers();
                })
                .addOnFailureListener(error -> {
                    if (!closed.get() && generation == observerGeneration) {
                        errorCallback.onError(error);
                    }
                });
    }

    private void attachRestrictedLocationListeners(
            @NonNull String familyId,
            @NonNull String viewerUid,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        privacyReference = firebaseRoot.child("journeyPrivacy")
                .child(familyId);
        privacyListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (closed.get() || generation != observerGeneration) {
                        return;
                    }
                    Set<String> allowed = new HashSet<>();
                    allowed.add(viewerUid);
                    for (DataSnapshot owner : snapshot.getChildren()) {
                        String ownerUid = owner.getKey();
                        if (ownerUid != null && Boolean.TRUE.equals(owner
                                .child("viewers").child(viewerUid)
                                .getValue(Boolean.class))) {
                            allowed.add(ownerUid);
                        }
                    }
                    reconcileRestrictedLocations(
                            familyId,
                            allowed,
                            generation,
                            errorCallback
                    );
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
        privacyReference.addValueEventListener(privacyListener);
    }

    private void reconcileRestrictedLocations(
            @NonNull String familyId,
            @NonNull Set<String> allowed,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        for (String existing : new ArrayList<>(
                restrictedLocationReferences.keySet()
        )) {
            if (allowed.contains(existing)) {
                continue;
            }
            DatabaseReference reference = restrictedLocationReferences
                    .remove(existing);
            ValueEventListener listener = restrictedLocationListeners
                    .remove(existing);
            if (reference != null && listener != null) {
                reference.removeEventListener(listener);
            }
            cloudLocations.remove(existing);
        }
        for (String targetUid : allowed) {
            attachRestrictedLocation(
                    familyId, targetUid, generation, errorCallback
            );
        }
    }

    private void attachRestrictedLocation(
            @NonNull String familyId,
            @NonNull String targetUid,
            int generation,
            @NonNull ErrorCallback errorCallback
    ) {
        if (restrictedLocationReferences.containsKey(targetUid)) {
            return;
        }
        DatabaseReference reference = firebaseRoot.child("locations")
                .child(familyId).child(targetUid);
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (closed.get() || generation != observerGeneration) {
                    return;
                }
                if (snapshot.exists()) {
                    putCloudLocation(snapshot, targetUid);
                } else {
                    cloudLocations.remove(targetUid);
                }
                dispatchCloudMembers();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!closed.get() && generation == observerGeneration) {
                    errorCallback.onError(error.toException());
                }
            }
        };
        restrictedLocationReferences.put(targetUid, reference);
        restrictedLocationListeners.put(targetUid, listener);
        reference.addValueEventListener(listener);
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
                    location == null ? 0L : location.updatedAt,
                    location == null ? "" : location.serviceState,
                    location == null ? 0L : location.serviceHeartbeatAt,
                    location == null ? 0 : location.serviceRecoveryCount,
                    location == null ? 0 : location.serviceConsecutiveMisses
            ));
        }

        members.sort(Comparator.comparing(
                member -> member.displayName.toLowerCase()
        ));
        callback.onMembersChanged(members);
    }

    private void putCloudLocation(
            @NonNull DataSnapshot snapshot,
            @Nullable String expectedUid
    ) {
        String uid = snapshot.child("uid").getValue(String.class);
        if (uid == null || expectedUid == null || !uid.equals(expectedUid)) {
            return;
        }
        Long battery = snapshot.child("batteryPercentage")
                .getValue(Long.class);
        Long locationUpdatedAt = snapshot.child("locationUpdatedAt")
                .getValue(Long.class);
        Long clientTimestamp = snapshot.child("clientTimestamp")
                .getValue(Long.class);
        Long updatedAt = snapshot.child("updatedAt").getValue(Long.class);
        cloudLocations.put(uid, new CloudLocation(
                snapshot.child("latitude").getValue(Double.class),
                snapshot.child("longitude").getValue(Double.class),
                snapshot.child("accuracy").getValue(Double.class),
                stringValue(snapshot.child("placeLabel")),
                battery == null ? -1 : Math.max(
                        -1, Math.min(100, battery.intValue())
                ),
                Boolean.TRUE.equals(snapshot.child("charging")
                        .getValue(Boolean.class)),
                Math.max(0D, doubleValue(snapshot.child(
                        "speedMetersPerSecond"
                ))),
                defaultValue(stringValue(snapshot.child("movementType")),
                        "UNKNOWN"),
                Boolean.TRUE.equals(snapshot.child("sharingEnabled")
                        .getValue(Boolean.class)),
                Boolean.TRUE.equals(snapshot.child("online")
                        .getValue(Boolean.class)),
                stringValue(snapshot.child("availabilityReason")),
                firstPositiveTimestamp(
                        locationUpdatedAt, clientTimestamp, updatedAt
                ),
                stringValue(snapshot.child("serviceState")),
                longValue(snapshot.child("serviceHeartbeatAt")),
                safeNonNegativeInt(snapshot.child("serviceRecoveryCount")
                        .getValue(Long.class)),
                safeNonNegativeInt(snapshot.child("serviceConsecutiveMisses")
                        .getValue(Long.class))
        ));
    }

    public void stopObservingCloudMembers() {
        observerGeneration++;
        if (membershipReference != null && membershipListener != null) {
            membershipReference.removeEventListener(membershipListener);
        }
        if (locationReference != null && locationListener != null) {
            locationReference.removeEventListener(locationListener);
        }
        if (privacyReference != null && privacyListener != null) {
            privacyReference.removeEventListener(privacyListener);
        }
        for (Map.Entry<String, DatabaseReference> entry
                : restrictedLocationReferences.entrySet()) {
            ValueEventListener listener = restrictedLocationListeners.get(
                    entry.getKey()
            );
            if (listener != null) {
                entry.getValue().removeEventListener(listener);
            }
        }
        restrictedLocationReferences.clear();
        restrictedLocationListeners.clear();
        membershipReference = null;
        locationReference = null;
        membershipListener = null;
        locationListener = null;
        privacyReference = null;
        privacyListener = null;
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

    private static int safeNonNegativeInt(@Nullable Long value) {
        if (value == null || value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : value.intValue();
    }

    private static long longValue(@NonNull DataSnapshot snapshot) {
        Long value = snapshot.getValue(Long.class);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static double doubleValue(@NonNull DataSnapshot snapshot) {
        Double value = snapshot.getValue(Double.class);
        return value == null ? 0D : value;
    }

    @NonNull
    private static String defaultValue(
            @NonNull String value,
            @NonNull String fallback
    ) {
        return value.isEmpty() ? fallback : value;
    }

    private static long firstPositiveTimestamp(
            @Nullable Long preferred,
            @Nullable Long fallback,
            @Nullable Long legacyFallback
    ) {
        if (preferred != null && preferred > 0L) {
            return preferred;
        }
        if (fallback != null && fallback > 0L) {
            return fallback;
        }
        return legacyFallback == null ? 0L : Math.max(0L, legacyFallback);
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
        @NonNull final String serviceState;
        final long serviceHeartbeatAt;
        final int serviceRecoveryCount;
        final int serviceConsecutiveMisses;

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
                long updatedAt,
                @NonNull String serviceState,
                long serviceHeartbeatAt,
                int serviceRecoveryCount,
                int serviceConsecutiveMisses
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
            this.serviceState = serviceState;
            this.serviceHeartbeatAt = serviceHeartbeatAt;
            this.serviceRecoveryCount = serviceRecoveryCount;
            this.serviceConsecutiveMisses = serviceConsecutiveMisses;
        }
    }
}
