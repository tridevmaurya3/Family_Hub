package com.tridev.familyhub.feature.sos;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.R;

import java.util.Map;

/**
 * Keeps a Realtime Database listener while the app process is alive.
 * This is intentionally not a replacement for FCM when Android kills the app.
 */
public final class FamilySosLiveMonitor {

    private static final String CHANNEL_ID = "family_sos_alerts";
    private static final String PREFS = "family_sos_live_monitor";
    private static final String KEY_ALERT_PREFIX = "alert_";
    private static final String KEY_RESPONSE_PREFIX = "response_";

    private static final FamilySosLiveMonitor INSTANCE =
            new FamilySosLiveMonitor();

    @Nullable private Context appContext;
    @Nullable private FirebaseAuth.AuthStateListener authStateListener;
    @Nullable private DatabaseReference sosReference;
    @Nullable private ChildEventListener sosListener;
    @Nullable private String currentUid;
    private int generation;

    private FamilySosLiveMonitor() {
    }

    public static void start(@NonNull Context context) {
        INSTANCE.startInternal(context.getApplicationContext());
    }

    private synchronized void startInternal(@NonNull Context context) {
        appContext = context;
        if (authStateListener != null) {
            return;
        }
        createChannel(context);
        authStateListener = auth -> attachForUser(auth.getCurrentUser());
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener);
    }

    private synchronized void attachForUser(@Nullable FirebaseUser user) {
        detachSosListener();
        currentUid = null;
        if (user == null || !user.isEmailVerified() || appContext == null) {
            return;
        }
        currentUid = user.getUid();
        int requestGeneration = ++generation;
        FirebaseDatabase.getInstance()
                .getReference()
                .child("users")
                .child(user.getUid())
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (requestGeneration != generation) {
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
                    attachFamilySos(familyId.trim(), requestGeneration);
                });
    }

    private synchronized void attachFamilySos(
            @NonNull String familyId,
            int requestGeneration
    ) {
        if (appContext == null || requestGeneration != generation) {
            return;
        }
        sosReference = FirebaseDatabase.getInstance()
                .getReference()
                .child("familySos")
                .child(familyId);
        sosListener = new ChildEventListener() {
            @Override
            public void onChildAdded(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                handleSnapshot(snapshot);
            }

            @Override
            public void onChildChanged(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                handleSnapshot(snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                // History removal does not require a notification.
            }

            @Override
            public void onChildMoved(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                // Ordering changes do not require a notification.
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // The auth listener will reconnect after the next auth change.
            }
        };
        sosReference.addChildEventListener(sosListener);
    }

    private void handleSnapshot(@NonNull DataSnapshot snapshot) {
        Context context = appContext;
        String viewerUid = currentUid;
        if (context == null || viewerUid == null) {
            return;
        }
        FamilySosAlert alert = snapshot.getValue(FamilySosAlert.class);
        if (alert == null || alert.sosId.trim().isEmpty()) {
            if (alert != null && snapshot.getKey() != null) {
                alert.sosId = snapshot.getKey();
            }
        }
        if (alert == null || alert.sosId.trim().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (FamilySosPolicy.isActive(alert.status)
                && !viewerUid.equals(alert.senderUid)
                && FamilySosPolicy.shouldNotifyLive(
                        alert.effectiveCreatedAt(),
                        now
                )) {
            notifyIncomingSos(context, viewerUid, alert);
        }

        if (viewerUid.equals(alert.senderUid)
                && FamilySosPolicy.isActive(alert.status)) {
            notifyResponses(context, viewerUid, alert);
        }
    }

    private void notifyIncomingSos(
            @NonNull Context context,
            @NonNull String viewerUid,
            @NonNull FamilySosAlert alert
    ) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        String key = KEY_ALERT_PREFIX + viewerUid + "_" + alert.sosId;
        if (preferences.getBoolean(key, false)) {
            return;
        }
        preferences.edit().putBoolean(key, true).apply();

        String senderName = safeName(context, alert.senderName);
        String detail = alert.hasLocation && !alert.placeLabel.trim().isEmpty()
                ? context.getString(
                        R.string.family_sos_notification_with_place,
                        senderName,
                        alert.placeLabel.trim()
                )
                : context.getString(
                        R.string.family_sos_notification_without_place,
                        senderName
                );
        showNotification(
                context,
                ("sos:" + alert.sosId).hashCode(),
                context.getString(R.string.family_sos_notification_title),
                detail,
                NotificationCompat.CATEGORY_ALARM
        );
    }

    private void notifyResponses(
            @NonNull Context context,
            @NonNull String senderUid,
            @NonNull FamilySosAlert alert
    ) {
        if (alert.responses == null || alert.responses.isEmpty()) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        for (Map.Entry<String, FamilySosResponse> entry
                : alert.responses.entrySet()) {
            String responderUid = entry.getKey();
            FamilySosResponse response = entry.getValue();
            if (responderUid == null
                    || responderUid.trim().isEmpty()
                    || senderUid.equals(responderUid)
                    || response == null) {
                continue;
            }
            String key = KEY_RESPONSE_PREFIX
                    + senderUid
                    + "_"
                    + alert.sosId
                    + "_"
                    + responderUid;
            if (preferences.getBoolean(key, false)) {
                continue;
            }
            preferences.edit().putBoolean(key, true).apply();
            String responderName = safeName(context, response.displayName);
            showNotification(
                    context,
                    ("response:" + alert.sosId + ":" + responderUid)
                            .hashCode(),
                    context.getString(
                            R.string.family_sos_response_notification_title
                    ),
                    context.getString(
                            R.string.family_sos_response_notification_detail,
                            responderName
                    ),
                    NotificationCompat.CATEGORY_STATUS
            );
        }
    }

    private void showNotification(
            @NonNull Context context,
            int notificationId,
            @NonNull String title,
            @NonNull String detail,
            @NonNull String category
    ) {
        NotificationManager manager = context.getSystemService(
                NotificationManager.class
        );
        if (manager == null) {
            return;
        }

        Intent openIntent = new Intent(context, FamilySosActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            manager.notify(
                    notificationId,
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_family_sos)
                            .setContentTitle(title)
                            .setContentText(detail)
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(detail))
                            .setContentIntent(open)
                            .setCategory(category)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setVisibility(
                                    NotificationCompat.VISIBILITY_PRIVATE
                            )
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .build()
            );
        } catch (SecurityException ignored) {
            // The in-app SOS centre remains available without notifications.
        }
    }

    private void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(
                NotificationManager.class
        );
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.family_sos_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(
                R.string.family_sos_channel_description
        ));
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    private synchronized void detachSosListener() {
        generation++;
        if (sosReference != null && sosListener != null) {
            sosReference.removeEventListener(sosListener);
        }
        sosReference = null;
        sosListener = null;
    }

    @NonNull
    private static String safeName(
            @NonNull Context context,
            @Nullable String value
    ) {
        if (value == null || value.trim().isEmpty()) {
            return context.getString(R.string.family_sos_member_fallback);
        }
        return value.trim();
    }
}
