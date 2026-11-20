package com.tridev.familyhub.feature.chat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.feature.main.MainActivity;

final class ChatNotifications {
    static final int ALL = 0, MENTIONS = 1, MUTED = 2;
    private static final String CHANNEL = "family_chat_messages";
    private static final String TAG = "family-chat-notification";
    private final Context context;
    private final FamilyChatStore store;

    ChatNotifications(Context context, FamilyChatStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    static int mode(Context context, String uid, FamilyChatStore.ThreadState thread) {
        if (uid == null) return MUTED;
        return context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE)
                .getInt(uid + "|" + thread.ref, MUTED);
    }

    static void setMode(Context context, String uid, FamilyChatStore.ThreadState thread, int mode) {
        if (uid == null) return;
        context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE)
                .edit().putInt(uid + "|" + thread.ref, mode).apply();
        if (mode == MUTED) NotificationManagerCompat.from(context).cancel(TAG, 1);
    }

    void onMessages(FamilyChatStore.ThreadState thread) {
        FamilyChatStore.Message incoming = null;
        String uid = store.accountId();
        int setting = mode(context, uid, thread);
        for (FamilyChatStore.Message message : thread.messages) {
            String key = message.snapshot.getKey();
            if (key == null || !thread.notificationSeen.add(key) || store.own(message)
                    || message.deleted() || !store.unread(message) || !thread.notificationsInitialized
                    || setting == MUTED) continue;
            if (setting == ALL || message.snapshot.child("mentions").child(uid).exists()) {
                incoming = message;
            } else {
                String reply = message.snapshot.child("replyTo").getValue(String.class);
                if (reply != null) store.loadMessage(thread, reply, original -> {
                    if (original != null && uid.equals(store.accountId()) && store.own(original)
                            && store.canUse(thread) && mode(context, uid, thread) == MENTIONS
                            && store.unread(message)) show(uid);
                });
            }
        }
        thread.notificationsInitialized = true;
        if (thread.notificationSeen.size() > 240) {
            thread.notificationSeen.clear();
            for (FamilyChatStore.Message message : thread.messages) thread.notificationSeen.add(message.snapshot.getKey());
        }
        if (incoming != null) show(uid);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void show(String uid) {
        if (uid == null || !uid.equals(store.accountId())) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) return;
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nativeManager = context.getSystemService(NotificationManager.class);
        if (nativeManager == null) return;
        nativeManager.createNotificationChannel(new NotificationChannel(CHANNEL, "Family Chat", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AccountDataScope.EXTRA_UID, uid)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, true);
        PendingIntent intent = PendingIntent.getActivity(context, 9032, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(TAG, 1, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_chat).setContentTitle("Family Chat")
                .setContentText("You have a new chat message")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(intent).setAutoCancel(true).build());
    }

    void clear() {
        NotificationManagerCompat.from(context).cancel(TAG, 1);
    }
}
