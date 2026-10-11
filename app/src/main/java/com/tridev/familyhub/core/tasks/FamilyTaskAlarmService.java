package com.tridev.familyhub.core.tasks;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyTask;

/** Bounded local ringing session; task scheduling and cloud data remain in their existing paths. */
public final class FamilyTaskAlarmService extends Service {
    public static final String CLOSED = "com.tridev.familyhub.TASK_ALARM_CLOSED";
    private static final String CHANNEL = "family_task_alarm_screen";
    private static final String STOP = "stop";
    private static volatile long ringingId;
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    static Intent alarmIntent(Context context, FamilyTask task, int stage, long occurrence) {
        return new Intent(context, FamilyTaskAlarmService.class)
                .putExtra(FamilyTaskReceiver.EXTRA_ID, task.id).putExtra("title", task.title)
                .putExtra("notes", com.tridev.familyhub.feature.tasks.FamilyTaskSubtasks.decode(task.notes).notes)
                .putExtra("time", occurrence).putExtra("stage", stage);
    }
    static boolean canRing(Context context) {
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "To-Do alarms", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Ringing To-Do alarms with full-screen controls");
        channel.setSound(null, null); manager.createNotificationChannel(channel);
    }
    public static boolean fullScreenAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return Build.VERSION.SDK_INT < 34 || (manager != null && manager.canUseFullScreenIntent());
    }
    public static void stopAlarm(Context context, long id) {
        if (ringingId != id) return;
        try { context.startService(new Intent(context, FamilyTaskAlarmService.class).setAction(STOP)
                .putExtra(FamilyTaskReceiver.EXTRA_ID, id)); } catch (RuntimeException ignored) { }
    }
    static PendingIntent action(Context context, long id, String action, int minutes) {
        Intent intent = new Intent(context, FamilyTaskReceiver.class).setAction(action)
                .setData(Uri.parse("familyhub://alarm-action/" + id + "/" + action + "/" + minutes))
                .putExtra(FamilyTaskReceiver.EXTRA_ID, id).putExtra("minutes", minutes);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        long id = intent.getLongExtra(FamilyTaskReceiver.EXTRA_ID, 0);
        if (STOP.equals(intent.getAction())) { if (id == ringingId) stopSelf(); return START_NOT_STICKY; }
        if (id <= 0) { stopSelf(); return START_NOT_STICKY; }
        releaseSound();
        if (ringingId != 0 && ringingId != id) closed(ringingId);
        ringingId = id;
        ensureChannel(this);
        Intent screen = new Intent(this, FamilyTaskAlarmActivity.class).putExtras(intent)
                .setData(Uri.parse("familyhub://task-alarm/" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, FamilyTaskScheduler.notificationId(id), screen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_family_task).setContentTitle(intent.getStringExtra("title"))
                .setContentText(getString(intent.getIntExtra("stage", 1) == 3 ? R.string.task_alarm_snoozed : R.string.task_alarm_due)).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setContentIntent(open)
                .addAction(0, getString(R.string.task_alarm_snooze10), action(this, id, FamilyTaskReceiver.ACTION_SNOOZE, 10))
                .addAction(0, getString(R.string.task_alarm_dismiss), action(this, id, FamilyTaskReceiver.ACTION_DISMISS, 0));
        if (fullScreenAllowed(this)) builder.setFullScreenIntent(open, true);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(FamilyTaskScheduler.notificationId(id), builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(FamilyTaskScheduler.notificationId(id), builder.build());
        } catch (RuntimeException denied) { stopSelf(); return START_NOT_STICKY; }
        startSound(); handler.removeCallbacks(timeout); handler.postDelayed(timeout, 120000);
        return START_NOT_STICKY;
    }
    private void startSound() {
        try {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyHub:TaskAlarm");
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
    private void closed(long id) { sendBroadcast(new Intent(CLOSED).setPackage(getPackageName()).putExtra(FamilyTaskReceiver.EXTRA_ID, id)); }
    @Override public void onDestroy() {
        handler.removeCallbacks(timeout); releaseSound();
        long old = ringingId; ringingId = 0; if (old != 0) closed(old);
        // Leave the reminder available after the two-minute ringing limit.
        stopForeground(STOP_FOREGROUND_DETACH); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
