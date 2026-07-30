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
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;

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
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.receiver.MovementTransitionReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final long ACTIVITY_FRESHNESS_MS = 2L * 60L * 1000L;
    private static final int ACTIVITY_PENDING_INTENT_REQUEST = 4203;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityRecognitionClient activityRecognitionClient;
    private PendingIntent movementTransitionPendingIntent;
    private LocationCallback locationCallback;
    private DatabaseReference locationReference;
    private String familyId;
    private String userId;
    private boolean requestingUpdates;
    @Nullable private Location previousMovementLocation;
    @NonNull private String stableMovementType = "UNKNOWN";
    @NonNull private String candidateMovementType = "UNKNOWN";
    private int candidateMovementSamples;

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

        if (!hasLocationPermission()) {
            stopWithoutRestart();
            return START_NOT_STICKY;
        }

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
                    LocationSharingStore.setSharingEnabled(this, true);
                    registerMovementTransitions();
                    requestLocationUpdates();
                })
                .addOnFailureListener(error -> stopSharing());

        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates() {
        if (requestingUpdates || !hasLocationPermission()) {
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                UPDATE_INTERVAL_MS
        )
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                .setWaitForAccurateLocation(false)
                .build();

        requestingUpdates = true;
        fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                getMainLooper()
        ).addOnFailureListener(error -> {
            requestingUpdates = false;
            stopSharing();
        });
    }

    private void registerDisconnectState() {
        if (locationReference == null) {
            return;
        }
        Map<String, Object> disconnected = new HashMap<>();
        disconnected.put("online", false);
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
        values.put("batteryPercentage", battery.percentage);
        values.put("charging", battery.charging);
        values.put("online", true);
        values.put("sharingEnabled", true);
        values.put("clientTimestamp", System.currentTimeMillis());
        values.put("updatedAt", ServerValue.TIMESTAMP);
        locationReference.updateChildren(values);
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
        removeUpdates();
        removeMovementTransitions();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }
}
