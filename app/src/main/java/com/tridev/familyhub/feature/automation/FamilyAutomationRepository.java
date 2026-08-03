package com.tridev.familyhub.feature.automation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

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

/** Authenticated Firebase access for automation rules and event history. */
public final class FamilyAutomationRepository {

    public static final class Session {
        @NonNull public final String uid;
        @NonNull public final String familyId;
        @NonNull public final String displayName;
        @NonNull public final String role;

        Session(
                @NonNull String uid,
                @NonNull String familyId,
                @NonNull String displayName,
                @NonNull String role
        ) {
            this.uid = uid;
            this.familyId = familyId;
            this.displayName = displayName;
            this.role = role;
        }

        public boolean canManageFamilyRules() {
            return "OWNER_ADMIN".equals(role) || "GUARDIAN".equals(role);
        }
    }

    public static final class Member {
        @NonNull public final String uid;
        @NonNull public final String displayName;
        @NonNull public final String role;
        public final boolean self;
        public final boolean visible;
        public final boolean manageable;

        Member(
                @NonNull String uid,
                @NonNull String displayName,
                @NonNull String role,
                boolean self,
                boolean visible,
                boolean manageable
        ) {
            this.uid = uid;
            this.displayName = displayName;
            this.role = role;
            this.self = self;
            this.visible = visible;
            this.manageable = manageable;
        }

        @NonNull
        @Override
        public String toString() {
            return displayName;
        }
    }

    public interface OverviewCallback {
        void onLoaded(
                @NonNull Session session,
                @NonNull List<Member> allMembers,
                @NonNull List<Member> visibleMembers,
                @NonNull List<Member> manageableMembers,
                @NonNull List<FamilyAutomationRule> rules,
                @NonNull List<FamilyAutomationEvent> events
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
                                            MemberLists lists = buildMembers(
                                                    session,
                                                    memberships,
                                                    privacy
                                            );
                                            loadBranches(
                                                    session,
                                                    lists,
                                                    callback
                                            );
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

    @NonNull
    private MemberLists buildMembers(
            @NonNull Session session,
            @NonNull DataSnapshot memberships,
            @NonNull DataSnapshot privacy
    ) {
        List<Member> all = new ArrayList<>();
        List<Member> visible = new ArrayList<>();
        List<Member> manageable = new ArrayList<>();
        for (DataSnapshot child : memberships.getChildren()) {
            String uid = stringValue(child.child("uid"));
            String status = stringValue(child.child("status"));
            if (uid.isEmpty() || !"ACTIVE".equals(status)) {
                continue;
            }
            String name = stringValue(child.child("displayName"));
            if (name.isEmpty()) {
                name = uid;
            }
            String role = stringValue(child.child("role"));
            boolean self = session.uid.equals(uid);
            boolean trusted = Boolean.TRUE.equals(
                    privacy.child(uid)
                            .child("viewers")
                            .child(session.uid)
                            .getValue(Boolean.class)
            );
            boolean canManage = self || session.canManageFamilyRules();
            boolean canSee = canManage || trusted;
            Member member = new Member(
                    uid,
                    name,
                    role,
                    self,
                    canSee,
                    canManage
            );
            all.add(member);
            if (canSee) {
                visible.add(member);
            }
            if (canManage) {
                manageable.add(member);
            }
        }
        Comparator<Member> comparator = Comparator.comparing(
                value -> value.displayName.toLowerCase()
        );
        all.sort(comparator);
        visible.sort((first, second) -> first.self != second.self
                ? (first.self ? -1 : 1)
                : comparator.compare(first, second));
        manageable.sort((first, second) -> first.self != second.self
                ? (first.self ? -1 : 1)
                : comparator.compare(first, second));
        return new MemberLists(all, visible, manageable);
    }

    private void loadBranches(
            @NonNull Session session,
            @NonNull MemberLists lists,
            @NonNull OverviewCallback callback
    ) {
        if (lists.visible.isEmpty()) {
            callback.onLoaded(
                    session,
                    lists.all,
                    lists.visible,
                    lists.manageable,
                    Collections.emptyList(),
                    Collections.emptyList()
            );
            return;
        }

        List<FamilyAutomationRule> rules =
                Collections.synchronizedList(new ArrayList<>());
        List<FamilyAutomationEvent> events =
                Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remaining = new AtomicInteger(lists.visible.size() * 2);
        AtomicBoolean completed = new AtomicBoolean(false);

        for (Member member : lists.visible) {
            root.child("familyAutomationRules")
                    .child(session.familyId)
                    .child(member.uid)
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            FamilyAutomationRule rule = child.getValue(
                                    FamilyAutomationRule.class
                            );
                            if (rule == null) {
                                continue;
                            }
                            if ((rule.ruleId == null
                                    || rule.ruleId.trim().isEmpty())
                                    && child.getKey() != null) {
                                rule.ruleId = child.getKey();
                            }
                            if (FamilyAutomationPolicy.validRule(rule)) {
                                rules.add(rule);
                            }
                        }
                        finishBranch(
                                session,
                                lists,
                                rules,
                                events,
                                remaining,
                                completed,
                                callback
                        );
                    })
                    .addOnFailureListener(error -> failOnce(
                            completed,
                            callback,
                            "RULE_ACCESS_DENIED"
                    ));

