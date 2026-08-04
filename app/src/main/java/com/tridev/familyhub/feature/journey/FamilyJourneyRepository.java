package com.tridev.familyhub.feature.journey;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.feature.automation.FirebaseNumericValueReader;
import com.tridev.familyhub.feature.insights.FamilyReportRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated Firebase access for Journey History and trusted viewers. */
public final class FamilyJourneyRepository {

    public static final class Session {
        @NonNull public final String uid;
        @NonNull public final String familyId;
        @NonNull public final String displayName;

        Session(
                @NonNull String uid,
                @NonNull String familyId,
                @NonNull String displayName
        ) {
            this.uid = uid;
            this.familyId = familyId;
            this.displayName = displayName;
        }
    }

    public static final class Member {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String role;
        public final boolean self;
        public final boolean accessible;

        Member(
                @NonNull String uid,
                @NonNull String displayName,
                @NonNull String role,
                boolean self,
                boolean accessible
        ) {
            this.uid = uid;
            this.displayName = displayName;
            this.role = role;
            this.self = self;
            this.accessible = accessible;
        }

        @NonNull
        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class PrivacySettings {
        public final boolean historyEnabled;
        public final int retentionDays;
        @NonNull public final Map<String, Boolean> viewers;

        PrivacySettings(
                boolean historyEnabled,
                int retentionDays,
                @NonNull Map<String, Boolean> viewers
        ) {
            this.historyEnabled = historyEnabled;
            this.retentionDays = retentionDays;
            this.viewers = viewers;
        }
    }

    public interface OverviewCallback {
        void onLoaded(
                @NonNull Session session,
                @NonNull List<Member> allMembers,
                @NonNull List<Member> accessibleMembers,
                @NonNull PrivacySettings ownSettings
        );

        void onError(@NonNull String reason);
    }

    public interface PointsCallback {
        void onLoaded(@NonNull List<FamilyJourneyPoint> points);

        void onError(@NonNull String reason);
    }

    public interface RangeCallback {
        void onLoaded(
                @NonNull Session session,
                @NonNull List<Member> members,
                @NonNull Map<String, List<FamilyJourneyPoint>> pointsByMember
        );

        void onError(@NonNull String reason);
    }

    public interface ActionCallback {
        void onSuccess();

        void onError(@NonNull String reason);
    }

    private final DatabaseReference root = FirebaseDatabase
            .getInstance()
            .getReference();

    @Nullable private Session cachedSession;

    public void loadOverview(@NonNull OverviewCallback callback) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                root.child("memberships").child(session.familyId).get()
                        .addOnSuccessListener(memberships ->
                                root.child("journeyPrivacy")
                                        .child(session.familyId)
                                        .get()
                                        .addOnSuccessListener(privacy -> {
                                            try {
                                                buildOverview(
                                                        session,
                                                        memberships,
                                                        privacy,
                                                        callback
                                                );
                                            } catch (DatabaseException error) {
                                                callback.onError(
                                                        "PRIVACY_DATA_INVALID"
                                                );
                                            } catch (RuntimeException error) {
                                                callback.onError(
                                                        "OVERVIEW_DATA_INVALID"
                                                );
                                            }
                                        })
                                        .addOnFailureListener(error ->
                                                callback.onError(
                                                        "PRIVACY_LOAD_FAILED"
                                                )))
                        .addOnFailureListener(error ->
                                callback.onError("MEMBERS_LOAD_FAILED"));
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    private void buildOverview(
            @NonNull Session session,
            @NonNull DataSnapshot memberships,
            @NonNull DataSnapshot privacy,
            @NonNull OverviewCallback callback
    ) {
        List<Member> all = new ArrayList<>();
        List<Member> accessible = new ArrayList<>();
        for (DataSnapshot child : memberships.getChildren()) {
            String memberUid = stringValue(child.child("uid"));
            if (memberUid.isEmpty() && child.getKey() != null) {
                memberUid = child.getKey();
            }
            String status = stringValue(child.child("status"));
            if (memberUid.isEmpty() || !"ACTIVE".equals(status)) {
                continue;
            }
            String name = stringValue(child.child("displayName"));
            if (name.isEmpty()) {
                name = memberUid;
            }
            String role = stringValue(child.child("role"));
            boolean self = session.uid.equals(memberUid);
            boolean granted = Boolean.TRUE.equals(
                    privacy.child(memberUid)
                            .child("viewers")
                            .child(session.uid)
                            .getValue(Boolean.class)
            );
            Member member = new Member(
                    memberUid,
                    name,
                    role,
                    self,
                    self || granted
            );
            all.add(member);
            if (member.accessible) {
                accessible.add(member);
            }
        }
        Comparator<Member> comparator = Comparator.comparing(
                member -> member.displayName.toLowerCase()
        );
        all.sort(comparator);
        accessible.sort((first, second) -> {
            if (first.self != second.self) {
                return first.self ? -1 : 1;
            }
            return comparator.compare(first, second);
        });

        DataSnapshot own = privacy.child(session.uid);
        Map<String, Boolean> viewers = new LinkedHashMap<>();
        for (DataSnapshot viewer : own.child("viewers").getChildren()) {
            String viewerUid = viewer.getKey();
            if (viewerUid != null
                    && Boolean.TRUE.equals(viewer.getValue(Boolean.class))) {
                viewers.put(viewerUid, true);
            }
        }
        PrivacySettings settings = new PrivacySettings(
                booleanValue(own.child("historyEnabled"), false),
                FamilyJourneyPolicy.normalizeRetentionDays(
                        FirebaseNumericValueReader.intValue(
                                own.child("retentionDays"),
                                FamilyJourneyPolicy.DEFAULT_RETENTION_DAYS
                        )
                ),
                viewers
        );
        callback.onLoaded(session, all, accessible, settings);
    }

