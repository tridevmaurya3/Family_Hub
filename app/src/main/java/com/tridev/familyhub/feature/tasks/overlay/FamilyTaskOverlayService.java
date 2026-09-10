package com.tridev.familyhub.feature.tasks.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** Independent, draggable Family To-Do overlay; it never reads Grocery or Finance data. */
public final class FamilyTaskOverlayService extends Service {
    public static final String ACTION_SHOW = "com.tridev.familyhub.action.SHOW_TASK_OVERLAY";
    public static final String ACTION_STOP = "com.tridev.familyhub.action.STOP_TASK_OVERLAY";
    public static final String PREFS = "family_task_overlay";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_REQUESTED = "permission_requested";
    private static final String CHANNEL = "family_task_overlay";
    private static final int NOTIFICATION_ID = 4217;

    private WindowManager windowManager;
    private WindowManager.LayoutParams stripParams;
    @Nullable private View stripView;
    @Nullable private View panelView;
    @Nullable private LinearLayout taskRows;
    @Nullable private TextView countText;
    @Nullable private TextView voiceStatus;
    @Nullable private android.speech.SpeechRecognizer speechRecognizer;
    private FamilyTaskRepository repository;
    private boolean tomorrow;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply();
        repository = new FamilyTaskRepository(this);
        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull FamilyTask task) { refresh(); }
            @Override public void onRemoved(long localId) { refresh(); }
        });
        windowManager = getSystemService(WindowManager.class);
        if (Settings.canDrawOverlays(this)) showStrip();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (stripView == null && Settings.canDrawOverlays(this)) showStrip();
        if (stripView != null) stripView.setVisibility(View.VISIBLE);
        return START_STICKY;
    }

    private void showStrip() {
        TextView strip = text("✓", 25, true);
        strip.setTextColor(Color.WHITE);
        strip.setGravity(Gravity.CENTER);
        strip.setContentDescription(getString(R.string.family_tasks_title));
        strip.setBackground(round(Color.rgb(15, 122, 90), 22, Color.TRANSPARENT));
        stripParams = params(dp(52), dp(52));
        stripParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        stripParams.x = dp(12);
        windowManager.addView(strip, stripParams);
        stripView = strip;
        strip.setOnClickListener(v -> togglePanel());
        strip.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX=e.getRawX(); downY=e.getRawY(); startX=stripParams.x; startY=stripParams.y; moved=false; return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx=(int)(e.getRawX()-downX), dy=(int)(e.getRawY()-downY);
                    moved |= Math.abs(dx)>dp(4) || Math.abs(dy)>dp(4);
                    stripParams.x=startX-dx; stripParams.y=startY+dy;
                    windowManager.updateViewLayout(stripView, stripParams); return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) togglePanel(); return true; }
                return false;
            }
        });
    }

    private void togglePanel() { if (panelView == null) showPanel(); else closePanel(); }

    private void showPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackground(round(Color.rgb(250, 253, 251), 24, Color.rgb(15,122,90)));

        LinearLayout header = row();
        TextView title = text(getString(R.string.family_tasks_title), 20, true);
        title.setTextColor(Color.rgb(15, 91, 70));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button open = chip("Open");
        open.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        header.addView(open);
        Button close = chip("×"); close.setOnClickListener(v -> closePanel()); header.addView(close);
        root.addView(header);

        LinearLayout filters = row();
        Button todayButton = chip(getString(R.string.family_tasks_overlay_today));
        Button tomorrowButton = chip(getString(R.string.family_tasks_overlay_tomorrow));
        countText = text("", 12, true); countText.setGravity(Gravity.CENTER_VERTICAL);
        filters.addView(todayButton); filters.addView(tomorrowButton);
        filters.addView(countText, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(filters);

        LinearLayout quick = row();
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setTextSize(15); input.setHint(R.string.family_tasks_overlay_add_hint);
        input.setPadding(dp(14),0,dp(10),0); input.setBackground(round(Color.WHITE, 15, Color.rgb(173,205,195)));
        ImageButton voice = new ImageButton(this);
        voice.setImageResource(R.drawable.ic_mic);
        voice.setContentDescription(getString(R.string.family_tasks_voice_add));
        voice.setColorFilter(Color.rgb(15, 105, 80));
        voice.setPadding(dp(11), dp(11), dp(11), dp(11));
        voice.setBackground(round(Color.rgb(237,248,244),16,Color.rgb(138,194,176)));
        Button add = chip("+ Add");
        quick.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        voiceParams.setMarginStart(dp(5)); quick.addView(voice, voiceParams); quick.addView(add);
        root.addView(quick);
        voiceStatus = text("", 12, true);
        voiceStatus.setTextColor(Color.rgb(15, 105, 80));
        voiceStatus.setVisibility(View.GONE);
        root.addView(voiceStatus);

        ScrollView scroll = new ScrollView(this);
        taskRows = new LinearLayout(this); taskRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(taskRows); root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(310)));

        View.OnClickListener filterClick = v -> { tomorrow = v == tomorrowButton; refresh(); };
        todayButton.setOnClickListener(filterClick); tomorrowButton.setOnClickListener(filterClick);
        View.OnClickListener save = v -> {
            String value=input.getText().toString().trim(); if (value.isEmpty()) return;
            FamilyTask task=new FamilyTask(); task.title=value; task.dueAt=dueAt(tomorrow);
            repository.save(task, () -> { input.setText(""); refresh(); });
        };
        add.setOnClickListener(save);
        voice.setOnClickListener(v -> startVoiceCapture(input, voice));
        input.setOnEditorActionListener((v,a,e) -> { if(a==EditorInfo.IME_ACTION_DONE){save.onClick(v);return true;}return false; });

        WindowManager.LayoutParams panelParams = params(dp(360), WindowManager.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        windowManager.addView(root, panelParams); panelView=root; refresh();
    }

    private void refresh() {
        if (taskRows == null) return;
        repository.loadAll("", tasks -> {
            if (taskRows == null) return;
            taskRows.removeAllViews();
            long[] range=range(tomorrow); List<FamilyTask> visible=new ArrayList<>();
            for(FamilyTask task:tasks) if(FamilyTask.STATUS_PENDING.equals(task.status) && task.dueAt>=range[0] && task.dueAt<range[1]) visible.add(task);
            if(countText!=null) countText.setText(visible.size()+" pending");
            if(visible.isEmpty()) { TextView empty=text(getString(R.string.family_tasks_empty_title),15,true); empty.setGravity(Gravity.CENTER); empty.setPadding(0,dp(34),0,dp(34)); taskRows.addView(empty); return; }
            for(FamilyTask task:visible) addTaskRow(task);
        });
    }

    private void addTaskRow(FamilyTask task) {
        LinearLayout card=row(); card.setPadding(dp(8),dp(8),dp(8),dp(8)); card.setBackground(round(Color.rgb(231,246,240),16,Color.rgb(184,220,207)));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(5),0,dp(5));
        CheckBox check=new CheckBox(this); card.addView(check,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout copy=new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(task.title,16,true); title.setTextColor(Color.rgb(32,43,40)); copy.addView(title);
        TextView detail=text(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(task.dueAt))+(task.assignedMemberName.isEmpty()?" • Whole family":" • "+task.assignedMemberName),12,false);
        detail.setTextColor(Color.rgb(69,112,99)); copy.addView(detail); card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        check.setOnCheckedChangeListener((button,checked)->{if(checked)repository.setCompleted(task,true,()->{
            FamilyTaskScheduler.cancel(this, task.id);
            repository.loadAll("", all -> {
                for (FamilyTask pending : all) if (pending.reminderEnabled
                        && FamilyTask.STATUS_PENDING.equals(pending.status)) {
                    FamilyTaskScheduler.schedule(this, pending);
                }
                refresh();
            });
        });});
        taskRows.addView(card,cp);
    }

    private void startVoiceCapture(@NonNull EditText input, @NonNull ImageButton voice) {
        if (speechRecognizer != null) { stopVoiceCapture(); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showVoiceStatus(R.string.family_tasks_voice_permission, true); return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus(R.string.family_tasks_voice_unavailable, true); return;
        }
        voice.setColorFilter(Color.rgb(220, 45, 82));
        showVoiceStatus(R.string.family_tasks_voice_listening, false);
        try {
            speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { showVoiceStatus(R.string.family_tasks_voice_processing, false); }
            @Override public void onError(int error) {
                stopVoiceCapture(); voice.setColorFilter(Color.rgb(15,105,80));
                showVoiceStatus(R.string.family_tasks_voice_no_match, true);
            }
            @Override public void onResults(android.os.Bundle results) {
                boolean added = applyVoiceResult(results, input);
                stopVoiceCapture(); voice.setColorFilter(Color.rgb(15,105,80));
                showVoiceStatus(added ? R.string.family_tasks_voice_added
                        : R.string.family_tasks_voice_no_match, true);
            }
            @Override public void onPartialResults(android.os.Bundle results) { applyVoiceResult(results, input); }
            @Override public void onEvent(int eventType, android.os.Bundle params) { }
            });
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                            java.util.Locale.getDefault().toLanguageTag())
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechRecognizer.startListening(intent);
        } catch (RuntimeException error) {
            stopVoiceCapture();
            voice.setColorFilter(Color.rgb(15,105,80));
            showVoiceStatus(R.string.family_tasks_voice_unavailable, true);
        }
    }

    private boolean applyVoiceResult(@Nullable android.os.Bundle results,
                                     @NonNull EditText input) {
        if (results == null) return false;
        ArrayList<String> matches = results.getStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null
                || matches.get(0).trim().isEmpty()) return false;
        input.setText(matches.get(0).trim()); input.setSelection(input.length());
        return true;
    }

    private void showVoiceStatus(int message, boolean hide) {
        if (voiceStatus == null) return;
        voiceStatus.setText(message); voiceStatus.setVisibility(View.VISIBLE);
        if (hide) voiceStatus.postDelayed(() -> {
            if (voiceStatus != null) voiceStatus.setVisibility(View.GONE);
        }, 2600L);
    }

    private void stopVoiceCapture() {
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
    }

    private void closePanel() { stopVoiceCapture(); if(panelView!=null){windowManager.removeView(panelView);panelView=null;taskRows=null;countText=null;voiceStatus=null;} }
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private Button chip(String value){Button b=new Button(this);b.setText(value);b.setTextSize(12);b.setTextColor(Color.rgb(15,105,80));b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setBackground(round(Color.rgb(237,248,244),16,Color.rgb(138,194,176)));return b;}
    private TextView text(String value,float size,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private WindowManager.LayoutParams params(int w,int h){return new WindowManager.LayoutParams(w,h,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private long dueAt(boolean next){Calendar c=Calendar.getInstance();if(next)c.add(Calendar.DAY_OF_YEAR,1);c.set(Calendar.HOUR_OF_DAY,18);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long[] range(boolean next){Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(next)c.add(Calendar.DAY_OF_YEAR,1);long start=c.getTimeInMillis();c.add(Calendar.DAY_OF_YEAR,1);return new long[]{start,c.getTimeInMillis()};}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=getSystemService(NotificationManager.class);if(n!=null)n.createNotificationChannel(new NotificationChannel(CHANNEL,getString(R.string.family_tasks_overlay_channel),NotificationManager.IMPORTANCE_LOW));}}
    private Notification notification(){PendingIntent p=PendingIntent.getActivity(this,NOTIFICATION_ID,new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE,MainActivity.ROUTE_TASKS),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_family_task).setContentTitle(getString(R.string.family_tasks_title)).setContentText(getString(R.string.family_tasks_overlay_notification)).setOngoing(true).setContentIntent(p).build();}
    @Nullable @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){closePanel();if(stripView!=null){windowManager.removeView(stripView);stripView=null;}repository.stopRealtimeSync();getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(KEY_ENABLED,false).apply();super.onDestroy();}
}
