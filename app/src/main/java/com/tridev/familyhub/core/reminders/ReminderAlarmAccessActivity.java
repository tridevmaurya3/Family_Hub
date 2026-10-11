package com.tridev.familyhub.core.reminders;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;

/** User-controlled alarm access; never opens settings from an alarm delivery. */
public final class ReminderAlarmAccessActivity extends AppCompatActivity {
    private LinearLayout body;
    @Override protected void onCreate(Bundle state){super.onCreate(state);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(16*getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);setContentView(body);}
    @Override protected void onResume(){super.onResume();render();ReminderScheduler.rescheduleAll(this,()->{});}
    private void render(){body.removeAllViews();TextView title=new TextView(this);title.setText("Reminder alarm access");title.setTextSize(20);body.addView(title);
        TextView help=new TextView(this);help.setText("Allow notifications, exact alarms and full-screen alarms for lock-screen reminders. These permissions apply to all your reminders.");body.addView(help);
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)button("Allow notifications",()->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},301));
        if(Build.VERSION.SDK_INT>=34&&!ReminderAlarmService.fullScreenAllowed(this))button("Allow full-screen alarms",()->open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()))));
        AlarmManager manager=getSystemService(AlarmManager.class);if(Build.VERSION.SDK_INT>=31&&manager!=null&&!manager.canScheduleExactAlarms())button("Allow exact alarm times",()->open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName()))));
        button("Alarm notification settings",()->open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,ReminderAlarmService.alarmChannel())));
        button("Done",this::finish);
    }
    private void button(String label,Runnable action){MaterialButton b=new MaterialButton(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());body.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void open(Intent intent){if(intent.resolveActivity(getPackageManager())!=null)startActivity(intent);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);render();}
}
