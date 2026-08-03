package com.tridev.familyhub.location;

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
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PendingLocationUploadDao;
import com.tridev.familyhub.data.local.entity.PendingLocationUpload;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Restores the newest encrypted Family Live point when connectivity returns,
 * even if the foreground service or app process has already been stopped.
 */
public final class PendingLocationSyncWorker extends Worker {

    private static final long READ_TIMEOUT_SECONDS = 20L;
    private static final long WRITE_TIMEOUT_SECONDS = 30L;

    public PendingLocationSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        PendingLocationUploadDao dao = FamilyHubDatabase
                .getInstance(context)
                .pendingLocationUploadDao();

        PendingLocationUpload pending = dao.getLatest();
        if (pending == null) {
            return Result.success();
        }

        if (!LocationSharingStore.isSharingEnabled(context)) {
            dao.deleteAll();
            return Result.success();
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            dao.deleteAll();
            return Result.success();
        }

        try {
            JSONObject payload = new JSONObject(
                    VaultCipher.decrypt(pending.encryptedPayload)
            );
            String payloadUserId = payload.optString("uid");
            String payloadFamilyId = payload.optString("familyId");
            long queuedTimestamp = payload.optLong(
                    "clientTimestamp",
                    pending.createdAt
            );

            if (!user.getUid().equals(payloadUserId)
                    || payloadFamilyId.trim().isEmpty()) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            if (!belongsToCurrentSession(context, queuedTimestamp)) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            DatabaseReference root = FirebaseDatabase
                    .getInstance()
                    .getReference();
            DataSnapshot userSnapshot = Tasks.await(
                    root.child("users")
                            .child(user.getUid())
                            .get(),
                    READ_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            String currentFamilyId = userSnapshot
                    .child("familyId")
                    .getValue(String.class);
            String currentStatus = userSnapshot
                    .child("status")
                    .getValue(String.class);

            if (!payloadFamilyId.equals(currentFamilyId)
                    || !"ACTIVE".equals(currentStatus)) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            if (!LocationSharingStore.isSharingEnabled(context)) {
                dao.deleteAll();
                return Result.success();
            }
            if (!belongsToCurrentSession(context, queuedTimestamp)) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            DatabaseReference locationReference = root
                    .child("locations")
                    .child(payloadFamilyId)
                    .child(payloadUserId);

            DataSnapshot remoteTimestampSnapshot = Tasks.await(
                    locationReference.child("clientTimestamp").get(),
                    READ_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            Long remoteTimestampValue = remoteTimestampSnapshot
                    .getValue(Long.class);
            long remoteTimestamp = remoteTimestampValue == null
                    ? 0L
                    : remoteTimestampValue;

            if (!LocationSyncPolicy.shouldUpload(
                    remoteTimestamp,
                    queuedTimestamp
            )) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            Map<String, Object> values = jsonToMap(payload);
            double latitude = LocationSyncPolicy.doubleValue(
                    values.get("latitude"),
                    0D
            );
            double longitude = LocationSyncPolicy.doubleValue(
                    values.get("longitude"),
                    0D
            );
            values.put(
                    "clientUpdateId",
                    LocationSyncPolicy.createUpdateId(
                            payloadFamilyId,
                            payloadUserId,
                            queuedTimestamp,
                            latitude,
                            longitude
                    )
            );
            values.put("updatedAt", queuedTimestamp);
            values.put("syncedAt", ServerValue.TIMESTAMP);

            if (!LocationSharingStore.isSharingEnabled(context)) {
                dao.deleteAll();
                return Result.success();
            }
            if (!belongsToCurrentSession(context, queuedTimestamp)) {
                dao.deleteById(pending.id);
                return Result.success();
            }

            Tasks.await(
                    locationReference.updateChildren(values),
                    WRITE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            dao.deleteById(pending.id);
            return Result.success();
        } catch (Exception error) {
            long delay = LocationSyncPolicy.retryDelay(
                    pending.attemptCount
            );
            dao.markRetry(
                    pending.id,
                    System.currentTimeMillis() + delay
            );
            return Result.retry();
        }
    }

    private boolean belongsToCurrentSession(
            @NonNull Context context,
            long queuedTimestamp
    ) {
        return LocationSyncPolicy.belongsToCurrentSharingSession(
                queuedTimestamp,
                LocationSharingStore.sharingEnabledAt(context)
        );
    }

    @NonNull
    private Map<String, Object> jsonToMap(
            @NonNull JSONObject object
    ) throws JSONException {
        Map<String, Object> values = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            if (value != JSONObject.NULL) {
                values.put(key, value);
            }
        }
        return values;
    }
}
