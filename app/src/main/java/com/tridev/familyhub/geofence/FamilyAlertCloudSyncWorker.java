package com.tridev.familyhub.geofence;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.SafePlaceAlertDao;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Uploads local alerts idempotently and merges authorised family history. */
public final class FamilyAlertCloudSyncWorker extends Worker {

    private static final long TIMEOUT_SECONDS = 25L;
    private static final int MAX_CLOUD_ALERTS = 500;

    public FamilyAlertCloudSyncWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters
    ) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            return Result.success();
        }
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        try {
            DataSnapshot profile = Tasks.await(
                    root.child("users").child(user.getUid()).get(),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
            String familyId = stringValue(profile.child("familyId"));
            if (familyId.isEmpty()
                    || !"ACTIVE".equals(stringValue(profile.child("status")))) {
                return Result.success();
            }
            DataSnapshot membership = Tasks.await(
                    root.child("memberships").child(familyId)
                            .child(user.getUid()).get(),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
            );
            if (!user.getUid().equals(stringValue(membership.child("uid")))
                    || !"ACTIVE".equals(stringValue(
                    membership.child("status")
            ))) {
                return Result.success();
            }

            SafePlaceAlertDao dao = FamilyHubDatabase
                    .getInstance(getApplicationContext())
                    .safePlaceAlertDao();
            DatabaseReference branch = root.child("familySafetyAlerts")
                    .child(familyId);
            uploadLocal(branch, dao.getAll(), familyId, user.getUid());
            mergeCloud(dao, Tasks.await(
                    branch.orderByChild("occurredAt")
                            .limitToLast(MAX_CLOUD_ALERTS).get(),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
            ));
            return Result.success();
        } catch (Exception error) {
            return Result.retry();
        }
    }

    private void uploadLocal(
            @NonNull DatabaseReference branch,
            @NonNull List<SafePlaceAlert> alerts,
            @NonNull String familyId,
            @NonNull String uid
    ) throws Exception {
        int limit = Math.min(MAX_CLOUD_ALERTS, alerts.size());
        for (int i = 0; i < limit; i++) {
            SafePlaceAlert alert = alerts.get(i);
            String eventId = eventId(alert);
            DatabaseReference target = branch.child(eventId);

            // familySafetyAlerts are immutable. Cloud history is merged back into
            // the local Room table, so a later worker run can encounter the same
            // deterministic eventId again. Skip an existing cloud event instead
            // of attempting an overwrite that the security rules correctly deny.
            try {
                DataSnapshot existing = Tasks.await(target.get(),
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (existing.exists()) {
                    continue;
                }
            } catch (Exception ignored) {
                // Preserve the previous upload behaviour when a preflight read is
                // temporarily unavailable; the guarded write below remains safe.
            }

            Map<String, Object> values = new HashMap<>();
            values.put("eventId", eventId);
            values.put("familyId", familyId);
            values.put("createdByUid", uid);
            values.put("placeId", bounded(alert.placeId, 160));
            values.put("type", bounded(alert.transitionType, 40));
            values.put("occurredAt", alert.occurredAt);
            values.put("deduplicationBucket", alert.deduplicationBucket);
            values.put("createdAt", ServerValue.TIMESTAMP);
            try {
                Tasks.await(target.setValue(values),
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // An immutable event may have been created between preflight and write.
            }
        }
    }

    private void mergeCloud(
            @NonNull SafePlaceAlertDao dao,
            @NonNull DataSnapshot snapshot
    ) {
        for (DataSnapshot child : snapshot.getChildren()) {
            if (!child.getKey().equals(stringValue(child.child("eventId")))) {
                continue;
            }
            String placeId = stringValue(child.child("placeId"));
            String type = stringValue(child.child("type"));
            Long occurredAt = child.child("occurredAt").getValue(Long.class);
            Long bucket = child.child("deduplicationBucket")
                    .getValue(Long.class);
            if (placeId.isEmpty() || type.isEmpty()
                    || occurredAt == null || occurredAt <= 0L
                    || bucket == null || bucket < 0L) {
                continue;
            }
            SafePlaceAlert alert = new SafePlaceAlert();
            alert.placeId = placeId;
            alert.transitionType = type;
            alert.occurredAt = occurredAt;
            alert.deduplicationBucket = bucket;
            alert.isRead = false;
            dao.insert(alert);
        }
    }

    @NonNull
    static String eventId(@NonNull SafePlaceAlert alert) throws Exception {
        String identity = alert.placeId + "|" + alert.transitionType + "|"
                + alert.deduplicationBucket;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder("alert_");
        for (int i = 0; i < 16; i++) {
            result.append(String.format("%02x", digest[i]));
        }
        return result.toString();
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String bounded(@NonNull String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
