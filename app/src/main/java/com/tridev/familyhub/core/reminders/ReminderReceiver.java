package com.tridev.familyhub.core.reminders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.feature.main.MainActivity;

/** Displays the reminder notification and continues daily schedules. */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_DISMISS="com.tridev.familyhub.action.DISMISS_REMINDER_ALARM";
    public static final String ACTION_COMPLETE="com.tridev.familyhub.action.COMPLETE_REMINDER_ALARM";
    private static final java.util.concurrent.ExecutorService ACTIONS=java.util.concurrent.Executors.newSingleThreadExecutor();
    public static final String ACTION_FIRE = "com.tridev.familyhub.action.FIRE_REMINDER";
    public static final String ACTION_SNOOZE = "com.tridev.familyhub.action.SNOOZE_REMINDER";
    public static final String EXTRA_REMINDER_ID = "reminder_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_NOTE = "note";
    public static final String EXTRA_REPEAT_TYPE = "repeat_type";
    public static final String EXTRA_SNOOZE_MINUTES = "snooze_minutes";
    public static final String EXTRA_SNOOZE_DELIVERY = "snooze_delivery";
    public static final String EXTRA_PRIORITY = "priority";
    public static final String EXTRA_PRE_ALERT = "pre_alert";
    private static final String CHANNEL_ID = "family_hub_reminders";
    private static final String URGENT_CHANNEL_ID = "family_hub_urgent_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_SNOOZE.equals(intent.getAction()) || ACTION_COMPLETE.equals(intent.getAction()) || ACTION_DISMISS.equals(intent.getAction())) {
            long id=intent.getLongExtra(EXTRA_REMINDER_ID,0);if(id<=0)return;
            ReminderAlarmService.stopAlarm(context,id);
            NotificationManagerCompat.from(context).cancel(ReminderAlarmService.notificationId(id));
            NotificationManagerCompat.from(context).cancel((int)(id&0x7fffffff));
            if(ACTION_DISMISS.equals(intent.getAction()))return;
            PendingResult result=goAsync();Context app=context.getApplicationContext();
            ACTIONS.execute(()->{try{
                Reminder r=com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(app).reminderDao().getById(id);
                if(r==null || "COMPLETED".equalsIgnoreCase(r.collaborationStatus)) {result.finish();return;}
                if(ACTION_COMPLETE.equals(intent.getAction())){
                    r.collaborationStatus="COMPLETED";r.isEnabled=false;ReminderScheduler.cancel(app,id);
                    new com.tridev.familyhub.data.repository.ReminderRepository(app).save(r,saved->result.finish());
                }else{ReminderScheduler.scheduleSnooze(app,id,r.title,r.note,r.repeatType,intent.getIntExtra(EXTRA_SNOOZE_MINUTES,10));result.finish();}
            }catch(RuntimeException error){result.finish();}});
            return;
        }
        if (!ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        PendingResult result=goAsync();Context app=context.getApplicationContext();
        ACTIONS.execute(()->{try{
            Reminder current=com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(app).reminderDao().getById(intent.getLongExtra(EXTRA_REMINDER_ID,0));
            if(!ReminderAlarmPolicy.canDeliver(current,intent.getBooleanExtra(EXTRA_SNOOZE_DELIVERY,false))){result.finish();return;}
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{deliver(app,intent);}finally{result.finish();}});
        }catch(RuntimeException error){result.finish();}});
    }
    private void deliver(Context context,Intent intent){
        long reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String note = intent.getStringExtra(EXTRA_NOTE);
        String repeatType = intent.getStringExtra(EXTRA_REPEAT_TYPE);
        String priority = intent.getStringExtra(EXTRA_PRIORITY);
        boolean preAlert = intent.getBooleanExtra(EXTRA_PRE_ALERT, false);

        boolean started=false;
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT<33){
            try {if(ReminderAlarmService.canRing(context)){ContextCompat.startForegroundService(context,ReminderAlarmService.alarmIntent(context,intent));started=true;}}catch(RuntimeException denied){ }
        }
        if(!started)showNotification(context, reminderId, title, note, repeatType, priority, preAlert);
        if (preAlert) return;
        if (intent.getBooleanExtra(EXTRA_SNOOZE_DELIVERY, false)) return;
        if (!Reminder.REPEAT_ONCE.equals(repeatType)) {
            ReminderScheduler.rescheduleRepeatingIfEnabled(context, reminderId, ()->{});
        } else {
            ReminderScheduler.disableOneTimeIfPresent(context, reminderId, ()->{});
        }
    }

    private void showNotification(Context context, long reminderId, String title, String note,
                                  String repeatType, String priority, boolean preAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        boolean urgent = "URGENT".equalsIgnoreCase(priority);
        createNotificationChannels(context);
        Intent openAppIntent = new Intent(context, ReminderAlarmActivity.class)
                .putExtra(EXTRA_REMINDER_ID,reminderId).putExtra("title",title).putExtra("notes",note).putExtra("time",System.currentTimeMillis()).putExtra("stage",preAlert?0:1)
                .setData(android.net.Uri.parse("familyhub://reminder-notification/"+reminderId))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(context,(int)(reminderId&0x7fffffff),openAppIntent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent chooser=new Intent(openAppIntent).setClass(context,ReminderSnoozeActivity.class).putExtra("snooze_only",true).setData(android.net.Uri.parse("familyhub://reminder-notification-snooze/"+reminderId));
        PendingIntent choose=PendingIntent.getActivity(context,(int)(reminderId&0x7fffffff),chooser,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                urgent ? URGENT_CHANNEL_ID : CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle((preAlert ? "Upcoming • " : "") + (title == null || title.trim().isEmpty()
                        ? context.getString(R.string.reminder_notification_fallback_title)
                        : title))
                .setContentText(note == null || note.trim().isEmpty()
                        ? context.getString(R.string.reminder_notification_fallback_note)
                        : note)
                .setPriority(urgent ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOnlyAlertOnce(preAlert)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .addAction(0,"Snooze",choose)
                .addAction(0,"Stop alarm",ReminderAlarmService.action(context,reminderId,ACTION_DISMISS,0))
                .addAction(0,"Complete",ReminderAlarmService.action(context,reminderId,ACTION_COMPLETE,0));
        NotificationManagerCompat.from(context).notify((int) (reminderId & 0x7fffffff), builder.build());
    }

    private PendingIntent snoozeIntent(Context context, long reminderId, String title,
                                       String note, String repeatType, int minutes) {
        Intent snooze = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_REPEAT_TYPE, repeatType)
                .putExtra(EXTRA_SNOOZE_MINUTES, minutes);
        return PendingIntent.getBroadcast(context,
                (((int) (reminderId & 0x7fffffff)) ^ minutes), snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.reminder_notification_channel_description));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            NotificationChannel urgent = new NotificationChannel(
                    URGENT_CHANNEL_ID, "Urgent family reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            urgent.setDescription("High-priority reminders that need immediate attention");
            urgent.enableVibration(true);
            manager.createNotificationChannel(urgent);
        }
    }
}
