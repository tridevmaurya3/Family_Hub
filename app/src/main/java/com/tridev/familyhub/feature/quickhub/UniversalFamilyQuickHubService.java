package com.tridev.familyhub.feature.quickhub;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.grocery.overlay.GroceryOverlayService;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.tasks.overlay.FamilyTaskOverlayService;

/** Launcher only; Grocery and To-Do behavior remains in their existing services. */
public final class UniversalFamilyQuickHubService extends Service {
    public static final String ACTION_SHOW = "com.tridev.familyhub.action.SHOW_QUICK_HUB";
    public static final String ACTION_HIDE = "com.tridev.familyhub.action.HIDE_QUICK_HUB";
    public static final String ACTION_STOP = "com.tridev.familyhub.action.STOP_QUICK_HUB";
    public static final String PREFS = "family_quick_hub";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_REQUESTED = "permission_requested";
    private static final String CHANNEL = "family_quick_hub";
    private static final int NOTIFICATION_ID = 4218;
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private View icon;
    private PopupWindow selector;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_family_task).setContentTitle("Family Quick Hub")
                .setContentText("Grocery and To-Do quick access").setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE))
                .build());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply();
        manager = getSystemService(WindowManager.class);
        if (Settings.canDrawOverlays(this)) showIcon();
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (icon == null && Settings.canDrawOverlays(this)) showIcon();
        if (icon != null) icon.setVisibility(ACTION_HIDE.equals(action) ? View.GONE : View.VISIBLE);
        return START_STICKY;
    }

    private void showIcon() {
        if (icon != null || manager == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        TextView hub = new TextView(this);
        hub.setText("+"); hub.setTextSize(28); hub.setGravity(Gravity.CENTER);
        hub.setTextColor(Color.rgb(15, 104, 80)); hub.setContentDescription("Family Quick Hub");
        hub.setBackground(round(Color.argb(245,231,246,240), Color.rgb(15,122,90), 24));
        hub.setElevation(dp(10));
        hub.setAlpha(prefs.getFloat("button_alpha", .92f));
        params = overlayParams(dp(48), dp(48));
        params.gravity = Gravity.TOP | Gravity.START;
        int defaultY = Math.round(getResources().getDisplayMetrics().heightPixels * .62f);
        params.x = prefs.getInt("x", getResources().getDisplayMetrics().widthPixels - dp(58));
        params.y = prefs.getInt("y", defaultY);
        icon = hub;
        hub.setOnTouchListener(new View.OnTouchListener() {
            int x,y; float downX,downY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if(e.getAction()==MotionEvent.ACTION_DOWN){x=params.x;y=params.y;downX=e.getRawX();downY=e.getRawY();moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){int dx=Math.round(e.getRawX()-downX),dy=Math.round(e.getRawY()-downY);moved|=Math.abs(dx)>dp(4)||Math.abs(dy)>dp(4);params.x=clamp(x+dx,0,getResources().getDisplayMetrics().widthPixels-dp(48));params.y=clamp(y+dy,0,getResources().getDisplayMetrics().heightPixels-dp(48));manager.updateViewLayout(icon,params);return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){prefs.edit().putInt("x",params.x).putInt("y",params.y).apply();if(!moved)showSelector(hub);return true;} return false;
            }
        });
        manager.addView(icon, params);
    }

    private void showSelector(View anchor) {
        if(selector!=null&&selector.isShowing()){selector.dismiss();return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(6),dp(6),dp(6),dp(6));box.setBackground(round(Color.WHITE,Color.rgb(184,207,199),16));
        Button grocery=choice("🛒  Grocery"), tasks=choice("✓  To-Do"), opacity=choice("◐  Button transparency"); box.addView(grocery,new LinearLayout.LayoutParams(dp(180),dp(42)));box.addView(tasks,new LinearLayout.LayoutParams(dp(180),dp(42)));box.addView(opacity,new LinearLayout.LayoutParams(dp(180),dp(42)));
        selector=new PopupWindow(box,dp(192),dp(138),true);selector.setOutsideTouchable(true);selector.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));selector.setElevation(dp(12));
        grocery.setOnClickListener(v->{open(GroceryOverlayService.class,GroceryOverlayService.ACTION_OPEN_PANEL,FamilyTaskOverlayService.class);selector.dismiss();});
        tasks.setOnClickListener(v->{open(FamilyTaskOverlayService.class,FamilyTaskOverlayService.ACTION_OPEN_PANEL,GroceryOverlayService.class);selector.dismiss();});
        opacity.setOnClickListener(v->{selector.dismiss();showOpacity(anchor);});
        selector.showAsDropDown(anchor,-dp(150),dp(4));
    }

    private void showOpacity(View anchor){SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);SeekBar bar=new SeekBar(this);bar.setPadding(dp(12),0,dp(12),0);bar.setProgress(Math.round((p.getFloat("button_alpha",.92f)-.35f)/.65f*100));PopupWindow pop=new PopupWindow(bar,dp(210),dp(52),true);pop.setOutsideTouchable(true);pop.setBackgroundDrawable(round(Color.WHITE,Color.rgb(184,207,199),16));pop.setElevation(dp(12));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){float a=.35f+(value/100f)*.65f;if(icon!=null)icon.setAlpha(a);p.edit().putFloat("button_alpha",a).apply();}});pop.showAsDropDown(anchor,-dp(162),dp(4));}

    private Button choice(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setBackground(round(Color.argb(245,247,252,249),Color.argb(140,184,207,199),12));return b;}
    private void open(Class<?> selected,String action,Class<?> other){startService(new Intent(this,other).setAction(other==GroceryOverlayService.class?GroceryOverlayService.ACTION_STOP:FamilyTaskOverlayService.ACTION_STOP));ContextCompat.startForegroundService(this,new Intent(this,selected).setAction(action));}
    private WindowManager.LayoutParams overlayParams(int w,int h){return new WindowManager.LayoutParams(w,h,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);} private int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    @Override public void onDestroy(){if(selector!=null)selector.dismiss();if(icon!=null&&manager!=null)try{manager.removeView(icon);}catch(RuntimeException ignored){}getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(KEY_ENABLED,false).apply();super.onDestroy();}
    @Nullable @Override public IBinder onBind(Intent intent){return null;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=getSystemService(NotificationManager.class);if(n!=null)n.createNotificationChannel(new NotificationChannel(CHANNEL,"Family Quick Hub",NotificationManager.IMPORTANCE_LOW));}}
}
