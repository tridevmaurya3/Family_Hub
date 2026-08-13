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
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.PendingLocationUploadDao;
import com.tridev.familyhub.data.local.entity.PendingLocationUpload;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.receiver.MovementTransitionReceiver;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Visible, consent-based Family Live sharing.
 *
 * The service adapts location frequency to movement and battery state, keeps
 * the newest failed location encrypted, continuously monitors permission/GPS/
 * internet health, and protects the last reliable position from invalid,
 * stale, mock or physically implausible points. While an authorised member is
 * actively viewing Family Map, a short-lived Firebase session temporarily
 * enables precision updates and automatically returns to adaptive tracking.
 */
public class FamilyLocationService extends Service {

    public static final String ACTION_START =
            "com.tridev.familyhub.action.START_LOCATION_SHARING";
    public static final String ACTION_STOP =
            "com.tridev.familyhub.action.STOP_LOCATION_SHARING";
    public static final String ACTION_FORCE_REFRESH =
            "com.tridev.familyhub.action.FORCE_FRESH_LOCATION";

    private static final String CHANNEL_ID = "family_live_location";
    private static final int NOTIFICATION_ID = 4102;
    private static final long ACTIVITY_FRESHNESS_MS = 2L * 60L * 1000L;
    private static final long GEOCODE_INTERVAL_MS = 15L * 60L * 1000L;
    private static final float GEOCODE_DISTANCE_METERS = 500F;
    private static final int ACTIVITY_PENDING_INTENT_REQUEST = 4203;
    private static final int MAX_QUEUED_LOCATIONS = 1;

    private static final long PROFILE_EVALUATION_INTERVAL_MS = 45_000L;
    private static final long PROFILE_SWITCH_MIN_DWELL_MS = 45_000L;
    private static final int PROFILE_CONFIRMATION_SAMPLES = 2;
    private static final long LOCATION_REQUEST_RETRY_BASE_MS = 15_000L;
    private static final long LOCATION_REQUEST_RETRY_MAX_MS =
            5L * 60L * 1000L;
    private static final long DEVICE_HEALTH_CHECK_INTERVAL_MS = 20_000L;
    private static final long NETWORK_RECHECK_DELAY_MS = 1_200L;
    private static final long IMMEDIATE_LOCATION_TIMEOUT_MS = 12_000L;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityRecognitionClient activityRecognitionClient;
    private PendingIntent movementTransitionPendingIntent;
    private LocationCallback locationCallback;
    private DatabaseReference locationReference;
    private DatabaseReference precisionSessionReference;
    private ValueEventListener precisionSessionListener;
    private PendingLocationUploadDao pendingLocationUploadDao;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private CancellationTokenSource immediateLocationCancellation;
    private String familyId;
    private String userId;

    private boolean requestingUpdates;
    private boolean trackingProfileChangeInProgress;
    private boolean familySessionReady;
    private boolean familySessionInitializationInProgress;
    private boolean networkAvailable;
    private boolean waitingForFreshLocation;
    private boolean waitingForReliableLocation;
    private boolean precisionLiveMode;
    private boolean immediateRefreshInProgress;
    private boolean pendingImmediateRefresh;
    private long precisionActiveUntil;

    @NonNull
    private String currentTrackingProfile =
            AdaptiveLocationPolicy.PROFILE_NORMAL;
    @NonNull
    private String candidateTrackingProfile =
            AdaptiveLocationPolicy.PROFILE_NORMAL;
    @NonNull
    private String currentDeviceHealth = LocationDeviceHealth.READY;
    @NonNull
    private String lastPublishedHealthReason = "";

    private int candidateTrackingSamples;
    private long currentProfileAppliedAt;
    private int locationRequestRetryAttempt;
    private int familySessionGeneration;

    @Nullable
    private Location previousMovementLocation;
    @Nullable
    private Location lastAcceptedLocation;
    @Nullable
    private Location suspiciousJumpCandidate;

    @NonNull
    private String stableMovementType = "UNKNOWN";
    @NonNull
    private String candidateMovementType = "UNKNOWN";
    private int candidateMovementSamples;

    private final ExecutorService geocodeExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService uploadQueueExecutor =
            Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean serviceDestroyed =
            new AtomicBoolean(false);

    @Nullable
    private Location lastGeocodedLocation;
    private long lastGeocodedAt;
    private boolean geocodeInProgress;
    @NonNull
    private String cachedPlaceLabel = "";

