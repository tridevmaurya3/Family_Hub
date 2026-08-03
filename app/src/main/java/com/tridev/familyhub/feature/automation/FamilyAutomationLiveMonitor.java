package com.tridev.familyhub.feature.automation;

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

import java.util.ArrayList;
import java.util.List;

/** Process-active notification monitor; FCM is intentionally not required. */
public final class FamilyAutomationLiveMonitor {

    private static final String CHANNEL_ID = "family_automation_alerts";
    private static final String PREFS = "family_automation_monitor";
    private static final long MAX_LIVE_EVENT_AGE_MS = 20L * 60L * 1000L;

    private static final FamilyAutomationLiveMonitor INSTANCE =
            new FamilyAutomationLiveMonitor();

    @Nullable private Context appContext;
    @Nullable private FirebaseAuth.AuthStateListener authListener;
    @Nullable private String viewerUid;
    private final List<DatabaseReference> references = new ArrayList<>();
    private final List<ChildEventListener> listeners = new ArrayList<>();
    private int generation;

    private FamilyAutomationLiveMonitor() {
    }

    public static void start(@NonNull Context context) {
        INSTANCE.startInternal(context.getApplicationContext());
    }

    private synchronized void startInternal(@NonNull Context context) {
        appContext = context;
        if (authListener != null) {
            return;
        }
        createChannel(context);
        authListener = auth -> attachForUser(auth.getCurrentUser());
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
    }

    private synchronized void attachForUser(@Nullable FirebaseUser user) {
        detachListeners();
        viewerUid = null;
        if (user == null || !user.isEmailVerified() || appContext == null) {
            return;
        }
        viewerUid = user.getUid();
        int requestGeneration = ++generation;
        FamilyAutomationRepository repository =
                new FamilyAutomationRepository();
        repository.loadOverview(new FamilyAutomationRepository.OverviewCallback() {
            @Override
            public void onLoaded(
                    @NonNull FamilyAutomationRepository.Session session,
                    @NonNull List<FamilyAutomationRepository.Member> allMembers,
                    @NonNull List<FamilyAutomationRepository.Member> visibleMembers,
                    @NonNull List<FamilyAutomationRepository.Member> manageableMembers,
                    @NonNull List<FamilyAutomationRule> rules,
                    @NonNull List<FamilyAutomationEvent> events
            ) {
                if (requestGeneration != generation) {
                    return;
                }
                for (FamilyAutomationRepository.Member member
                        : visibleMembers) {
                    attachBranch(
                            session.familyId,
                            member.uid,
                            requestGeneration
                    );
                }
            }

            @Override
            public void onError(@NonNull String reason) {
                // Reconnects after the next auth/process start.
            }
        });
    }

    private synchronized void attachBranch(
            @NonNull String familyId,
            @NonNull String targetUid,
            int requestGeneration
    ) {
        DatabaseReference reference = FirebaseDatabase.getInstance()
                .getReference()
                .child("familyAutomationEvents")
                .child(familyId)
                .child(targetUid);
        ChildEventListener listener = new ChildEventListener() {
            @Override
            public void onChildAdded(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                if (requestGeneration == generation) {
                    handleEvent(snapshot);
                }
            }

            @Override
            public void onChildChanged(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                if (requestGeneration == generation) {
                    handleEvent(snapshot);
                }
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                // History deletion does not require notification.
            }

            @Override
            public void onChildMoved(
                    @NonNull DataSnapshot snapshot,
                    @Nullable String previousChildName
            ) {
                // Ordering changes do not require notification.
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Existing screens remain available without notifications.
            }
        };
        references.add(reference);
        listeners.add(listener);
        reference.limitToLast(30).addChildEventListener(listener);
    }

    private void handleEvent(@NonNull DataSnapshot snapshot) {
        Context context = appContext;
        String activeViewer = viewerUid;
        if (context == null || activeViewer == null) {
            return;
        }
        FamilyAutomationEvent event = snapshot.getValue(
                FamilyAutomationEvent.class
        );
        if (event == null) {
            return;
        }
        if ((event.eventId == null || event.eventId.trim().isEmpty())
                && snapshot.getKey() != null) {
            event.eventId = snapshot.getKey();
        }
        if (event.eventId == null || event.eventId.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.occurredAt <= 0L
                || now - event.occurredAt > MAX_LIVE_EVENT_AGE_MS
                || (!activeViewer.equals(event.targetUid)
                && !event.notifyTrustedViewers)) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        String key = "event_" + activeViewer + "_" + event.eventId;
        if (preferences.getBoolean(key, false)) {
            return;
        }
        preferences.edit().putBoolean(key, true).apply();
        showNotification(context, event);
    }

    private void showNotification(
            @NonNull Context context,
            @NonNull FamilyAutomationEvent event
    ) {
        NotificationManager manager = context.getSystemService(
                NotificationManager.class
        );
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(context, FamilyAutomationActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = ("automation:" + event.eventId).hashCode();
        PendingIntent open = PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
        String title = notificationTitle(context, event);
        String detail = event.detail == null || event.detail.trim().isEmpty()
                ? context.getString(R.string.family_automation_event_fallback)
                : event.detail.trim();
        int priority = FamilyAutomationEvent.SEVERITY_WARNING.equals(
                event.severity
        ) ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT;
        try {
            manager.notify(
                    id,
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_family_automation)
                            .setContentTitle(title)
                            .setContentText(detail)
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(detail))
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .setPriority(priority)
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                            .setCategory(
                                    FamilyAutomationEvent.SEVERITY_WARNING.equals(
                                            event.severity
                                    )
                                            ? NotificationCompat.CATEGORY_ALARM
                                            : NotificationCompat.CATEGORY_STATUS
                            )
                            .build()
            );
        } catch (SecurityException ignored) {
            // In-app event history remains available.
        }
    }

    @NonNull
    private String notificationTitle(
            @NonNull Context context,
            @NonNull FamilyAutomationEvent event
    ) {
        String name = event.targetName == null
                || event.targetName.trim().isEmpty()
                ? context.getString(R.string.family_automation_member_fallback)
                : event.targetName.trim();
        switch (event.type) {
            case FamilyAutomationEvent.EVENT_LATE:
            case FamilyAutomationEvent.EVENT_MISSED:
                return context.getString(
                        R.string.family_automation_notification_attention,
                        name
                );
            case FamilyAutomationEvent.EVENT_TRIP_STARTED:
            case FamilyAutomationEvent.EVENT_TRIP_ENDED:
                return context.getString(
                        R.string.family_automation_notification_trip,
                        name
                );
            case FamilyAutomationEvent.EVENT_SHARING_STARTED:
            case FamilyAutomationEvent.EVENT_SHARING_STOPPED:
            case FamilyAutomationEvent.EVENT_LOW_BATTERY_PAUSED:
                return context.getString(
                        R.string.family_automation_notification_sharing,
                        name
                );
            default:
                return context.getString(
                        R.string.family_automation_notification_routine,
                        name
                );
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
                context.getString(R.string.family_automation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(context.getString(
                R.string.family_automation_channel_description
        ));
        manager.createNotificationChannel(channel);
    }

    private synchronized void detachListeners() {
        generation++;
        int count = Math.min(references.size(), listeners.size());
        for (int index = 0; index < count; index++) {
            references.get(index).removeEventListener(listeners.get(index));
        }
        references.clear();
        listeners.clear();
    }
}
