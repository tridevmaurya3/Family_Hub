package com.tridev.familyhub.feature.journey;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records sampled Journey History while the existing consent-based Family Live
 * foreground service is active. It listens only to the signed-in user's own
 * reliable location node and never creates tracking by itself.
 */
public final class FamilyJourneyRecorder {

    private static final String PREFS = "family_journey_recorder";
    private static final String KEY_CLEANUP_AT_PREFIX = "cleanup_at_";
    private static final long SAFE_PLACE_CACHE_MS = 10L * 60L * 1000L;

    private static final FamilyJourneyRecorder INSTANCE =
            new FamilyJourneyRecorder();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DatabaseReference root = FirebaseDatabase
            .getInstance()
            .getReference();

    @Nullable private Context appContext;
    @Nullable private FirebaseAuth.AuthStateListener authListener;
    @Nullable private DatabaseReference privacyReference;
    @Nullable private ValueEventListener privacyListener;
    @Nullable private DatabaseReference locationReference;
    @Nullable private ValueEventListener locationListener;
    @Nullable private String uid;
    @Nullable private String familyId;

    private boolean historyEnabled;
    private int retentionDays = FamilyJourneyPolicy.DEFAULT_RETENTION_DAYS;
    private int generation;

    @NonNull private List<SafePlace> cachedSafePlaces = new ArrayList<>();
    private long safePlaceCacheAt;

    private FamilyJourneyRecorder() {
    }

    public static void start(@NonNull Context context) {
        INSTANCE.startInternal(context.getApplicationContext());
    }

    private synchronized void startInternal(@NonNull Context context) {
        appContext = context;
        if (authListener != null) {
            return;
        }
        authListener = auth -> attachForUser(auth.getCurrentUser());
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
    }

    private synchronized void attachForUser(@Nullable FirebaseUser user) {
        detachAll();
        if (user == null || !user.isEmailVerified()) {
            return;
        }
        uid = user.getUid();
        int requestGeneration = ++generation;
        root.child("users").child(uid).get()
                .addOnSuccessListener(snapshot -> {
                    if (requestGeneration != generation || uid == null) {
                        return;
                    }
                    String resolvedFamilyId =
                            stringValue(snapshot.child("familyId"));
                    String status = stringValue(snapshot.child("status"));
                    if (resolvedFamilyId.isEmpty() || !"ACTIVE".equals(status)) {
                        return;
                    }
                    familyId = resolvedFamilyId;
                    attachPrivacy(requestGeneration);
                });
    }