    private final Runnable profileEvaluationRunnable = new Runnable() {
        @Override
        public void run() {
            if (serviceDestroyed.get()
                    || !LocationSharingStore.isSharingEnabled(
                    FamilyLocationService.this
            )) {
                return;
            }

            if (!LocationDeviceHealth.blocksLocationUpdates(
                    currentDeviceHealth
            )) {
                evaluateTrackingProfileFromDeviceState();
            }

            mainHandler.postDelayed(
                    this,
                    PROFILE_EVALUATION_INTERVAL_MS
            );
        }
    };

    private final Runnable deviceHealthRunnable = new Runnable() {
        @Override
        public void run() {
            if (serviceDestroyed.get()
                    || !LocationSharingStore.isSharingEnabled(
                    FamilyLocationService.this
            )) {
                return;
            }

            evaluateDeviceHealth(false);
            mainHandler.postDelayed(
                    this,
                    DEVICE_HEALTH_CHECK_INTERVAL_MS
            );
        }
    };

    private final Runnable locationRequestRetryRunnable = () -> {
        if (serviceDestroyed.get()
                || requestingUpdates
                || !familySessionReady
                || !LocationSharingStore.isSharingEnabled(this)
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }
        requestLocationUpdates(resolveCurrentDeviceConfig());
    };

    private final Runnable precisionExpiryRunnable = () -> {
        if (serviceDestroyed.get() || !precisionLiveMode) {
            return;
        }
        if (System.currentTimeMillis() >= precisionActiveUntil) {
            setPrecisionLiveMode(false, 0L);
        }
    };

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

    @NonNull
    public static Intent forceRefreshIntent(@NonNull Context context) {
        return new Intent(context, FamilyLocationService.class)
                .setAction(ACTION_FORCE_REFRESH);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(this);
        activityRecognitionClient = ActivityRecognition.getClient(this);
        movementTransitionPendingIntent =
                buildMovementTransitionPendingIntent();
        pendingLocationUploadDao = FamilyHubDatabase
                .getInstance(this)
                .pendingLocationUploadDao();

        connectivityManager = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
        networkAvailable = isValidatedInternetAvailable();
        currentDeviceHealth = LocationDeviceHealth.resolve(
                hasLocationPermission(),
                isDeviceLocationEnabled(),
                networkAvailable
        );

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location latest = result.getLastLocation();
                if (latest != null) {
                    publishLocation(latest, false);
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

        startForeground(NOTIFICATION_ID, buildNotification(false));

        boolean forceRefresh = intent != null
                && ACTION_FORCE_REFRESH.equals(intent.getAction());
        if (forceRefresh
                && !LocationSharingStore.isSharingEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (familySessionReady) {
            if (forceRefresh) {
                requestImmediateFreshLocation();
            }
            return START_STICKY;
        }
        pendingImmediateRefresh = pendingImmediateRefresh || forceRefresh;
        if (familySessionInitializationInProgress) {
            return START_STICKY;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            stopWithoutRestart();
            return START_NOT_STICKY;
        }

        userId = user.getUid();
        familySessionInitializationInProgress = true;
        int sessionGeneration = ++familySessionGeneration;
        DatabaseReference firebaseRoot = FirebaseDatabase
                .getInstance()
                .getReference();
        firebaseRoot
                .child("users")
                .child(userId)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (!isCurrentFamilySessionInitialization(
                            sessionGeneration
                    )) {
                        return;
                    }
                    String resolvedFamilyId =
                            snapshot.child("familyId").getValue(String.class);
                    String status =
                            snapshot.child("status").getValue(String.class);

                    if (resolvedFamilyId == null
                            || resolvedFamilyId.trim().isEmpty()
                            || !"ACTIVE".equals(status)) {
                        stopSharing();
                        return;
                    }
                    verifyMembershipAndStartSession(
                            firebaseRoot,
                            resolvedFamilyId.trim(),
                            sessionGeneration
                    );
                })
                .addOnFailureListener(error -> {
                    if (isCurrentFamilySessionInitialization(
                            sessionGeneration
                    )) {
                        stopSharing();
                    }
                });

        return START_STICKY;
    }

