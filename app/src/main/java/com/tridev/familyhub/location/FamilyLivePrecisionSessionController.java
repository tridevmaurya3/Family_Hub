package com.tridev.familyhub.location;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a short-lived, privacy-safe request for precision Family Live data.
 * Only active family members can create sessions under Firebase security rules.
 */
public final class FamilyLivePrecisionSessionController {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String sessionId = UUID.randomUUID()
            .toString()
            .replace("-", "");
    private final boolean continuous;

    @Nullable
    private DatabaseReference sessionReference;
    private boolean stopped;
    private int generation;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopped || !continuous || sessionReference == null) {
                return;
            }
            publishSession(
                    FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS,
                    "MAP_VISIBLE"
            );
            mainHandler.postDelayed(
                    this,
                    FamilyLivePrecisionPolicy.MAP_SESSION_HEARTBEAT_MS
            );
        }
    };

    public FamilyLivePrecisionSessionController(
            @NonNull Context context,
            boolean continuous
    ) {
        appContext = context.getApplicationContext();
        this.continuous = continuous;
    }

    public void start() {
        stopped = false;
        int requestGeneration = ++generation;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            return;
        }

        String viewerUid = user.getUid();
        FirebaseDatabase.getInstance().getReference()
                .child("users")
                .child(viewerUid)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (stopped || requestGeneration != generation) {
                        return;
                    }
                    String familyId = snapshot.child("familyId")
                            .getValue(String.class);
                    String status = snapshot.child("status")
                            .getValue(String.class);
                    if (familyId == null
                            || familyId.trim().isEmpty()
                            || !"ACTIVE".equals(status)) {
                        return;
                    }

                    sessionReference = FirebaseDatabase.getInstance()
                            .getReference()
                            .child("liveTrackingSessions")
                            .child(familyId.trim())
                            .child(sessionId);
                    sessionReference.onDisconnect().removeValue();

                    publishSession(
                            continuous
                                    ? FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS
                                    : FamilyLivePrecisionPolicy.ONE_SHOT_SESSION_TTL_MS,
                            continuous ? "MAP_VISIBLE" : "MANUAL_REFRESH"
                    );
                    requestOwnFreshLocation();

                    if (continuous) {
                        mainHandler.removeCallbacks(heartbeatRunnable);
                        mainHandler.postDelayed(
                                heartbeatRunnable,
                                FamilyLivePrecisionPolicy
                                        .MAP_SESSION_HEARTBEAT_MS
                        );
                    } else {
                        mainHandler.postDelayed(
                                this::stop,
                                FamilyLivePrecisionPolicy
                                        .ONE_SHOT_SESSION_TTL_MS
                                        + 2_000L
                        );
                    }
                });
    }

    public void refreshNow() {
        if (stopped) {
            return;
        }
        if (sessionReference == null) {
            start();
            return;
        }
        publishSession(
                continuous
                        ? FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS
                        : FamilyLivePrecisionPolicy.ONE_SHOT_SESSION_TTL_MS,
                continuous ? "MAP_VISIBLE" : "MANUAL_REFRESH"
        );
        requestOwnFreshLocation();
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        DatabaseReference reference = sessionReference;
        sessionReference = null;
        if (reference != null) {
            reference.onDisconnect().cancel();
            reference.removeValue();
        }
    }

    private void publishSession(long ttlMs, @NonNull String mode) {
        DatabaseReference reference = sessionReference;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (reference == null || user == null || stopped) {
            return;
        }

        Map<String, Object> values = new HashMap<>();
        values.put("viewerUid", user.getUid());
        values.put("active", true);
        values.put("requestedAt", ServerValue.TIMESTAMP);
        values.put("ttlMs", Math.min(
                FamilyLivePrecisionPolicy.MAP_SESSION_TTL_MS,
                Math.max(5_000L, ttlMs)
        ));
        values.put(
                "mode",
                FamilyLivePrecisionPolicy.sanitizeMode(mode)
        );
        reference.setValue(values);
    }

    private void requestOwnFreshLocation() {
        if (!LocationSharingStore.isSharingEnabled(appContext)) {
            return;
        }
        try {
            ContextCompat.startForegroundService(
                    appContext,
                    FamilyLocationService.forceRefreshIntent(appContext)
            );
        } catch (RuntimeException ignored) {
            // The existing foreground service or its recovery worker will retry.
        }
    }

    public static void requestOneShot(@NonNull Context context) {
        new FamilyLivePrecisionSessionController(context, false).start();
    }
}
