package com.tridev.familyhub.feature.notes;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.feature.notes.overlay.NotesOverlayService;
import com.tridev.familyhub.feature.notes.overlay.NotesNotificationPermissionActivity;
import com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView;
import java.util.ArrayList;
import java.util.Locale;

/** Notes overlay dictation: stays beside the field and never opens the Google speech UI. */
final class NotesInlineVoice {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NotesOverlayService service;
    private SpeechRecognizer recognizer;
    private PopupWindow popup;
    private TaskVoiceWaveView wave;
    private TextView status;
    private int session;
    private boolean microphone;
    private final Runnable timeout = () -> finish("Voice timed out — tap mic to retry");

    NotesInlineVoice(Context context) {
        this.context = context;
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof NotesOverlayService))
            current = ((ContextWrapper) current).getBaseContext();
        service = current instanceof NotesOverlayService ? (NotesOverlayService) current : null;
    }

    void bind(View root) {
        if (root instanceof TextInputLayout) {
            TextInputLayout field = (TextInputLayout) root;
            EditText input = field.getEditText();
            if (input != null && field.getEndIconMode() == TextInputLayout.END_ICON_CUSTOM)
                field.setEndIconOnClickListener(v -> start(field, input));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) bind(group.getChildAt(i));
        }
    }

    private void start(TextInputLayout field, EditText input) {
        if (recognizer != null) { stop(); return; }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            final int request = ++session;
            ResultReceiver receiver = new ResultReceiver(handler) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (request != session) return;
                    if (code == 1) handler.postDelayed(() -> {
                        if (request == session && field.isAttachedToWindow()) start(field, input);
                    }, 250);
                    else Toast.makeText(context, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show();
                }
            };
            context.startActivity(new Intent(context, NotesNotificationPermissionActivity.class)
                    .putExtra("notes_audio", true).putExtra("receiver", receiver).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Voice input unavailable on this device", Toast.LENGTH_SHORT).show(); return;
        }
        stop();
        final int capture = session;
        final String original = input.getText().toString();
        try {
            if (service != null) { service.beginMicrophone(); microphone = true; }
            LinearLayout strip = NotesPanels.content(context, "Listening…");
            status = (TextView) strip.getChildAt(0);
            status.setTextSize(12);
            wave = new TaskVoiceWaveView(context);
            strip.addView(wave, new LinearLayout.LayoutParams(-1, NotesPanels.dp(context, 26)));
            int width = Math.max(NotesPanels.dp(context, 120), field.getWidth());
            popup = new PopupWindow(strip, width, NotesPanels.dp(context, 72), false);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            popup.setWindowLayoutType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            popup.setTouchable(false); popup.setClippingEnabled(true);
            popup.showAsDropDown(field, 0, -field.getHeight() - NotesPanels.dp(context, 76));
            wave.startListening();
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return capture == session && recognizer != null; }
                private void apply(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && !matches.get(0).trim().isEmpty()) {
                        input.setText(original.isEmpty() ? matches.get(0) : original + " " + matches.get(0));
                        input.setSelection(input.length());
                    }
                }
                public void onReadyForSpeech(Bundle params) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { if (current()) wave.setLevel(rms); }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() { if (current()) { status.setText("Processing…"); wave.showProcessing(); } }
                public void onError(int error) { if (current()) finish("Voice input stopped — tap mic to retry"); }
                public void onResults(Bundle results) { if (current()) { apply(results); stop(); } }
                public void onPartialResults(Bundle results) { if (current()) apply(results); }
                public void onEvent(int type, Bundle params) { }
            });
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
            handler.postDelayed(timeout, 30000);
        } catch (RuntimeException unavailable) { finish("Voice input unavailable — tap mic to retry"); }
    }
    private void finish(String message) {
        stop(); Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
    void stop() {
        session++; handler.removeCallbacks(timeout);
        SpeechRecognizer old = recognizer; recognizer = null;
        if (old != null) { try { old.cancel(); old.destroy(); } catch (RuntimeException ignored) { } }
        if (wave != null) wave.stopListening(); wave = null;
        if (popup != null) popup.dismiss(); popup = null;
        if (microphone && service != null) { try { service.endMicrophone(); } catch (RuntimeException ignored) { } }
        microphone = false;
    }
}