    private void verifyMembershipAndStartSession(
            @NonNull DatabaseReference firebaseRoot,
            @NonNull String resolvedFamilyId,
            int sessionGeneration
    ) {
        firebaseRoot.child("memberships")
                .child(resolvedFamilyId)
                .child(userId)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (!isCurrentFamilySessionInitialization(
                            sessionGeneration
                    )) {
                        return;
                    }
                    String membershipUid = snapshot.child("uid")
                            .getValue(String.class);
                    String membershipStatus = snapshot.child("status")
                            .getValue(String.class);
                    if (!userId.equals(membershipUid)
                            || !"ACTIVE".equals(membershipStatus)) {
                        stopSharing();
                        return;
                    }

                    familyId = resolvedFamilyId;
                    locationReference = firebaseRoot
                            .child("locations")
                            .child(familyId)
                            .child(userId);
                    familySessionInitializationInProgress = false;
                    familySessionReady = true;
                    registerDisconnectState();
                    attachPrecisionSessionListener();

                    LocationSharingStore.setSharingEnabled(this, true);
                    registerMovementTransitions();
                    scheduleProfileEvaluation();
                    scheduleDeviceHealthMonitor();
                    evaluateDeviceHealth(true);

                    if (pendingImmediateRefresh) {
                        pendingImmediateRefresh = false;
                        requestImmediateFreshLocation();
                    }
                })
                .addOnFailureListener(error -> {
                    if (isCurrentFamilySessionInitialization(
                            sessionGeneration
                    )) {
                        stopSharing();
                    }
                });
    }

    private boolean isCurrentFamilySessionInitialization(int generation) {
        return !serviceDestroyed.get()
                && familySessionInitializationInProgress
                && generation == familySessionGeneration;
    }

    @NonNull
    private AdaptiveLocationPolicy.Config resolveCurrentDeviceConfig() {
        BatterySnapshot battery = readBatterySnapshot();
        if (precisionLiveMode
                && FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                battery.percentage,
                battery.charging
        )) {
            return AdaptiveLocationPolicy.configFor(
                    AdaptiveLocationPolicy.PROFILE_LIVE_VIEW
            );
        }
        return AdaptiveLocationPolicy.resolve(
                resolvePolicyMovementType(),
                battery.percentage,
                battery.charging,
                isPowerSaveMode()
        );
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates(
            @NonNull AdaptiveLocationPolicy.Config config
    ) {
        if (requestingUpdates
                || trackingProfileChangeInProgress
                || !familySessionReady
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }

        LocationRequest.Builder builder = new LocationRequest.Builder(
                config.priority,
                config.intervalMs
        )
                .setMinUpdateIntervalMillis(config.minIntervalMs)
                .setMinUpdateDistanceMeters(config.minDistanceMeters)
                .setWaitForAccurateLocation(
                        config.waitForAccurateLocation
                );

        if (config.maxUpdateDelayMs > config.intervalMs) {
            builder.setMaxUpdateDelayMillis(config.maxUpdateDelayMs);
        }

        requestingUpdates = true;
        fusedLocationClient.requestLocationUpdates(
                builder.build(),
                locationCallback,
                getMainLooper()
        ).addOnSuccessListener(ignored -> {
            currentTrackingProfile = config.profile;
            currentProfileAppliedAt = System.currentTimeMillis();
            candidateTrackingProfile = config.profile;
            candidateTrackingSamples = 0;
            locationRequestRetryAttempt = 0;
            mainHandler.removeCallbacks(locationRequestRetryRunnable);
            updateForegroundNotification(false);
        }).addOnFailureListener(error -> {
            requestingUpdates = false;
            scheduleLocationRequestRetry();
        });
    }

    private void evaluateTrackingProfileFromDeviceState() {
        AdaptiveLocationPolicy.Config desired = resolveCurrentDeviceConfig();
        applyDesiredTrackingConfig(desired);
    }

    private void applyDesiredTrackingConfig(
            @NonNull AdaptiveLocationPolicy.Config desired
    ) {
        if (desired.profile.equals(currentTrackingProfile)) {
            candidateTrackingProfile = desired.profile;
            candidateTrackingSamples = 0;
            return;
        }

        boolean immediate = AdaptiveLocationPolicy
                .isImmediateSafetyProfile(desired.profile);
        long profileAge = System.currentTimeMillis()
                - currentProfileAppliedAt;

        if (!immediate
                && currentProfileAppliedAt > 0L
                && profileAge < PROFILE_SWITCH_MIN_DWELL_MS) {
            return;
        }

        if (candidateTrackingProfile.equals(desired.profile)) {
            candidateTrackingSamples++;
        } else {
            candidateTrackingProfile = desired.profile;
            candidateTrackingSamples = 1;
        }

        int requiredSamples = immediate
                ? 1
                : PROFILE_CONFIRMATION_SAMPLES;
        if (candidateTrackingSamples < requiredSamples) {
            return;
        }

        switchTrackingProfile(desired);
    }

    private void switchTrackingProfile(
            @NonNull AdaptiveLocationPolicy.Config desired
    ) {
        if (trackingProfileChangeInProgress) {
            return;
        }

        candidateTrackingSamples = 0;
        trackingProfileChangeInProgress = true;
        mainHandler.removeCallbacks(locationRequestRetryRunnable);

        if (!requestingUpdates) {
            trackingProfileChangeInProgress = false;
            requestLocationUpdates(desired);
            return;
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener(task -> {
                    requestingUpdates = false;
                    trackingProfileChangeInProgress = false;
                    if (LocationSharingStore.isSharingEnabled(this)
                            && !LocationDeviceHealth.blocksLocationUpdates(
                            currentDeviceHealth
                    )) {
                        requestLocationUpdates(desired);
                    }
                });
    }

    private void scheduleProfileEvaluation() {
        mainHandler.removeCallbacks(profileEvaluationRunnable);
        mainHandler.postDelayed(
                profileEvaluationRunnable,
                PROFILE_EVALUATION_INTERVAL_MS
        );
    }

    private void scheduleDeviceHealthMonitor() {
        mainHandler.removeCallbacks(deviceHealthRunnable);
        mainHandler.postDelayed(
                deviceHealthRunnable,
                DEVICE_HEALTH_CHECK_INTERVAL_MS
        );
    }

    private void scheduleLocationRequestRetry() {
        if (!LocationSharingStore.isSharingEnabled(this)
                || serviceDestroyed.get()
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }

        int exponent = Math.min(locationRequestRetryAttempt, 5);
        long delay = Math.min(
                LOCATION_REQUEST_RETRY_MAX_MS,
                LOCATION_REQUEST_RETRY_BASE_MS * (1L << exponent)
        );
        locationRequestRetryAttempt++;
        updateForegroundNotification(true);
        mainHandler.removeCallbacks(locationRequestRetryRunnable);
        mainHandler.postDelayed(locationRequestRetryRunnable, delay);
    }

    private void attachPrecisionSessionListener() {
        detachPrecisionSessionListener();
        if (familyId == null || familyId.isEmpty()) {
            return;
        }

        precisionSessionReference = FirebaseDatabase.getInstance()
                .getReference()
                .child("liveTrackingSessions")
                .child(familyId);
        precisionSessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                evaluatePrecisionSessions(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                setPrecisionLiveMode(false, 0L);
            }
        };
        precisionSessionReference.addValueEventListener(
                precisionSessionListener
        );
    }

    private void evaluatePrecisionSessions(@NonNull DataSnapshot snapshot) {
        long now = System.currentTimeMillis();
        long activeUntil = 0L;
        for (DataSnapshot child : snapshot.getChildren()) {
            Boolean active = child.child("active").getValue(Boolean.class);
            Long requestedAt = child.child("requestedAt").getValue(Long.class);
            Long ttlMs = child.child("ttlMs").getValue(Long.class);
            if (!Boolean.TRUE.equals(active)
                    || requestedAt == null
                    || ttlMs == null) {
                continue;
            }
            long expiry = FamilyLivePrecisionPolicy.safeExpiry(
                    requestedAt,
                    ttlMs
            );
            if (FamilyLivePrecisionPolicy.isSessionActive(
                    true,
                    requestedAt,
                    expiry,
                    now
            )) {
                activeUntil = Math.max(activeUntil, expiry);
            }
        }
        setPrecisionLiveMode(activeUntil > now, activeUntil);
    }

    private void setPrecisionLiveMode(boolean requested, long activeUntil) {
        BatterySnapshot battery = readBatterySnapshot();
        boolean enabled = requested
                && FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                battery.percentage,
                battery.charging
        );

        precisionActiveUntil = enabled ? activeUntil : 0L;
        mainHandler.removeCallbacks(precisionExpiryRunnable);
        if (enabled) {
            long delay = Math.max(
                    1_000L,
                    activeUntil - System.currentTimeMillis() + 250L
            );
            mainHandler.postDelayed(precisionExpiryRunnable, delay);
        }

        if (precisionLiveMode == enabled) {
            return;
        }

        precisionLiveMode = enabled;
        AdaptiveLocationPolicy.Config desired = resolveCurrentDeviceConfig();
        switchTrackingProfile(desired);
        if (enabled) {
            waitingForFreshLocation = true;
            requestImmediateFreshLocation();
        }
        updateForegroundNotification(false);
    }

    private void detachPrecisionSessionListener() {
        mainHandler.removeCallbacks(precisionExpiryRunnable);
        if (precisionSessionReference != null
                && precisionSessionListener != null) {
            precisionSessionReference.removeEventListener(
                    precisionSessionListener
            );
        }
        precisionSessionReference = null;
        precisionSessionListener = null;
        precisionLiveMode = false;
        precisionActiveUntil = 0L;
    }

    @SuppressLint("MissingPermission")
    private void requestImmediateFreshLocation() {
        if (immediateRefreshInProgress
                || !familySessionReady
                || !hasLocationPermission()
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }

        immediateRefreshInProgress = true;
        waitingForFreshLocation = true;
        if (immediateLocationCancellation != null) {
            immediateLocationCancellation.cancel();
        }
        immediateLocationCancellation = new CancellationTokenSource();

        CurrentLocationRequest request =
                new CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0L)
                        .setDurationMillis(IMMEDIATE_LOCATION_TIMEOUT_MS)
                        .build();

        fusedLocationClient.getCurrentLocation(
                request,
                immediateLocationCancellation.getToken()
        ).addOnSuccessListener(location -> {
            if (location != null) {
                publishLocation(location, false);
            }
        }).addOnCompleteListener(task -> {
            immediateRefreshInProgress = false;
            immediateLocationCancellation = null;
            if (!task.isSuccessful()) {
                updateForegroundNotification(true);
            }
        });
    }

    private void evaluateDeviceHealth(boolean forcePublish) {
        if (!LocationSharingStore.isSharingEnabled(this)) {
            return;
        }

        networkAvailable = isValidatedInternetAvailable();
        String previousState = currentDeviceHealth;
        String resolvedState = LocationDeviceHealth.resolve(
                hasLocationPermission(),
                isDeviceLocationEnabled(),
                networkAvailable
        );
        boolean changed = !resolvedState.equals(previousState);
        currentDeviceHealth = resolvedState;

        if (LocationDeviceHealth.blocksLocationUpdates(resolvedState)) {
            removeUpdates();
            mainHandler.removeCallbacks(locationRequestRetryRunnable);
            waitingForFreshLocation = false;
            waitingForReliableLocation = false;

            if (networkAvailable
                    && familySessionReady
                    && (forcePublish
                    || changed
                    || !LocationDeviceHealth.availabilityReason(
                    resolvedState
            ).equals(lastPublishedHealthReason))) {
                publishUnavailableState(
                        LocationDeviceHealth.availabilityReason(
                                resolvedState
                        )
                );
            }

            if (forcePublish || changed) {
                LocationRecoveryNotifier.showResumeRequired(this);
            }
        } else {
            LocationRecoveryNotifier.cancelResumeRequired(this);

            if (familySessionReady && !requestingUpdates) {
                waitingForFreshLocation = true;
                requestLocationUpdates(resolveCurrentDeviceConfig());
                publishLastKnownLocationIfAvailable();
            }

            if (LocationDeviceHealth.isReady(resolvedState)) {
                flushQueuedLocations();
                if (changed && !LocationDeviceHealth.isReady(previousState)) {
                    waitingForFreshLocation = true;
                    publishLastKnownLocationIfAvailable();
                }
            }
        }

        if (forcePublish || changed) {
            updateForegroundNotification(false);
        }
    }

    @SuppressLint("MissingPermission")
    private void publishLastKnownLocationIfAvailable() {
        if (!familySessionReady
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        publishLocation(location, true);
                    }
                })
                .addOnFailureListener(ignored -> {
                    // Continuous updates remain active and will provide a fresh
                    // reliable point when Android has one available.
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

    private void publishLocation(
            @NonNull Location location,
            boolean lastKnownSource
    ) {
        if (locationReference == null
                || familyId == null
                || userId == null
                || LocationDeviceHealth.blocksLocationUpdates(
                currentDeviceHealth
        )) {
            return;
        }

        if (!acceptReliableLocation(location, lastKnownSource)) {
            waitingForReliableLocation = true;
            updateForegroundNotification(false);
            return;
        }

        waitingForReliableLocation = false;
        BatterySnapshot battery = readBatterySnapshot();
        MovementSnapshot movement = resolveMovement(location);
        AdaptiveLocationPolicy.Config desired;
        if (precisionLiveMode
                && FamilyLivePrecisionPolicy.canUsePrecisionTracking(
                battery.percentage,
                battery.charging
        )) {
            desired = AdaptiveLocationPolicy.configFor(
                    AdaptiveLocationPolicy.PROFILE_LIVE_VIEW
            );
        } else {
            desired = AdaptiveLocationPolicy.resolve(
                    movement.type,
                    battery.percentage,
                    battery.charging,
                    isPowerSaveMode()
            );
        }
        applyDesiredTrackingConfig(desired);

        AdaptiveLocationPolicy.Config applied =
                AdaptiveLocationPolicy.configFor(currentTrackingProfile);
        LocationPointPolicy.Point point = toPoint(location);
        long capturedAt = LocationPointPolicy.safeCaptureTimeMs(
                point,
                System.currentTimeMillis()
        );
        long locationAgeMs = Math.max(
                0L,
                LocationPointPolicy.pointAgeMs(
                        point,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtimeNanos()
                )
        );

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
        values.put("trackingProfile", currentTrackingProfile);
        values.put(
                "trackingIntervalSeconds",
                applied.intervalMs / 1000L
        );
        values.put("precisionLive", precisionLiveMode);
        values.put("deviceHealth", currentDeviceHealth);
        values.put("locationQuality", "RELIABLE");
        values.put(
                "locationSource",
                lastKnownSource ? "LAST_KNOWN" : "FUSED_LIVE"
        );
        values.put("locationAgeSeconds", locationAgeMs / 1000L);

        if (!cachedPlaceLabel.isEmpty()) {
            values.put("placeLabel", cachedPlaceLabel);
        }

        values.put("batteryPercentage", battery.percentage);
        values.put("charging", battery.charging);
        values.put("online", true);
        values.put("sharingEnabled", true);

        String availabilityReason = isPowerSaveMode()
                ? FamilyLiveAvailability.BATTERY_SAVER
                : FamilyLiveAvailability.AVAILABLE;
        values.put("availabilityReason", availabilityReason);
        values.put("clientTimestamp", capturedAt);
        values.put(
                "clientUpdateId",
                LocationSyncPolicy.createUpdateId(
                        familyId,
                        userId,
                        capturedAt,
                        location.getLatitude(),
                        location.getLongitude()
                )
        );
        values.put("updatedAt", ServerValue.TIMESTAMP);
        values.put("locationUpdatedAt", ServerValue.TIMESTAMP);

        if (LocationDeviceHealth.shouldQueueOffline(currentDeviceHealth)
                || !networkAvailable) {
            enqueueLocationForRetry(values);
            updateForegroundNotification(false);
            resolvePlaceLabelIfNeeded(location);
            return;
        }

        locationReference.updateChildren(values)
                .addOnSuccessListener(ignored -> {
                    waitingForFreshLocation = false;
                    lastPublishedHealthReason = availabilityReason;
                    LocationRecoveryNotifier.cancelResumeRequired(this);
                    flushQueuedLocations();
                    updateForegroundNotification(false);
                })
                .addOnFailureListener(error -> {
                    enqueueLocationForRetry(values);
                    updateForegroundNotification(true);
                });

        resolvePlaceLabelIfNeeded(location);
    }

    private boolean acceptReliableLocation(
            @NonNull Location location,
            boolean lastKnownSource
    ) {
        LocationPointPolicy.Point current = toPoint(location);
        LocationPointPolicy.Point previous = lastAcceptedLocation == null
                ? null
                : toPoint(lastAcceptedLocation);
        String decision = LocationPointPolicy.evaluate(
                current,
                previous,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos(),
                lastKnownSource,
                isMockLocation(location)
        );

        if (LocationPointPolicy.ACCEPT.equals(decision)) {
            lastAcceptedLocation = new Location(location);
            suspiciousJumpCandidate = null;
            return true;
        }

        if (!LocationPointPolicy.REQUIRE_JUMP_CONFIRMATION.equals(decision)) {
            return false;
        }

        if (suspiciousJumpCandidate != null
                && LocationPointPolicy.confirmsSuspiciousJump(
                toPoint(suspiciousJumpCandidate),
                current
        )) {
            lastAcceptedLocation = new Location(location);
            suspiciousJumpCandidate = null;
            return true;
        }

        suspiciousJumpCandidate = new Location(location);
        return false;
    }

    @NonNull
    private LocationPointPolicy.Point toPoint(@NonNull Location location) {
        return new LocationPointPolicy.Point(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                location.getTime(),
                location.getElapsedRealtimeNanos()
        );
    }

    @SuppressWarnings("deprecation")
    private boolean isMockLocation(@NonNull Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private void enqueueLocationForRetry(
            @NonNull Map<String, Object> sourceValues
    ) {
        Map<String, Object> queueValues = new HashMap<>(sourceValues);
        queueValues.remove("updatedAt");
        queueValues.remove("locationUpdatedAt");

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
        if (!networkAvailable
                || !familySessionReady
                || !LocationSharingStore.isSharingEnabled(this)
                || serviceDestroyed.get()) {
            return;
        }
        PendingLocationSyncScheduler.schedule(this);
    }

    private void executeQueueTask(@NonNull Runnable task) {
        if (serviceDestroyed.get()) {
            return;
        }

        try {
            uploadQueueExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The service is already shutting down.
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) {
            return;
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                scheduleNetworkHealthRefresh(0L);
            }

            @Override
            public void onCapabilitiesChanged(
                    @NonNull Network network,
                    @NonNull NetworkCapabilities capabilities
            ) {
                scheduleNetworkHealthRefresh(0L);
            }

            @Override
            public void onLost(@NonNull Network network) {
                scheduleNetworkHealthRefresh(NETWORK_RECHECK_DELAY_MS);
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

    private void scheduleNetworkHealthRefresh(long delayMs) {
        mainHandler.postDelayed(
                () -> evaluateDeviceHealth(false),
                Math.max(0L, delayMs)
        );
    }

    private boolean isValidatedInternetAvailable() {
        if (connectivityManager == null) {
            return false;
        }

        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }

            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null
                    && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
                    && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
            );
        } catch (RuntimeException ignored) {
            return false;
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
                // Sharing must continue if reverse geocoding is unavailable.
            }

            final String resolvedLabel = label;
            mainHandler.post(() -> {
                geocodeInProgress = false;
                lastGeocodedAt = System.currentTimeMillis();
                lastGeocodedLocation = requestedLocation;

                if (resolvedLabel.isEmpty()) {
                    return;
                }

                cachedPlaceLabel = resolvedLabel;
                if (locationReference != null && networkAvailable) {
                    locationReference.child("placeLabel")
                            .setValue(resolvedLabel);
                }
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
        familySessionGeneration++;
        familySessionInitializationInProgress = false;
        familySessionReady = false;
        detachPrecisionSessionListener();
        removeScheduledWork();
        removeUpdates();
        removeMovementTransitions();
        resetLocationQualityState();
        MovementActivityStore.clear(this);
        LocationRecoveryNotifier.cancelAll(this);

        if (locationReference != null) {
            Map<String, Object> stopped = new HashMap<>();
            stopped.put("sharingEnabled", false);
            stopped.put("online", false);
            stopped.put("precisionLive", false);
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
        familySessionGeneration++;
        familySessionInitializationInProgress = false;
        familySessionReady = false;
        detachPrecisionSessionListener();
        removeScheduledWork();
        removeUpdates();
        removeMovementTransitions();
        resetLocationQualityState();
        MovementActivityStore.clear(this);
        LocationRecoveryNotifier.cancelAll(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void resetLocationQualityState() {
        previousMovementLocation = null;
        lastAcceptedLocation = null;
        suspiciousJumpCandidate = null;
        waitingForFreshLocation = false;
        waitingForReliableLocation = false;
    }

    private void removeScheduledWork() {
        mainHandler.removeCallbacks(profileEvaluationRunnable);
        mainHandler.removeCallbacks(deviceHealthRunnable);
        mainHandler.removeCallbacks(locationRequestRetryRunnable);
        mainHandler.removeCallbacks(precisionExpiryRunnable);
    }

    private void publishUnavailableState(@NonNull String reason) {
        if (locationReference == null || !networkAvailable) {
            return;
        }

        Map<String, Object> unavailable = new HashMap<>();
        unavailable.put("availabilityReason", reason);
        unavailable.put("deviceHealth", currentDeviceHealth);
        unavailable.put("sharingEnabled", true);
        unavailable.put("online", networkAvailable);
        unavailable.put("precisionLive", precisionLiveMode);
        unavailable.put("healthUpdatedAt", ServerValue.TIMESTAMP);
        unavailable.put("updatedAt", ServerValue.TIMESTAMP);

        locationReference.updateChildren(unavailable)
                .addOnSuccessListener(ignored ->
                        lastPublishedHealthReason = reason);
    }

    private void removeUpdates() {
        if (requestingUpdates) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (immediateLocationCancellation != null) {
            immediateLocationCancellation.cancel();
            immediateLocationCancellation = null;
        }
        immediateRefreshInProgress = false;
        requestingUpdates = false;
        trackingProfileChangeInProgress = false;
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
        if (manager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }

        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
        );
    }

    private boolean isPowerSaveMode() {
        PowerManager manager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isPowerSaveMode();
    }

    @NonNull
    private String resolvePolicyMovementType() {
        MovementActivityStore.Snapshot activity =
                MovementActivityStore.read(this);

        if (!activity.isFresh(
                System.currentTimeMillis(),
                ACTIVITY_FRESHNESS_MS
        )) {
            return stableMovementType;
        }

        switch (activity.type) {
            case MovementActivityStore.STILL:
                return "STATIONARY";
            case MovementActivityStore.WALKING:
            case MovementActivityStore.RUNNING:
                return "WALKING";
            case MovementActivityStore.CYCLING:
                return "CYCLING";
            case MovementActivityStore.IN_VEHICLE:
                return "TRAVELLING";
            default:
                return stableMovementType;
        }
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
        @NonNull
        final String type;

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

    private void updateForegroundNotification(boolean retrying) {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        if (manager != null) {
            manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(retrying)
            );
        }
    }

    @NonNull
    private Notification buildNotification(boolean retrying) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_family)
                .setContentTitle(
                        getString(R.string.family_live_notification_title)
                )
                .setContentText(trackingNotificationText(retrying))
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

    @NonNull
    private String trackingNotificationText(boolean retrying) {
        if (LocationDeviceHealth.PERMISSION_OFF.equals(currentDeviceHealth)) {
            return getString(
                    R.string.family_live_tracking_permission_off
            );
        }
        if (LocationDeviceHealth.GPS_OFF.equals(currentDeviceHealth)) {
            return getString(R.string.family_live_tracking_gps_off);
        }
        if (LocationDeviceHealth.INTERNET_OFF.equals(currentDeviceHealth)) {
            return getString(R.string.family_live_tracking_internet_off);
        }
        if (waitingForReliableLocation) {
            return getString(
                    R.string.family_live_tracking_quality_waiting
            );
        }
        if (waitingForFreshLocation) {
            return getString(R.string.family_live_tracking_restoring);
        }
        if (retrying) {
            return getString(R.string.family_live_tracking_retrying);
        }

        switch (currentTrackingProfile) {
            case AdaptiveLocationPolicy.PROFILE_LIVE_VIEW:
                return getString(
                        R.string.family_live_tracking_precision
                );
            case AdaptiveLocationPolicy.PROFILE_ACTIVE:
                return getString(R.string.family_live_tracking_active);
            case AdaptiveLocationPolicy.PROFILE_TRAVELLING:
                return getString(
                        R.string.family_live_tracking_travelling
                );
            case AdaptiveLocationPolicy.PROFILE_STATIONARY:
                return getString(
                        R.string.family_live_tracking_stationary
                );
            case AdaptiveLocationPolicy.PROFILE_POWER_SAVER:
                return getString(
                        R.string.family_live_tracking_power_saver
                );
            case AdaptiveLocationPolicy.PROFILE_LOW_BATTERY:
                return getString(
                        R.string.family_live_tracking_low_battery
                );
            default:
                return getString(R.string.family_live_tracking_normal);
        }
    }

    @Override
    public void onDestroy() {
        serviceDestroyed.set(true);
        detachPrecisionSessionListener();
        removeScheduledWork();
        unregisterNetworkCallback();
        removeUpdates();
        removeMovementTransitions();
        geocodeExecutor.shutdownNow();
        uploadQueueExecutor.shutdownNow();

        if (LocationSharingStore.isSharingEnabled(this)) {
            LocationServiceRecoveryScheduler.scheduleNow(this);
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }
}