    public void loadDay(
            @NonNull String memberUid,
            @NonNull String dayKey,
            @NonNull PointsCallback callback
    ) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                root.child("locationHistory")
                        .child(session.familyId)
                        .child(memberUid)
                        .child(dayKey)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(
                                    @NonNull DataSnapshot snapshot
                            ) {
                                callback.onLoaded(parsePoints(snapshot));
                            }

                            @Override
                            public void onCancelled(
                                    @NonNull DatabaseError error
                            ) {
                                callback.onError(
                                        error.getCode()
                                                == DatabaseError.PERMISSION_DENIED
                                                ? "HISTORY_ACCESS_DENIED"
                                                : "HISTORY_LOAD_FAILED"
                                );
                            }
                        });
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    /**
     * Loads at most ninety selected day folders for currently accessible
     * members. The access list is revalidated immediately before loading.
     */
    public void loadRange(
            @NonNull List<Member> requestedMembers,
            @NonNull FamilyReportRange range,
            @NonNull RangeCallback callback
    ) {
        loadOverview(new OverviewCallback() {
            @Override
            public void onLoaded(
                    @NonNull Session session,
                    @NonNull List<Member> allMembers,
                    @NonNull List<Member> accessibleMembers,
                    @NonNull PrivacySettings ownSettings
            ) {
                Map<String, Member> allowed = new LinkedHashMap<>();
                for (Member member : accessibleMembers) {
                    allowed.put(member.uid, member);
                }
                List<Member> selected = new ArrayList<>();
                Set<String> duplicateGuard = new HashSet<>();
                for (Member requested : requestedMembers) {
                    Member current = allowed.get(requested.uid);
                    if (current != null && duplicateGuard.add(current.uid)) {
                        selected.add(current);
                    }
                }
                if (selected.isEmpty()) {
                    callback.onLoaded(
                            session,
                            Collections.emptyList(),
                            Collections.emptyMap()
                    );
                    return;
                }
                loadMemberRanges(session, selected, range, callback);
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    private void loadMemberRanges(
            @NonNull Session session,
            @NonNull List<Member> members,
            @NonNull FamilyReportRange range,
            @NonNull RangeCallback callback
    ) {
        Set<String> dayKeys = new HashSet<>(range.dayKeys());
        Map<String, List<FamilyJourneyPoint>> result =
                Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicInteger remaining = new AtomicInteger(members.size());
        AtomicBoolean completed = new AtomicBoolean(false);

        for (Member member : members) {
            root.child("locationHistory")
                    .child(session.familyId)
                    .child(member.uid)
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        List<FamilyJourneyPoint> points = new ArrayList<>();
                        for (DataSnapshot day : snapshot.getChildren()) {
                            String key = day.getKey();
                            if (key != null && dayKeys.contains(key)) {
                                points.addAll(parsePoints(day));
                            }
                        }
                        points.sort(Comparator.comparingLong(
                                point -> point.capturedAt
                        ));
                        result.put(member.uid, points);
                        if (remaining.decrementAndGet() == 0
                                && completed.compareAndSet(false, true)) {
                            callback.onLoaded(session, members,
                                    new LinkedHashMap<>(result));
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (completed.compareAndSet(false, true)) {
                            callback.onError("REPORT_ACCESS_DENIED");
                        }
                    });
        }
    }

    @NonNull
    private List<FamilyJourneyPoint> parsePoints(
            @NonNull DataSnapshot snapshot
    ) {
        List<FamilyJourneyPoint> points = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (DataSnapshot child : snapshot.getChildren()) {
            try {
                FamilyJourneyPoint point = child.getValue(
                        FamilyJourneyPoint.class
                );
                if (point == null) {
                    continue;
                }
                if (point.pointId == null) {
                    point.pointId = "";
                }
                if (point.pointId.trim().isEmpty()
                        && child.getKey() != null) {
                    point.pointId = child.getKey();
                }
                if (FamilyJourneyPolicy.validStoredPoint(
                        point.latitude,
                        point.longitude,
                        point.accuracy,
                        point.capturedAt,
                        now
                )) {
                    points.add(point);
                }
            } catch (DatabaseException ignored) {
                // A single legacy/corrupt point must not block the whole day.
            } catch (RuntimeException ignored) {
                // Continue rendering other valid points.
            }
        }
        points.sort(Comparator.comparingLong(point -> point.capturedAt));
        return points;
    }

    public void saveOwnPrivacy(
            boolean historyEnabled,
            int retentionDays,
            @NonNull Map<String, Boolean> selectedViewers,
            @NonNull ActionCallback callback
    ) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("ownerUid", session.uid);
                values.put("historyEnabled", historyEnabled);
                values.put(
                        "retentionDays",
                        FamilyJourneyPolicy.normalizeRetentionDays(
                                retentionDays
                        )
                );
                Map<String, Boolean> viewers = new LinkedHashMap<>();
                for (Map.Entry<String, Boolean> entry
                        : selectedViewers.entrySet()) {
                    if (!session.uid.equals(entry.getKey())
                            && Boolean.TRUE.equals(entry.getValue())) {
                        viewers.put(entry.getKey(), true);
                    }
                }
                values.put("viewers", viewers);
                values.put("updatedAt", ServerValue.TIMESTAMP);
                root.child("journeyPrivacy")
                        .child(session.familyId)
                        .child(session.uid)
                        .setValue(values)
                        .addOnSuccessListener(unused -> callback.onSuccess())
                        .addOnFailureListener(error ->
                                callback.onError("PRIVACY_SAVE_FAILED"));
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    public void deleteOwnDay(
            @NonNull String dayKey,
            @NonNull ActionCallback callback
    ) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                root.child("locationHistory")
                        .child(session.familyId)
                        .child(session.uid)
                        .child(dayKey)
                        .removeValue()
                        .addOnSuccessListener(unused -> callback.onSuccess())
                        .addOnFailureListener(error ->
                                callback.onError("DELETE_FAILED"));
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    public void deleteAllOwnHistory(@NonNull ActionCallback callback) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                root.child("locationHistory")
                        .child(session.familyId)
                        .child(session.uid)
                        .removeValue()
                        .addOnSuccessListener(unused -> callback.onSuccess())
                        .addOnFailureListener(error ->
                                callback.onError("DELETE_FAILED"));
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    private void resolveSession(@NonNull SessionCallback callback) {
        if (cachedSession != null) {
            callback.onReady(cachedSession);
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            callback.onError("AUTH_REQUIRED");
            return;
        }
        root.child("users").child(user.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    String familyId = stringValue(snapshot.child("familyId"));
                    String status = stringValue(snapshot.child("status"));
                    if (familyId.isEmpty() || !"ACTIVE".equals(status)) {
                        callback.onError("ACTIVE_FAMILY_REQUIRED");
                        return;
                    }
                    root.child("memberships")
                            .child(familyId)
                            .child(user.getUid())
                            .get()
                            .addOnSuccessListener(membership -> {
                                if (!"ACTIVE".equals(stringValue(
                                        membership.child("status")
                                ))) {
                                    callback.onError(
                                            "ACTIVE_FAMILY_REQUIRED"
                                    );
                                    return;
                                }
                                String name = stringValue(
                                        membership.child("displayName")
                                );
                                if (name.isEmpty()) {
                                    name = user.getDisplayName();
                                }
                                if (name == null || name.trim().isEmpty()) {
                                    name = user.getEmail();
                                }
                                if (name == null || name.trim().isEmpty()) {
                                    name = user.getUid();
                                }
                                cachedSession = new Session(
                                        user.getUid(),
                                        familyId,
                                        name.trim()
                                );
                                callback.onReady(cachedSession);
                            })
                            .addOnFailureListener(error ->
                                    callback.onError(
                                            "MEMBERSHIP_LOAD_FAILED"
                                    ));
                })
                .addOnFailureListener(error ->
                        callback.onError("SESSION_LOAD_FAILED"));
    }

    private interface SessionCallback {
        void onReady(@NonNull Session session);

        void onError(@NonNull String reason);
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }
}