    private synchronized void attachPrivacy(int requestGeneration) {
        if (familyId == null || uid == null) {
            return;
        }
        privacyReference = root.child("journeyPrivacy")
                .child(familyId)
                .child(uid);
        privacyListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (requestGeneration != generation) {
                    return;
                }
                historyEnabled = booleanValue(
                        snapshot.child("historyEnabled"),
                        false
                );
                retentionDays = FamilyJourneyPolicy.normalizeRetentionDays(
                        intValue(
                                snapshot.child("retentionDays"),
                                FamilyJourneyPolicy.DEFAULT_RETENTION_DAYS
                        )
                );
                if (historyEnabled) {
                    attachOwnLocation(requestGeneration);
                    scheduleCleanup();
                } else {
                    detachLocationListener();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                historyEnabled = false;
                detachLocationListener();
            }
        };
        privacyReference.addValueEventListener(privacyListener);
    }

    private synchronized void attachOwnLocation(int requestGeneration) {
        if (locationListener != null || familyId == null || uid == null) {
            return;
        }
        locationReference = root.child("locations")
                .child(familyId)
                .child(uid);
        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (requestGeneration != generation || !historyEnabled) {
                    return;
                }
                processLocationSnapshot(snapshot, requestGeneration);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Family Live continues independently if history is unavailable.
            }
        };
        locationReference.addValueEventListener(locationListener);
    }

    private void processLocationSnapshot(
            @NonNull DataSnapshot snapshot,
            int requestGeneration
    ) {
        if (!booleanValue(snapshot.child("sharingEnabled"), false)
                || !"RELIABLE".equals(
                stringValue(snapshot.child("locationQuality"))
        )) {
            return;
        }

        Double latitude = doubleValue(snapshot.child("latitude"));
        Double longitude = doubleValue(snapshot.child("longitude"));
        Double accuracy = doubleValue(snapshot.child("accuracy"));
        long capturedAt = longValue(snapshot.child("clientTimestamp"));
        if (capturedAt <= 0L) {
            capturedAt = longValue(snapshot.child("updatedAt"));
        }
        long now = System.currentTimeMillis();
        if (latitude == null
                || longitude == null
                || accuracy == null
                || !FamilyJourneyPolicy.validPoint(
                latitude,
                longitude,
                accuracy,
                capturedAt,
                now
        )) {
            return;
        }

        String activeFamilyId = familyId;
        String activeUid = uid;
        if (activeFamilyId == null || activeUid == null) {
            return;
        }

        FamilyJourneyPoint point = new FamilyJourneyPoint();
        point.familyId = activeFamilyId;
        point.uid = activeUid;
        point.clientUpdateId = stringValue(snapshot.child("clientUpdateId"));
        point.dayKey = FamilyJourneyPolicy.dayKey(capturedAt);
        point.latitude = latitude;
        point.longitude = longitude;
        point.accuracy = accuracy;
        point.capturedAt = capturedAt;
        point.speedMetersPerSecond = Math.max(
                0D,
                nullableDouble(snapshot.child("speedMetersPerSecond"), 0D)
        );
        point.movementType = FamilyJourneyPolicy.normalizeMovement(
                stringValue(snapshot.child("movementType"))
        );
        point.placeLabel = stringValue(snapshot.child("placeLabel"));
        point.batteryPercentage = Math.max(
                -1,
                Math.min(100, intValue(
                        snapshot.child("batteryPercentage"),
                        -1
                ))
        );
        point.charging = booleanValue(snapshot.child("charging"), false);
        point.pointId = createPointId(point);

        executor.execute(() -> completeAndRecord(point, requestGeneration));
    }

    private void completeAndRecord(
            @NonNull FamilyJourneyPoint point,
            int requestGeneration
    ) {
        if (requestGeneration != generation || !historyEnabled) {
            return;
        }
        SafePlace matched = findSafePlace(point.latitude, point.longitude);
        if (matched != null) {
            point.safePlaceId = String.valueOf(matched.id);
            point.safePlaceName = matched.name.trim();
        }

        FamilyJourneyPoint previous = loadPreviousPoint(point.uid);
        if (!FamilyJourneyPolicy.shouldRecord(previous, point)) {
            return;
        }

        Map<String, Object> values = new HashMap<>();
        values.put("pointId", point.pointId);
        values.put("familyId", point.familyId);
        values.put("uid", point.uid);
        values.put("clientUpdateId", point.clientUpdateId);
        values.put("dayKey", point.dayKey);
        values.put("latitude", point.latitude);
        values.put("longitude", point.longitude);
        values.put("accuracy", point.accuracy);
        values.put("capturedAt", point.capturedAt);
        values.put("recordedAt", ServerValue.TIMESTAMP);
        values.put("speedMetersPerSecond", point.speedMetersPerSecond);
        values.put("movementType", point.movementType);
        values.put("placeLabel", point.placeLabel);
        values.put("safePlaceId", point.safePlaceId);
        values.put("safePlaceName", point.safePlaceName);
        values.put("batteryPercentage", point.batteryPercentage);
        values.put("charging", point.charging);

        root.child("locationHistory")
                .child(point.familyId)
                .child(point.uid)
                .child(point.dayKey)
                .child(point.pointId)
                .setValue(values)
                .addOnSuccessListener(unused -> savePreviousPoint(point));
    }

    @Nullable
    private SafePlace findSafePlace(double latitude, double longitude) {
        Context context = appContext;
        if (context == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - safePlaceCacheAt >= SAFE_PLACE_CACHE_MS) {
            cachedSafePlaces = FamilyHubDatabase.getInstance(context)
                    .safePlaceDao()
                    .getAll();
            safePlaceCacheAt = now;
        }
        String activeUid = uid == null ? "" : uid;
        SafePlace best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SafePlace place : cachedSafePlaces) {
            if (!place.memberUid.trim().isEmpty()
                    && !place.memberUid.trim().equals(activeUid)) {
                continue;
            }
            double distance = FamilyJourneyPolicy.distanceMeters(
                    latitude,
                    longitude,
                    place.latitude,
                    place.longitude
            );
            if (distance <= Math.max(100F, place.radiusMeters)
                    && distance < bestDistance) {
                best = place;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void scheduleCleanup() {
        Context context = appContext;
        String activeFamilyId = familyId;
        String activeUid = uid;
        if (context == null || activeFamilyId == null || activeUid == null) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        long now = System.currentTimeMillis();
        String key = KEY_CLEANUP_AT_PREFIX + activeUid;
        if (now - preferences.getLong(key, 0L)
                < FamilyJourneyPolicy.CLEANUP_INTERVAL_MS) {
            return;
        }
        preferences.edit().putLong(key, now).apply();
        root.child("locationHistory")
                .child(activeFamilyId)
                .child(activeUid)
                .get()
                .addOnSuccessListener(snapshot -> {
                    Map<String, Object> removals = new HashMap<>();
                    long cutoff = FamilyJourneyPolicy.retentionCutoffDay(
                            System.currentTimeMillis(),
                            retentionDays
                    );
                    for (DataSnapshot day : snapshot.getChildren()) {
                        String dayKey = day.getKey();
                        if (dayKey != null && dayStart(dayKey) < cutoff) {
                            removals.put(dayKey, null);
                        }
                    }
                    if (!removals.isEmpty()) {
                        snapshot.getRef().updateChildren(removals);
                    }
                });
    }

    private long dayStart(@NonNull String dayKey) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getDefault());
        try {
            Date date = format.parse(dayKey);
            return date == null ? Long.MAX_VALUE : date.getTime();
        } catch (ParseException ignored) {
            return Long.MAX_VALUE;
        }
    }

    @NonNull
    private String createPointId(@NonNull FamilyJourneyPoint point) {
        String seed = point.clientUpdateId.isEmpty()
                ? point.latitude + ":" + point.longitude
                : point.clientUpdateId;
        return point.capturedAt + "_" + Integer.toHexString(seed.hashCode());
    }

    @Nullable
    private FamilyJourneyPoint loadPreviousPoint(@NonNull String activeUid) {
        Context context = appContext;
        if (context == null) {
            return null;
        }
        SharedPreferences p = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        String prefix = "last_" + activeUid + "_";
        long capturedAt = p.getLong(prefix + "capturedAt", 0L);
        if (capturedAt <= 0L) {
            return null;
        }
        FamilyJourneyPoint point = new FamilyJourneyPoint();
        point.uid = activeUid;
        point.capturedAt = capturedAt;
        point.latitude = Double.longBitsToDouble(
                p.getLong(prefix + "lat", 0L)
        );
        point.longitude = Double.longBitsToDouble(
                p.getLong(prefix + "lon", 0L)
        );
        point.movementType = p.getString(prefix + "movement", "UNKNOWN");
        point.safePlaceId = p.getString(prefix + "safePlaceId", "");
        point.clientUpdateId = p.getString(prefix + "updateId", "");
        return point;
    }

    private void savePreviousPoint(@NonNull FamilyJourneyPoint point) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        String prefix = "last_" + point.uid + "_";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(prefix + "capturedAt", point.capturedAt)
                .putLong(
                        prefix + "lat",
                        Double.doubleToLongBits(point.latitude)
                )
                .putLong(
                        prefix + "lon",
                        Double.doubleToLongBits(point.longitude)
                )
                .putString(prefix + "movement", point.movementType)
                .putString(prefix + "safePlaceId", point.safePlaceId)
                .putString(prefix + "updateId", point.clientUpdateId)
                .apply();
    }

    private synchronized void detachAll() {
        generation++;
        detachLocationListener();
        if (privacyReference != null && privacyListener != null) {
            privacyReference.removeEventListener(privacyListener);
        }
        privacyReference = null;
        privacyListener = null;
        uid = null;
        familyId = null;
        historyEnabled = false;
    }

    private synchronized void detachLocationListener() {
        if (locationReference != null && locationListener != null) {
            locationReference.removeEventListener(locationListener);
        }
        locationReference = null;
        locationListener = null;
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private static long longValue(@NonNull DataSnapshot snapshot) {
        Long value = snapshot.getValue(Long.class);
        if (value != null) {
            return Math.max(0L, value);
        }
        Double decimal = snapshot.getValue(Double.class);
        return decimal == null ? 0L : Math.max(0L, decimal.longValue());
    }

    private static int intValue(@NonNull DataSnapshot snapshot, int fallback) {
        Long value = snapshot.getValue(Long.class);
        if (value != null) {
            return value.intValue();
        }
        Double decimal = snapshot.getValue(Double.class);
        return decimal == null ? fallback : decimal.intValue();
    }

    @Nullable
    private static Double doubleValue(@NonNull DataSnapshot snapshot) {
        Double decimal = snapshot.getValue(Double.class);
        if (decimal != null) {
            return decimal;
        }
        Long whole = snapshot.getValue(Long.class);
        return whole == null ? null : whole.doubleValue();
    }

    private static double nullableDouble(
            @NonNull DataSnapshot snapshot,
            double fallback
    ) {
        Double value = doubleValue(snapshot);
        return value == null ? fallback : value;
    }

    private static boolean booleanValue(
            @NonNull DataSnapshot snapshot,
            boolean fallback
    ) {
        Boolean value = snapshot.getValue(Boolean.class);
        return value == null ? fallback : value;
    }
}
