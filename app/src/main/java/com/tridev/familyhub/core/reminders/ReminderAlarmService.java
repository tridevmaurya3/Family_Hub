package com.tridev.familyhub.core.reminders;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.tridev.familyhub.R;

/** Bounded local ringing session; reminder scheduling and cloud data remain in their existing paths. */
public final class ReminderAlarmService extends Service {
    public static final String CLOSED = "com.tridev.familyhub.REMINDER_ALARM_CLOSED";
    private static final String CHANNEL = "family_reminder_alarm_screen_v1";
    private static final String STOP = "stop";
    private static volatile long ringingId;
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    static Intent alarmIntent(Context context, Intent delivery) {
        return new Intent(context, ReminderAlarmService.class).putExtra(ReminderReceiver.EXTRA_REMINDER_ID, delivery.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID,0))
                .putExtra("title",delivery.getStringExtra(ReminderReceiver.EXTRA_TITLE)).putExtra("notes",delivery.getStringExtra(ReminderReceiver.EXTRA_NOTE))
                .putExtra("time",System.currentTimeMillis()).putExtra("stage",delivery.getBooleanExtra(ReminderReceiver.EXTRA_PRE_ALERT,false)?0:delivery.getBooleanExtra(ReminderReceiver.EXTRA_SNOOZE_DELIVERY,false)?3:1);
    }
    public static int notificationId(long id){return 500_000_000+(int)(id%100_000_000);}
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
    public static void stopAlarm(Context context, long id) {
        if (ringingId != id) return;
        try { context.startService(new Intent(context, ReminderAlarmService.class).setAction(STOP)
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id)); } catch (RuntimeException ignored) { }
    }
    static PendingIntent action(Context context, long id, String action, int minutes) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(action)
                .setData(Uri.parse("familyhub://reminder-alarm-action/" + id + "/" + action + "/" + minutes))
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id).putExtra(ReminderReceiver.EXTRA_SNOOZE_MINUTES, minutes);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        long id = intent.getLongExtra(ReminderReceiver.EXTRA_REMINDER_ID, 0);
        if (STOP.equals(intent.getAction())) { if (id == ringingId) stopSelf(); return START_NOT_STICKY; }
        if (id <= 0) { stopSelf(); return START_NOT_STICKY; }
        releaseSound();
        if (ringingId != 0 && ringingId != id) closed(ringingId);
        ringingId = id;
        ensureChannel(this);
        Intent screen = new Intent(this, ReminderAlarmActivity.class).putExtras(intent)
                .setData(Uri.parse("familyhub://reminder-alarm/" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle launchOptions = null;
        if (Build.VERSION.SDK_INT >= 34) {
            // Only this explicit, immutable alarm intent may launch while the phone is locked.
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentCreatorBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                    ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            launchOptions = options.toBundle();
        }
        PendingIntent open = PendingIntent.getActivity(this, notificationId(id), screen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, launchOptions);
        Intent chooser = new Intent(screen).setClass(this, ReminderSnoozeActivity.class).putExtra("snooze_only", true)
                .setData(Uri.parse("familyhub://reminder-snooze/" + id));
        PendingIntent choose = PendingIntent.getActivity(this, notificationId(id) ^ 0x116611,
                chooser, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(intent.getLongExtra("time", System.currentTimeMillis())));
        String detail = (intent.getIntExtra("stage",1)==0?"Upcoming reminder":getString(intent.getIntExtra("stage", 1) == 3 ? R.string.task_alarm_snoozed : R.string.task_alarm_due))
                + "\n" + when;
        String notes = intent.getStringExtra("notes");
        if (notes != null && !notes.isEmpty()) detail += "\n" + notes;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(intent.getStringExtra("title"))
                .setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail)).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setContentIntent(open)
                .addAction(0, getString(R.string.task_alarm_snooze_choose), choose)
                .addAction(0, getString(R.string.task_alarm_dismiss), action(this, id, ReminderReceiver.ACTION_DISMISS, 0))
                .addAction(0, getString(R.string.family_tasks_notification_complete), action(this, id, ReminderReceiver.ACTION_COMPLETE, 0));
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        boolean lockedOrOff = (power != null && !power.isInteractive()) || (keyguard != null && keyguard.isKeyguardLocked());
        if (lockedOrOff && fullScreenAllowed(this) && fullScreenChannelReady(this)) builder.setFullScreenIntent(open, true);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId(id), builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(notificationId(id), builder.build());
        } catch (RuntimeException denied) { stopSelf(); return START_NOT_STICKY; }
        startSound(); handler.removeCallbacks(timeout); handler.postDelayed(timeout, 120000);
        return START_NOT_STICKY;
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
        handler.removeCallbacks(timeout); releaseSound();
        long old = ringingId; ringingId = 0; if (old != 0) closed(old);
        // Leave the reminder available after the two-minute ringing limit.
        stopForeground(STOP_FOREGROUND_DETACH); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
