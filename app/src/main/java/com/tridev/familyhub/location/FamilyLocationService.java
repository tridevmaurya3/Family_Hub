package com.tridev.familyhub.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PendingLocationUploadDao;
import com.tridev.familyhub.data.local.entity.PendingLocationUpload;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;
import com.tridev.familyhub.receiver.MovementTransitionReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Visible, consent-based Family Live sharing.
 *
 * Only the signed-in member's coordinates, accuracy and timestamps are written
 * to their own family-scoped Firebase node. No device identifier or unrelated
 * device telemetry is collected by this foundation.
 */
public class FamilyLocationService extends Service {

    public static final String ACTION_START =
            "com.tridev.familyhub.action.START_LOCATION_SHARING";
    public static final String ACTION_STOP =
            "com.tridev.familyhub.action.STOP_LOCATION_SHARING";

    private static final String CHANNEL_ID = "family_live_location";
    private static final int NOTIFICATION_ID = 4102;
    private static final long UPDATE_INTERVAL_MS = 60_000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 30_000L;
    private static final float MIN_DISTANCE_METERS = 25F;
    private static final String PROFILE_NORMAL = "NORMAL";
    private static final String PROFILE_ACTIVE = "ACTIVE";
    private static final String PROFILE_STATIONARY = "STATIONARY";
    private static final String PROFILE_LOW_BATTERY = "LOW_BATTERY";
    private static final long ACTIVITY_FRESHNESS_MS = 2L * 60L * 1000L;
    private static final long GEOCODE_INTERVAL_MS = 15L * 60L * 1000L;
    private static final float GEOCODE_DISTANCE_METERS = 500F;
    private static final int ACTIVITY_PENDING_INTENT_REQUEST = 4203;
    private static final int MAX_QUEUED_LOCATIONS = 100;
    private static final long RETRY_BASE_DELAY_MS = 30_000L;
    private static final long RETRY_MAX_DELAY_MS = 15L * 60L * 1000L;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityRecognitionClient activityRecognitionClient;
    private PendingIntent movementTransitionPendingIntent;
    private LocationCallback locationCallback;
    private DatabaseReference locationReference;
    private PendingLocationUploadDao pendingLocationUploadDao;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String familyId;
    private String userId;
    private boolean requestingUpdates;
    private boolean trackingProfileChangeInProgress;
    @NonNull private String currentTrackingProfile = PROFILE_NORMAL;
    @Nullable private Location previousMovementLocation;
    @NonNull private String stableMovementType = "UNKNOWN";
    @NonNull private String candidateMovementType = "UNKNOWN";
    private int candidateMovementSamples;
    private final ExecutorService geocodeExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService uploadQueueExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean queueFlushInProgress =
            new AtomicBoolean(false);
    private final AtomicBoolean serviceDestroyed =
            new AtomicBoolean(false);
    @Nullable private Location lastGeocodedLocation;
    private long lastGeocodedAt;
    private boolean geocodeInProgress;
    @NonNull private String cachedPlaceLabel = "";

    @NonNull
    public static Intent startIntent(@NonNull Context context) {
        return new Intent(context, FamilyLocationService.class)
                .setAction(ACTION_START);
    }

