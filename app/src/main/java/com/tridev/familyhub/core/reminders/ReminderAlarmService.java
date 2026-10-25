package com.tridev.familyhub.core.reminders;

import com.tridev.familyhub.core.security.AccountDataScope;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Reminder;

/** Bounded local ringing session; task scheduling and cloud data remain in their existing paths. */
public final class ReminderAlarmService extends Service {
    public static int notificationId(long id) { return 500_000_000 + (int)(id % 100_000_000); }
    public static final String CLOSED = "com.tridev.familyhub.REMINDER_ALARM_CLOSED";
    private static final String CHANNEL = "family_reminder_alarm_screen_v1";
    private static final String STOP = "stop";
    private static volatile long ringingId;
    private com.google.firebase.auth.FirebaseAuth.AuthStateListener accountListener;
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean keepControlsAfterStop;
    private int currentStartId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        keepControlsAfterStop = true;
        stopSelf();
    };

    static Intent alarmIntent(Context context, Reminder task, int stage, long occurrence) {
        return AccountDataScope.stamp(context,new Intent(context, ReminderAlarmService.class))
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, task.id).putExtra("title", task.title)
                .putExtra("notes", taskDetails(task))
                .putExtra("time", occurrence).putExtra("stage", stage)
                .putExtra("important", ReminderPreferences.important(context, task))
                .putExtra("due", task.reminderAt);
    }
    private static String taskDetails(Reminder task) { return task.note; }
    public static boolean fullScreenChannelReady(Context context) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }
    public static String alarmChannel() { return CHANNEL; }
    static boolean canRing(Context context) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Reminder alarms", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Ringing Reminder alarms with full-screen controls");
        channel.setSound(null, null);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 300, 150, 300});
        manager.createNotificationChannel(channel);
    }
    public static boolean fullScreenAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return Build.VERSION.SDK_INT < 34 || (manager != null && manager.canUseFullScreenIntent());
    }
    private static Intent screenIntent(Context context, Intent alarm) {
        return new Intent(context, ReminderAlarmActivity.class).putExtras(alarm)
                .setData(Uri.parse("familyhub://reminder-alarm/" + alarm.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }
    static PendingIntent screenPendingIntent(Context context, Intent alarm) {
        Bundle launchOptions = null;
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions options = ActivityOptions.makeBasic();
            // Only the explicit immutable alarm screen receives background launch privileges.
            options.setPendingIntentCreatorBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                    ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            launchOptions = options.toBundle();
        }
        return PendingIntent.getActivity(context,
                ReminderAlarmService.notificationId(alarm.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0)),
                screenIntent(context, alarm), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                launchOptions);
    }
    static PendingIntent snoozePendingIntent(Context context, Intent alarm) {
        Intent chooser = screenIntent(context, alarm).setClass(context, ReminderSnoozeActivity.class)
                .putExtra("snooze_only", true)
                .setData(Uri.parse("familyhub://reminder-snooze/" + alarm.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0)));
        return PendingIntent.getActivity(context,
                ReminderAlarmService.notificationId(alarm.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0)) ^ 0x116611,
                chooser, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static boolean isLockedOrScreenOff(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return (power != null && !power.isInteractive())
                || (keyguard != null && keyguard.isKeyguardLocked());
    }
    public static void stopAlarm(Context context, long id) {
        if (ringingId != id) return;
        try { context.startService(new Intent(context, ReminderAlarmService.class).setAction(STOP)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id)); } catch (RuntimeException ignored) { }
    }
    static PendingIntent action(Context context, long id, long due, String action, int minutes) {
        Intent intent = AccountDataScope.stamp(context, new Intent(context, ReminderReceiver.class)).setAction(action)
                .setData(Uri.parse("familyhub://alarm-action/" + id + "/" + action + "/" + minutes))
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id).putExtra("due", due).putExtra(ReminderReceiver.EXTRA_SNOOZE_MINUTES, minutes);
        return PendingIntent.getBroadcast(context, 0, AccountDataScope.isolate(context,intent), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        long id = intent.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0);
        if (STOP.equals(intent.getAction())) { if (id == ringingId) stopSelf(); return START_NOT_STICKY; }
        if (!AccountDataScope.canDeliver(this,intent.getStringExtra(AccountDataScope.EXTRA_UID))) { stopSelf(); return START_NOT_STICKY; }
        if (accountListener == null) {
            String account = AccountDataScope.uid();
            accountListener = auth -> { if (!account.equals(AccountDataScope.uid())) stopSelf(); };
            com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(accountListener);
        }
        if (id <= 0 || !ReminderReceiver.canNotify(this, intent.getBooleanExtra("important", false))) {
            stopSelf(); return START_NOT_STICKY;
        }
        currentStartId = startId;
        // Receiver already chose ringing while locked/off; do not re-sample after wake-up.
        boolean ring = intent.getBooleanExtra("ring", true);
        keepControlsAfterStop = false;
        releaseSound();
        // A prior occurrence may still own this foreground notification.
        if (ringingId != 0) stopForeground(STOP_FOREGROUND_REMOVE);
        if (ringingId != 0 && ringingId != id) closed(ringingId);
        ringingId = id;
        ensureChannel(this);
        PendingIntent open = screenPendingIntent(this, intent);
        PendingIntent choose = snoozePendingIntent(this, intent);
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(intent.getLongExtra("time", System.currentTimeMillis())));
        String detail = getString(intent.getIntExtra("stage", 1) == 3 ? R.string.task_alarm_snoozed : R.string.task_alarm_due)
                + "\n" + when;
        String notes = intent.getStringExtra("notes");
        if (notes != null && !notes.isEmpty()) detail += "\n" + notes;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(intent.getStringExtra("title"))
                .setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail)).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, getString(R.string.task_alarm_snooze_choose), choose)
                .addAction(0, getString(R.string.task_alarm_dismiss), action(this, id, intent.getLongExtra("due", -1), ReminderReceiver.ACTION_DISMISS, 0))
                .addAction(0, getString(R.string.family_tasks_notification_complete), action(this, id, intent.getLongExtra("due", -1), ReminderReceiver.ACTION_COMPLETE, 0));
        // Android chooses heads-up while in use and full-screen while locked/off/AOD.
        // Sampling screen state here races with the system waking for the notification.
        if (fullScreenAllowed(this) && fullScreenChannelReady(this)) builder.setFullScreenIntent(open, true);
        NotificationManager manager = getSystemService(NotificationManager.class);
        // Advance reminders and timed-out alarms reuse this id. Publish a fresh entry;
        // updating the existing notification can skip SystemUI's full-screen launch.
        if (manager != null) manager.cancel(ReminderAlarmService.notificationId(id));
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ReminderAlarmService.notificationId(id), builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ReminderAlarmService.notificationId(id), builder.build());
        } catch (RuntimeException denied) {
            // A failure inside startForeground happens after the receiver has returned.
            // Keep an actionable notification/full-screen intent instead of losing the alarm.
            boolean available = ReminderReceiver.canNotify(this, intent.getBooleanExtra("important", false));
            String fallbackChannel = ReminderReceiver.channelId(intent.getBooleanExtra("important", false));
            NotificationChannel fallback = manager == null ? null : manager.getNotificationChannel(fallbackChannel);
            if (fallback == null || fallback.getImportance() < NotificationManager.IMPORTANCE_HIGH)
                builder.setFullScreenIntent(null, false);
            builder.setChannelId(fallbackChannel)
                    .setDefaults(NotificationCompat.DEFAULT_ALL).setOngoing(false).setAutoCancel(false);
            try {
                if (!available) { stopSelf(); return START_NOT_STICKY; }
                androidx.core.app.NotificationManagerCompat.from(this)
                        .notify(ReminderAlarmService.notificationId(id), builder.build());
                keepControlsAfterStop = true;
                ReminderReceiver.confirmDelivery(this, intent);
                ReminderScheduler.rescheduleAll(this, () -> { });
            }
            catch (SecurityException ignored) { }
            stopSelf(); return START_NOT_STICKY;
        }
        validateRingingTask(intent, startId);
        ReminderReceiver.confirmDelivery(this, intent);
        ReminderScheduler.rescheduleAll(this, () -> { });
        if (ring) startSound();
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 120000);
        return START_NOT_STICKY;
    }
    private void validateRingingTask(Intent alarm, int startId) {
        String uid = alarm.getStringExtra(AccountDataScope.EXTRA_UID);
        com.tridev.familyhub.data.local.FamilyHubDatabase database =
                com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(this);
        new Thread(() -> {
            Reminder task = database.reminderDao().getById(alarm.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0));
            handler.post(() -> {
                if (currentStartId != startId || ringingId == 0) return;
                if (!AccountDataScope.canDeliver(this, uid) || task == null || !task.isEnabled
                        || "COMPLETED".equalsIgnoreCase(task.collaborationStatus)
                        || task.reminderAt != alarm.getLongExtra("due", -1)) {
                    keepControlsAfterStop = false;
                    stopSelf();
                }
            });
        }, "ReminderAlarmCurrentState").start();
    }
    private void startSound() {
        try {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyHub:ReminderAlarm");
            wakeLock.acquire(125000);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            audio = getSystemService(AudioManager.class);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> { }).build();
            audio.requestAudioFocus(focus);
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer(); player.setAudioAttributes(attributes); player.setDataSource(this, sound);
            player.setLooping(true);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener((mp, what, extra) -> true); player.prepareAsync();
        } catch (Exception ignored) { /* Vibration and the alarm controls still work without a ringtone. */ }
        try {
            vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 600}, 0));
        } catch (RuntimeException ignored) { }
    }
    private void releaseSound() {
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } player = null; }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); wakeLock = null;
    }
    private void closed(long id) { sendBroadcast(new Intent(CLOSED).setPackage(getPackageName()).putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id)); }
    @Override public void onDestroy() {
        if (accountListener != null) com.google.firebase.auth.FirebaseAuth.getInstance().removeAuthStateListener(accountListener);
        handler.removeCallbacks(timeout); releaseSound();
        long old = ringingId; ringingId = 0;
        if (old != 0 && !keepControlsAfterStop) closed(old);
        // Leave the reminder available after the two-minute ringing limit.
        stopForeground(keepControlsAfterStop ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
