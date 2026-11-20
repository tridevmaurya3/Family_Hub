package com.tridev.familyhub.feature.chat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView;

import java.util.List;
import java.util.Locale;

final class ChatDictation implements RecognitionListener {
    private final FamilyChatFragment fragment;
    private final ActivityResultLauncher<String> permission;
    private SpeechRecognizer recognizer;
    private EditText field;
    private TaskVoiceWaveView wave;
    private boolean awaitingPermission;

    ChatDictation(FamilyChatFragment fragment) {
        this.fragment = fragment;
        permission = fragment.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!awaitingPermission) return;
            awaitingPermission = false;
            if (granted) start();
            else notice("Allow microphone access to dictate a message");
        });
    }

    void bind(EditText field, TaskVoiceWaveView wave) {
        stop();
        this.field = field;
        this.wave = wave;
    }

    void toggle() {
        if (recognizer != null) {
            recognizer.stopListening();
            return;
        }
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = true;
            permission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        start();
    }

    private void start() {
        if (field == null || !field.isAttachedToWindow()) return;
        if (!SpeechRecognizer.isRecognitionAvailable(fragment.requireContext())) {
            notice("Speech recognition is unavailable on this phone");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(fragment.requireContext());
            recognizer.setRecognitionListener(this);
            wave.setVisibility(View.VISIBLE);
            field.setAlpha(0f);
            wave.startListening();
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            recognizer.startListening(request);
        } catch (RuntimeException unavailable) {
            stop();
            notice("Unable to start dictation");
        }
    }

    void stop() {
        awaitingPermission = false;
        SpeechRecognizer active = recognizer;
        recognizer = null;
        if (active != null) active.destroy();
        if (wave != null) {
            wave.stopListening();
            wave.setVisibility(View.GONE);
        }
        if (field != null) field.setAlpha(1f);
    }

    @Override public void onResults(Bundle results) {
        if (recognizer == null) return;
        List<String> phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (field != null && phrases != null && !phrases.isEmpty()) {
            int start = Math.max(0, field.getSelectionStart());
            int end = Math.max(start, field.getSelectionEnd());
            field.getText().replace(start, end, phrases.get(0));
        }
        stop();
    }
    @Override public void onError(int error) {
        if (recognizer == null) return;
        stop();
        notice("No dictation captured. Try again.");
    }
    @Override public void onRmsChanged(float level) { if (recognizer != null) wave.setLevel(level); }
    @Override public void onEndOfSpeech() { if (recognizer != null) wave.showProcessing(); }
    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onPartialResults(Bundle partial) {}
    @Override public void onEvent(int event, Bundle extras) {}

    private void notice(String message) {
        if (fragment.isAdded()) Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