    @NonNull
    public static Intent stopIntent(@NonNull Context context) {
        return new Intent(context, FamilyLocationService.class)
                .setAction(ACTION_STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(this);
        activityRecognitionClient =
                ActivityRecognition.getClient(this);
        movementTransitionPendingIntent =
                buildMovementTransitionPendingIntent();
        pendingLocationUploadDao = FamilyHubDatabase
                .getInstance(this)
                .pendingLocationUploadDao();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location latest = result.getLastLocation();
                if (latest != null) {
                    publishLocation(latest);
                }
            }
        };
        createNotificationChannel();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(
            @Nullable Intent intent,
            int flags,
            int startId
    ) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSharing();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            stopWithoutRestart();
            return START_NOT_STICKY;
        }

        userId = user.getUid();
        FirebaseDatabase.getInstance().getReference()
                .child("users")
                .child(userId)
                .get()
                .addOnSuccessListener(snapshot -> {
                    String resolvedFamilyId =
                            snapshot.child("familyId").getValue(String.class);
                    String status = snapshot.child("status").getValue(String.class);
                    if (resolvedFamilyId == null
                            || resolvedFamilyId.trim().isEmpty()
                            || !"ACTIVE".equals(status)) {
                        stopSharing();
                        return;
                    }

                    familyId = resolvedFamilyId;
                    locationReference = FirebaseDatabase.getInstance()
                            .getReference()
                            .child("locations")
                            .child(familyId)
                            .child(userId);
                    registerDisconnectState();
                    if (!hasLocationPermission()) {
                        publishUnavailableState(
                                FamilyLiveAvailability.PERMISSION_OFF
                        );
                        stopWithoutRestart();
                        return;
                    }
                    if (!isDeviceLocationEnabled()) {
                        publishUnavailableState(
                                FamilyLiveAvailability.GPS_OFF
                        );
                        stopWithoutRestart();
                        return;
                    }
                    LocationSharingStore.setSharingEnabled(this, true);
                    registerMovementTransitions();
                    requestLocationUpdates();
                    flushQueuedLocations();
                })
                .addOnFailureListener(error -> stopSharing());

        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates() {
        requestLocationUpdates(currentTrackingProfile);
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates(@NonNull String profile) {
        if (requestingUpdates || !hasLocationPermission()) {
            return;
        }

        long interval = UPDATE_INTERVAL_MS;
        long minInterval = MIN_UPDATE_INTERVAL_MS;
        float minDistance = MIN_DISTANCE_METERS;
        int priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        switch (profile) {
            case PROFILE_ACTIVE:
                interval = 20_000L;
                minInterval = 10_000L;
                minDistance = 10F;
                priority = Priority.PRIORITY_HIGH_ACCURACY;
                break;
            case PROFILE_STATIONARY:
                interval = 180_000L;
                minInterval = 60_000L;
                minDistance = 50F;
                break;
            case PROFILE_LOW_BATTERY:
                interval = 300_000L;
                minInterval = 120_000L;
                minDistance = 100F;
                break;
            default:
                break;
        }

        LocationRequest request = new LocationRequest.Builder(
                priority,
                interval
        )
                .setMinUpdateIntervalMillis(minInterval)
                .setMinUpdateDistanceMeters(minDistance)
                .setWaitForAccurateLocation(
                        PROFILE_ACTIVE.equals(profile)
                )
                .build();

        requestingUpdates = true;
        currentTrackingProfile = profile;
        fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                getMainLooper()
        ).addOnFailureListener(error -> {
            requestingUpdates = false;
            stopSharing();
        });
    }

    private void updateTrackingProfile(
            @NonNull MovementSnapshot movement,
            @NonNull BatterySnapshot battery
    ) {
        String desiredProfile;
        if (battery.percentage >= 0
                && battery.percentage <= 15
                && !battery.charging) {
            desiredProfile = PROFILE_LOW_BATTERY;
        } else if ("WALKING".equals(movement.type)
                || "CYCLING".equals(movement.type)
                || "TRAVELLING".equals(movement.type)) {
            desiredProfile = PROFILE_ACTIVE;
        } else if ("STATIONARY".equals(movement.type)) {
            desiredProfile = PROFILE_STATIONARY;
        } else {
            desiredProfile = PROFILE_NORMAL;
        }

        if (desiredProfile.equals(currentTrackingProfile)
                || trackingProfileChangeInProgress) {
            return;
        }
        trackingProfileChangeInProgress = true;
        fusedLocationClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener(task -> {
                    requestingUpdates = false;
                    trackingProfileChangeInProgress = false;
                    if (LocationSharingStore.isSharingEnabled(this)
                            && hasLocationPermission()) {
                        requestLocationUpdates(desiredProfile);
                    }
                });
    }

    private void registerDisconnectState() {
        if (locationReference == null) {
            return;
        }
        Map<String, Object> disconnected = new HashMap<>();
        disconnected.put("online", false);
        disconnected.put(
                "availabilityReason",
                FamilyLiveAvailability.DEVICE_OFFLINE
        );
        disconnected.put("lastDisconnectedAt", ServerValue.TIMESTAMP);
        locationReference.onDisconnect().updateChildren(disconnected);
    }

    private void publishLocation(@NonNull Location location) {
        if (locationReference == null || familyId == null || userId == null) {
            return;
        }

        BatterySnapshot battery = readBatterySnapshot();
        MovementSnapshot movement = resolveMovement(location);
        Map<String, Object> values = new HashMap<>();
        values.put("uid", userId);
        values.put("familyId", familyId);
        values.put("latitude", location.getLatitude());
        values.put("longitude", location.getLongitude());
        values.put("accuracy", (double) location.getAccuracy());
        values.put(
                "speedMetersPerSecond",
                movement.speedMetersPerSecond
        );
        values.put("movementType", movement.type);
        if (!cachedPlaceLabel.isEmpty()) {
            values.put("placeLabel", cachedPlaceLabel);
        }
        values.put("batteryPercentage", battery.percentage);
        values.put("charging", battery.charging);
        values.put("online", true);
        values.put("sharingEnabled", true);
        values.put(
                "availabilityReason",
                isPowerSaveMode()
                        ? FamilyLiveAvailability.BATTERY_SAVER
                        : FamilyLiveAvailability.AVAILABLE
        );
        values.put("clientTimestamp", System.currentTimeMillis());
        values.put("updatedAt", ServerValue.TIMESTAMP);
        locationReference.updateChildren(values)
                .addOnSuccessListener(ignored -> flushQueuedLocations())
                .addOnFailureListener(error ->
                        enqueueLocationForRetry(values));
        resolvePlaceLabelIfNeeded(location);
        updateTrackingProfile(movement, battery);
    }

    private void enqueueLocationForRetry(
            @NonNull Map<String, Object> sourceValues
    ) {
        Map<String, Object> queueValues = new HashMap<>(sourceValues);
        queueValues.remove("updatedAt");
        executeQueueTask(() -> {
            PendingLocationUpload pending = new PendingLocationUpload();
            pending.createdAt = System.currentTimeMillis();
            pending.nextAttemptAt = pending.createdAt;
            pending.attemptCount = 0;
            pending.encryptedPayload = VaultCipher.encrypt(
                    new JSONObject(queueValues).toString()
            );
            pendingLocationUploadDao.insert(pending);
            pendingLocationUploadDao.trimToLatest(MAX_QUEUED_LOCATIONS);
        });
    }

    private void flushQueuedLocations() {
        if (locationReference == null
                || familyId == null
                || userId == null
                || serviceDestroyed.get()
                || !queueFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        drainNextQueuedLocation();
    }

    private void drainNextQueuedLocation() {
        executeQueueTask(() -> {
            PendingLocationUpload pending =
                    pendingLocationUploadDao.getNextReady(
                            System.currentTimeMillis()
                    );
            if (pending == null) {
                queueFlushInProgress.set(false);
                return;
            }

            Map<String, Object> values;
            try {
                JSONObject payload = new JSONObject(VaultCipher.decrypt(
                        pending.encryptedPayload
                ));
                if (!userId.equals(payload.optString("uid"))
                        || !familyId.equals(payload.optString("familyId"))) {
                    pendingLocationUploadDao.deleteById(pending.id);
                    drainNextQueuedLocation();
                    return;
                }
                values = jsonToMap(payload);
                values.put("updatedAt", ServerValue.TIMESTAMP);
            } catch (JSONException error) {
                pendingLocationUploadDao.deleteById(pending.id);
                drainNextQueuedLocation();
                return;
            }

            mainHandler.post(() -> uploadQueuedLocation(pending, values));
        });
    }

    private void uploadQueuedLocation(
            @NonNull PendingLocationUpload pending,
            @NonNull Map<String, Object> values
    ) {
        if (locationReference == null || serviceDestroyed.get()) {
            queueFlushInProgress.set(false);
            return;
        }
        locationReference.updateChildren(values)
                .addOnSuccessListener(ignored -> executeQueueTask(() -> {
                    pendingLocationUploadDao.deleteById(pending.id);
                    drainNextQueuedLocation();
                }))
                .addOnFailureListener(error -> executeQueueTask(() -> {
                    long delay = retryDelay(pending.attemptCount);
                    pendingLocationUploadDao.markRetry(
                            pending.id,
                            System.currentTimeMillis() + delay
                    );
                    queueFlushInProgress.set(false);
                    mainHandler.postDelayed(
                            this::flushQueuedLocations,
                            delay
                    );
                }));
    }

    @NonNull
    private Map<String, Object> jsonToMap(
            @NonNull JSONObject object
    ) throws JSONException {
        Map<String, Object> values = new HashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            if (value != JSONObject.NULL) {
                values.put(key, value);
            }
        }
        return values;
    }

    private long retryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(0, attemptCount), 5);
        return Math.min(
                RETRY_MAX_DELAY_MS,
                RETRY_BASE_DELAY_MS * (1L << exponent)
        );
    }

    private void executeQueueTask(@NonNull Runnable task) {
        if (serviceDestroyed.get()) {
            return;
        }
        try {
            uploadQueueExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            queueFlushInProgress.set(false);
        }
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> flushQueuedLocations());
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(
                    networkCallback
            );
        } catch (RuntimeException ignored) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Callback may already have been removed by the system.
        }
        networkCallback = null;
    }

    /**
     * Resolves only a broad locality label. Street, premise, postal code and
     * full address lines are deliberately excluded from Family Live.
     */
    private void resolvePlaceLabelIfNeeded(@NonNull Location location) {
        if (!Geocoder.isPresent() || geocodeInProgress) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean intervalElapsed =
                now - lastGeocodedAt >= GEOCODE_INTERVAL_MS;
        boolean movedEnough = lastGeocodedLocation == null
                || lastGeocodedLocation.distanceTo(location)
                >= GEOCODE_DISTANCE_METERS;
        if (!intervalElapsed && !movedEnough) {
            return;
        }

        geocodeInProgress = true;
        Location requestedLocation = new Location(location);
        geocodeExecutor.execute(() -> {
            String label = "";
            try {
                Geocoder geocoder = new Geocoder(
                        getApplicationContext(),
                        Locale.getDefault()
                );
                List<Address> addresses = geocoder.getFromLocation(
                        requestedLocation.getLatitude(),
                        requestedLocation.getLongitude(),
                        1
                );
                if (addresses != null && !addresses.isEmpty()) {
                    label = createBroadPlaceLabel(addresses.get(0));
                }
            } catch (Exception ignored) {
                // Location sharing must continue if geocoding is unavailable.
            }

            final String resolvedLabel = label;
            mainHandler.post(() -> {
                geocodeInProgress = false;
                lastGeocodedAt = System.currentTimeMillis();
                lastGeocodedLocation = requestedLocation;
                if (resolvedLabel.isEmpty() || locationReference == null) {
                    return;
                }
                cachedPlaceLabel = resolvedLabel;
                locationReference.child("placeLabel")
                        .setValue(resolvedLabel);
            });
        });
    }

    @NonNull
    private String createBroadPlaceLabel(@NonNull Address address) {
        List<String> parts = new ArrayList<>();
        addUniquePlacePart(parts, address.getSubLocality());
        addUniquePlacePart(parts, address.getLocality());
        addUniquePlacePart(parts, address.getSubAdminArea());
        addUniquePlacePart(parts, address.getAdminArea());
        return String.join(", ", parts);
    }

    private void addUniquePlacePart(
            @NonNull List<String> parts,
            @Nullable String value
    ) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : parts) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        parts.add(trimmed);
    }

    private void stopSharing() {
        LocationSharingStore.setSharingEnabled(this, false);
        removeUpdates();
        removeMovementTransitions();
        MovementActivityStore.clear(this);
        if (locationReference != null) {
            Map<String, Object> stopped = new HashMap<>();
            stopped.put("sharingEnabled", false);
            stopped.put("online", false);
            stopped.put(
                    "availabilityReason",
                    FamilyLiveAvailability.SHARING_PAUSED
            );
            stopped.put("updatedAt", ServerValue.TIMESTAMP);
            locationReference.updateChildren(stopped);
            locationReference.onDisconnect().cancel();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopWithoutRestart() {
        LocationSharingStore.setSharingEnabled(this, false);
        removeUpdates();
        removeMovementTransitions();
        MovementActivityStore.clear(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publishUnavailableState(@NonNull String reason) {
        if (locationReference == null) {
            return;
        }
        Map<String, Object> unavailable = new HashMap<>();
        unavailable.put("availabilityReason", reason);
        unavailable.put("online", false);
        unavailable.put("updatedAt", ServerValue.TIMESTAMP);
        locationReference.updateChildren(unavailable);
    }

    private void removeUpdates() {
        if (requestingUpdates) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            requestingUpdates = false;
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDeviceLocationEnabled() {
        LocationManager manager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return manager != null && manager.isLocationEnabled();
    }

    private boolean isPowerSaveMode() {
        PowerManager manager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isPowerSaveMode();
    }

    @NonNull
    private MovementSnapshot resolveMovement(@NonNull Location location) {
        double calculatedSpeed = -1D;

        if (location.getAccuracy() <= 100F && location.hasSpeed()) {
            calculatedSpeed = Math.max(0D, location.getSpeed());
        } else if (location.getAccuracy() <= 100F
                && previousMovementLocation != null) {
            long elapsedNanos = location.getElapsedRealtimeNanos()
                    - previousMovementLocation.getElapsedRealtimeNanos();
            double elapsedSeconds = elapsedNanos / 1_000_000_000D;

            if (elapsedSeconds >= 2D && elapsedSeconds <= 300D) {
                float distance =
                        previousMovementLocation.distanceTo(location);
                float noiseRadius = Math.max(
                        10F,
                        (previousMovementLocation.getAccuracy()
                                + location.getAccuracy()) / 2F
                );
                calculatedSpeed = distance <= noiseRadius
                        ? 0D
                        : Math.max(
                                0D,
                                (distance - noiseRadius) / elapsedSeconds
                        );
            }
        } else if (location.getAccuracy() <= 50F) {
            calculatedSpeed = 0D;
        }

        previousMovementLocation = new Location(location);
        String gpsMovement = movementFromSpeed(calculatedSpeed);
        MovementActivityStore.Snapshot activity =
                MovementActivityStore.read(this);
        String fusedMovement = fuseMovement(
                gpsMovement,
                calculatedSpeed,
                activity
        );
        return new MovementSnapshot(
                Math.max(0D, calculatedSpeed),
                stabilizeMovement(fusedMovement, activity)
        );
    }

    @NonNull
    private String movementFromSpeed(double speedMetersPerSecond) {
        if (speedMetersPerSecond < 0D) {
            return "UNKNOWN";
        }
        if (speedMetersPerSecond < 0.8D) {
            return "STATIONARY";
        }
        if (speedMetersPerSecond < 2.5D) {
            return "WALKING";
        }
        if (speedMetersPerSecond < 8.5D) {
            return "CYCLING";
        }
        return "TRAVELLING";
    }

    @NonNull
    private String fuseMovement(
            @NonNull String gpsMovement,
            double speedMetersPerSecond,
            @NonNull MovementActivityStore.Snapshot activity
    ) {
        if (!activity.isFresh(
                System.currentTimeMillis(),
                ACTIVITY_FRESHNESS_MS
        )) {
            return gpsMovement;
        }

        switch (activity.type) {
            case MovementActivityStore.STILL:
                return speedMetersPerSecond >= 2.5D
                        ? gpsMovement
                        : "STATIONARY";
            case MovementActivityStore.WALKING:
            case MovementActivityStore.RUNNING:
                return speedMetersPerSecond >= 8.5D
                        ? "TRAVELLING"
                        : "WALKING";
            case MovementActivityStore.CYCLING:
                return speedMetersPerSecond >= 15D
                        ? "TRAVELLING"
                        : "CYCLING";
            case MovementActivityStore.IN_VEHICLE:
                return "TRAVELLING";
            default:
                return gpsMovement;
        }
    }

    @NonNull
    private String stabilizeMovement(
            @NonNull String proposed,
            @NonNull MovementActivityStore.Snapshot activity
    ) {
        if ("UNKNOWN".equals(proposed)) {
            return stableMovementType;
        }

        boolean freshActivity = activity.isFresh(
                System.currentTimeMillis(),
                ACTIVITY_FRESHNESS_MS
        );
        if ("UNKNOWN".equals(stableMovementType) || freshActivity) {
            stableMovementType = proposed;
            candidateMovementType = "UNKNOWN";
            candidateMovementSamples = 0;
            return stableMovementType;
        }

        if (stableMovementType.equals(proposed)) {
            candidateMovementType = "UNKNOWN";
            candidateMovementSamples = 0;
            return stableMovementType;
        }

        if (candidateMovementType.equals(proposed)) {
            candidateMovementSamples++;
        } else {
            candidateMovementType = proposed;
            candidateMovementSamples = 1;
        }

        if (candidateMovementSamples >= 2) {
            stableMovementType = proposed;
            candidateMovementType = "UNKNOWN";
            candidateMovementSamples = 0;
        }
        return stableMovementType;
    }

    @SuppressLint("MissingPermission")
    private void registerMovementTransitions() {
        if (!hasActivityRecognitionPermission()) {
            return;
        }

        List<ActivityTransition> transitions = new ArrayList<>();
        int[] activities = new int[]{
                DetectedActivity.STILL,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.IN_VEHICLE
        };
        for (int activity : activities) {
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(
                            ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    )
                    .build());
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(
                            ActivityTransition.ACTIVITY_TRANSITION_EXIT
                    )
                    .build());
        }

        activityRecognitionClient.requestActivityTransitionUpdates(
                new ActivityTransitionRequest(transitions),
                movementTransitionPendingIntent
        );
    }

    private void removeMovementTransitions() {
        if (activityRecognitionClient != null
                && movementTransitionPendingIntent != null
                && hasActivityRecognitionPermission()) {
            activityRecognitionClient.removeActivityTransitionUpdates(
                    movementTransitionPendingIntent
            );
        }
    }

    private boolean hasActivityRecognitionPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    private PendingIntent buildMovementTransitionPendingIntent() {
        Intent transitionIntent = new Intent(
                this,
                MovementTransitionReceiver.class
        ).setAction(
                MovementTransitionReceiver.ACTION_MOVEMENT_TRANSITION
        );
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(
                this,
                ACTIVITY_PENDING_INTENT_REQUEST,
                transitionIntent,
                pendingFlags
        );
    }

    private static final class MovementSnapshot {
        final double speedMetersPerSecond;
        @NonNull final String type;

        MovementSnapshot(
                double speedMetersPerSecond,
                @NonNull String type
        ) {
            this.speedMetersPerSecond = speedMetersPerSecond;
            this.type = type;
        }
    }

    @NonNull
    private BatterySnapshot readBatterySnapshot() {
        Intent batteryIntent = registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        if (batteryIntent == null) {
            return new BatterySnapshot(-1, false);
        }

        int level = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
        );
        int scale = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                -1
        );
        int status = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
        );
        int percentage = level >= 0 && scale > 0
                ? Math.min(100, Math.round(level * 100F / scale))
                : -1;
        boolean charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
        return new BatterySnapshot(percentage, charging);
    }

    private static final class BatterySnapshot {
        final int percentage;
        final boolean charging;

        BatterySnapshot(int percentage, boolean charging) {
            this.percentage = percentage;
            this.charging = charging;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.family_live_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(
                getString(R.string.family_live_notification_channel_detail)
        );
        getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
    }

    @NonNull
    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_family)
                .setContentTitle(
                        getString(R.string.family_live_notification_title)
                )
                .setContentText(
                        getString(R.string.family_live_notification_text)
                )
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                        0,
                        getString(R.string.family_live_stop_sharing),
                        stopPendingIntent
                )
                .build();
    }

    @Override
    public void onDestroy() {
        serviceDestroyed.set(true);
        unregisterNetworkCallback();
        removeUpdates();
        removeMovementTransitions();
        geocodeExecutor.shutdownNow();
        uploadQueueExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }
}
