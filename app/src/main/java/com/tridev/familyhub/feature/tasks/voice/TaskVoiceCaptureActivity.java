package com.tridev.familyhub.feature.tasks.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

/** Transparent foreground bridge for reliable speech capture launched from the To-Do overlay. */
public final class TaskVoiceCaptureActivity extends Activity {
    public static final String ACTION_EVENT =
            "com.tridev.familyhub.action.TASK_VOICE_CAPTURE_EVENT";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_RMS = "rms";
    public static final String STATE_LISTENING = "LISTENING";
    public static final String STATE_RMS = "RMS";
    public static final String STATE_PROCESSING = "PROCESSING";
    public static final String STATE_PARTIAL = "PARTIAL";
    public static final String STATE_RESULT = "RESULT";
    public static final String STATE_ERROR = "ERROR";
    private static final int REQUEST_AUDIO = 9341;

    @Nullable private SpeechRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finished;
    private long lastRmsBroadcast;
    private final Runnable timeout = this::finishWithError;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setDimAmount(0f);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        } else {
            begin();
        }
    }

    private void begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWithError();
            return;
        }
        sendState(STATE_LISTENING, null, 0f);
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    sendState(STATE_LISTENING, null, 0f);
                }
                @Override public void onBeginningOfSpeech() {
                    sendState(STATE_LISTENING, null, 0f);
                }
                @Override public void onRmsChanged(float rmsdB) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastRmsBroadcast >= 90L) {
                        lastRmsBroadcast = now;
                        sendState(STATE_RMS, null, rmsdB);
                    }
                }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    sendState(STATE_PROCESSING, null, 0f);
                }
                @Override public void onError(int error) {
                    finishWithError();
                }
                @Override public void onResults(Bundle results) {
                    String value = firstResult(results);
                    sendState(value.isEmpty() ? STATE_ERROR : STATE_RESULT, value, 0f);
                    finishCapture();
                }
                @Override public void onPartialResults(Bundle results) {
                    String value = firstResult(results);
                    if (!value.isEmpty()) sendState(STATE_PARTIAL, value, 0f);
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                            Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recognizer.startListening(request);
            handler.postDelayed(timeout, 15000L);
        } catch (RuntimeException error) {
            finishWithError();
        }
    }

    private String firstResult(@Nullable Bundle results) {
        if (results == null) return "";
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null) return "";
        return matches.get(0).trim();
    }

    private void sendState(String state, @Nullable String text, float rms) {
        Intent event = new Intent(ACTION_EVENT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_RMS, rms);
        if (text != null) event.putExtra(EXTRA_TEXT, text);
        sendBroadcast(event);
    }

    private void finishWithError() {
        if (!finished) sendState(STATE_ERROR, "", 0f);
        finishCapture();
    }

    private void finishCapture() {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(timeout);
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (RuntimeException ignored) { }
            recognizer = null;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            finishWithError();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(timeout);
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (RuntimeException ignored) { }
            recognizer = null;
        }
        super.onDestroy();
    }
}