            root.child("familyAutomationEvents")
                    .child(session.familyId)
                    .child(member.uid)
                    .limitToLast(60)
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            FamilyAutomationEvent event = child.getValue(
                                    FamilyAutomationEvent.class
                            );
                            if (event == null) {
                                continue;
                            }
                            if ((event.eventId == null
                                    || event.eventId.trim().isEmpty())
                                    && child.getKey() != null) {
                                event.eventId = child.getKey();
                            }
                            if (!event.eventId.trim().isEmpty()) {
                                events.add(event);
                            }
                        }
                        finishBranch(
                                session,
                                lists,
                                rules,
                                events,
                                remaining,
                                completed,
                                callback
                        );
                    })
                    .addOnFailureListener(error -> failOnce(
                            completed,
                            callback,
                            "EVENT_ACCESS_DENIED"
                    ));
        }
    }

    private void finishBranch(
            @NonNull Session session,
            @NonNull MemberLists lists,
            @NonNull List<FamilyAutomationRule> rules,
            @NonNull List<FamilyAutomationEvent> events,
            @NonNull AtomicInteger remaining,
            @NonNull AtomicBoolean completed,
            @NonNull OverviewCallback callback
    ) {
        if (remaining.decrementAndGet() != 0
                || !completed.compareAndSet(false, true)) {
            return;
        }
        List<FamilyAutomationRule> ruleCopy = new ArrayList<>(rules);
        ruleCopy.sort((first, second) -> {
            if (first.enabled != second.enabled) {
                return first.enabled ? -1 : 1;
            }
            return Long.compare(second.updatedAt, first.updatedAt);
        });
        List<FamilyAutomationEvent> eventCopy = new ArrayList<>(events);
        eventCopy.sort((first, second) ->
                Long.compare(second.occurredAt, first.occurredAt));
        if (eventCopy.size() > 60) {
            eventCopy = new ArrayList<>(eventCopy.subList(0, 60));
        }
        callback.onLoaded(
                session,
                lists.all,
                lists.visible,
                lists.manageable,
                ruleCopy,
                eventCopy
        );
    }

    private void failOnce(
            @NonNull AtomicBoolean completed,
            @NonNull OverviewCallback callback,
            @NonNull String reason
    ) {
        if (completed.compareAndSet(false, true)) {
            callback.onError(reason);
        }
    }

    public void saveRule(
            @NonNull FamilyAutomationRule source,
            @NonNull ActionCallback callback
    ) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                verifyManageAccess(session, source.targetUid, allowed -> {
                    if (!allowed) {
                        callback.onError("MANAGE_ACCESS_DENIED");
                        return;
                    }
                    FamilyAutomationRule rule = copyRule(source);
                    DatabaseReference branch = root
                            .child("familyAutomationRules")
                            .child(session.familyId)
                            .child(rule.targetUid);
                    if (rule.ruleId.trim().isEmpty()) {
                        String key = branch.push().getKey();
                        if (key == null) {
                            callback.onError("RULE_ID_FAILED");
                            return;
                        }
                        rule.ruleId = key;
                        rule.createdAt = System.currentTimeMillis();
                    }
                    rule.familyId = session.familyId;
                    rule.createdByUid = session.uid;
                    rule.updatedAt = System.currentTimeMillis();
                    if (!FamilyAutomationPolicy.validRule(rule)) {
                        callback.onError("INVALID_RULE");
                        return;
                    }
                    branch.child(rule.ruleId)
                            .setValue(rule)
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess())
                            .addOnFailureListener(error ->
                                    callback.onError("RULE_SAVE_FAILED"));
                });
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    public void deleteRule(
            @NonNull FamilyAutomationRule rule,
            @NonNull ActionCallback callback
    ) {
        resolveSession(new SessionCallback() {
            @Override
            public void onReady(@NonNull Session session) {
                verifyManageAccess(session, rule.targetUid, allowed -> {
                    if (!allowed) {
                        callback.onError("MANAGE_ACCESS_DENIED");
                        return;
                    }
                    root.child("familyAutomationRules")
                            .child(session.familyId)
                            .child(rule.targetUid)
                            .child(rule.ruleId)
                            .removeValue()
                            .addOnSuccessListener(unused ->
                                    callback.onSuccess())
                            .addOnFailureListener(error ->
                                    callback.onError("RULE_DELETE_FAILED"));
                });
            }

            @Override
            public void onError(@NonNull String reason) {
                callback.onError(reason);
            }
        });
    }

    private void verifyManageAccess(
            @NonNull Session session,
            @NonNull String targetUid,
            @NonNull AccessCallback callback
    ) {
        if (session.uid.equals(targetUid)
                || session.canManageFamilyRules()) {
            root.child("memberships")
                    .child(session.familyId)
                    .child(targetUid)
                    .get()
                    .addOnSuccessListener(snapshot -> callback.onResolved(
                            "ACTIVE".equals(stringValue(
                                    snapshot.child("status")
                            ))
                    ))
                    .addOnFailureListener(error ->
                            callback.onResolved(false));
            return;
        }
        callback.onResolved(false);
    }

    @NonNull
    private FamilyAutomationRule copyRule(
            @NonNull FamilyAutomationRule source
    ) {
        FamilyAutomationRule rule = new FamilyAutomationRule();
        rule.ruleId = safe(source.ruleId);
        rule.familyId = safe(source.familyId);
        rule.targetUid = safe(source.targetUid);
        rule.targetName = safe(source.targetName);
        rule.createdByUid = safe(source.createdByUid);
        rule.title = safe(source.title);
        rule.type = safe(source.type);
        rule.placeName = safe(source.placeName);
        rule.latitude = source.latitude;
        rule.longitude = source.longitude;
        rule.radiusMeters = source.radiusMeters;
        rule.daysMask = source.daysMask;
        rule.startMinute = source.startMinute;
        rule.endMinute = source.endMinute;
        rule.graceMinutes = source.graceMinutes;
        rule.enabled = source.enabled;
        rule.notifyTrustedViewers = source.notifyTrustedViewers;
        rule.createdAt = source.createdAt;
        rule.updatedAt = source.updatedAt;
        return rule;
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
                .addOnSuccessListener(userSnapshot -> {
                    String familyId = stringValue(
                            userSnapshot.child("familyId")
                    );
                    String status = stringValue(
                            userSnapshot.child("status")
                    );
                    if (familyId.isEmpty() || !"ACTIVE".equals(status)) {
                        callback.onError("ACTIVE_FAMILY_REQUIRED");
                        return;
                    }
                    root.child("memberships")
                            .child(familyId)
                            .child(user.getUid())
                            .get()
                            .addOnSuccessListener(membership -> {
                                String memberStatus = stringValue(
                                        membership.child("status")
                                );
                                if (!"ACTIVE".equals(memberStatus)) {
                                    callback.onError(
                                            "ACTIVE_FAMILY_REQUIRED"
                                    );
                                    return;
                                }
                                String displayName = stringValue(
                                        membership.child("displayName")
                                );
                                if (displayName.isEmpty()) {
                                    displayName = user.getDisplayName();
                                }
                                if (displayName == null
                                        || displayName.trim().isEmpty()) {
                                    displayName = user.getEmail();
                                }
                                if (displayName == null
                                        || displayName.trim().isEmpty()) {
                                    displayName = user.getUid();
                                }
                                cachedSession = new Session(
                                        user.getUid(),
                                        familyId,
                                        displayName.trim(),
                                        stringValue(membership.child("role"))
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

    private interface AccessCallback {
        void onResolved(boolean allowed);
    }

    private static final class MemberLists {
        @NonNull final List<Member> all;
        @NonNull final List<Member> visible;
        @NonNull final List<Member> manageable;

        MemberLists(
                @NonNull List<Member> all,
                @NonNull List<Member> visible,
                @NonNull List<Member> manageable
        ) {
            this.all = all;
            this.visible = visible;
            this.manageable = manageable;
        }
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
