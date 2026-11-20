package com.tridev.familyhub.feature.chat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class ChatVoiceNotes {
    private static final int RATE = 16000;
    private static final int MAX_BYTES = RATE * 2 * 25;
    private final FamilyChatFragment fragment;
    private final FamilyChatStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final androidx.activity.result.ActivityResultLauncher<String> permission;
    private FamilyChatStore.ThreadState pendingThread;
    private Recording current;
    private AlertDialog dialog;
    private MediaPlayer player;
    private File playingFile;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor audioSource;
    private Runnable recognitionTimeout;

    ChatVoiceNotes(FamilyChatFragment fragment, FamilyChatStore store) {
        this.fragment = fragment;
        this.store = store;
        permission = fragment.registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                allowed -> {
                    FamilyChatStore.ThreadState thread = pendingThread;
                    pendingThread = null;
                    if (allowed && thread != null && ready(thread)) start(thread);
                    else if (!allowed) notice("Microphone permission is required to record a voice note");
                });
    }

    void record(FamilyChatStore.ThreadState thread) {
        if (!ready(thread)) return;
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) start(thread);
        else {
            pendingThread = thread;
            permission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void start(FamilyChatStore.ThreadState thread) {
        stop();
        Recording recording = new Recording();
        current = recording;
        AlertDialog recordingDialog = new ChatFormDialog(fragment.requireContext())
                .setTitle("Recording voice note").setMessage("Speak now. The limit is 25 seconds.")
                .setNegativeButton("Discard", (dialog, action) -> recording.discard = true)
                .setPositiveButton("Stop", null).create();
        dialog = recordingDialog;
        recordingDialog.setOnDismissListener(ignored -> recording.stopping = true);
        recordingDialog.show();
        recordingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            recording.stopping = true;
            recordingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        });
        io.execute(() -> {
            byte[] pcm = capture(recording);
            handler.post(() -> {
                if (current != recording) return;
                current = null;
                boolean show = !recording.discard && recordingDialog.isShowing() && ready(thread);
                recordingDialog.dismiss();
                if (!show) return;
                if (pcm.length == 0) notice("No audio recorded");
                else review(thread, pcm);
            });
        });
    }

    @android.annotation.SuppressLint("MissingPermission")
    private static byte[] capture(Recording recording) {
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return new byte[0];
        AudioRecord microphone = null;
        try {
            microphone = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 4096));
            microphone.startRecording();
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!recording.stopping && audio.size() < MAX_BYTES) {
                int read = microphone.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - audio.size()));
                if (read <= 0) break;
                audio.write(buffer, 0, read);
            }
            return audio.toByteArray();
        } catch (IllegalStateException | SecurityException | IllegalArgumentException failure) {
            return new byte[0];
        } finally {
            if (microphone != null) {
                try { microphone.stop(); } catch (IllegalStateException ignored) {}
                microphone.release();
            }
        }
    }

    private void review(FamilyChatStore.ThreadState thread, byte[] pcm) {
        Context context = fragment.requireContext();
        android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, padding);
        EditText transcript = new EditText(context);
        transcript.setHint("Optional transcript");
        transcript.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        content.addView(ChatFormDialog.outlined(transcript));
        com.google.android.material.button.MaterialButton play = new com.google.android.material.button.MaterialButton(context);
        play.setText("Preview recording");
        play.setOnClickListener(view -> play(wav(pcm)));
        content.addView(play);
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            com.google.android.material.button.MaterialButton transcribe = new com.google.android.material.button.MaterialButton(context);
            transcribe.setText("Transcribe in Hindi");
            transcribe.setOnClickListener(view -> transcribe(pcm, transcript));
            content.addView(transcribe);
        }
        dialog = new ChatFormDialog(context).setTitle("Voice note")
                .setView(content).setNegativeButton("Discard", null)
                .setPositiveButton("Send", (dismissed, action) -> {
                    String text = transcript.getText().toString().trim();
                    Map<String, Object> details = new HashMap<>();
                    details.put("kind", "voice");
                    details.put("mime", "audio/wav");
                    store.send(thread, text.isEmpty() ? "Voice note" : text, details,
                            Base64.encodeToString(wav(pcm), Base64.NO_WRAP), () -> notice("Unable to send voice note"));
                }).show();
        dialog.setOnDismissListener(ignored -> { stopPlayback(); endRecognition(); });
    }

    @androidx.annotation.RequiresApi(33)
    private void transcribe(byte[] pcm, EditText field) {
        endRecognition();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            audioSource = pipe[0];
            SpeechRecognizer session = SpeechRecognizer.createOnDeviceSpeechRecognizer(fragment.requireContext());
            recognizer = session;
            session.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    if (recognizer != session) return;
                    java.util.ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values != null && !values.isEmpty()) field.setText(values.get(0));
                    else notice("No Hindi transcript was returned");
                    endRecognition();
                }
                @Override public void onError(int code) {
                    if (recognizer != session) return;
                    notice("Hindi transcription is unavailable on this device");
                    endRecognition();
                }
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle results) {}
                @Override public void onEvent(int event, Bundle data) {}
            });
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            io.execute(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(pcm);
                } catch (IOException ignored) {}
            });
            session.startListening(request);
            recognitionTimeout = () -> {
                notice("Hindi transcription did not complete");
                endRecognition();
            };
            handler.postDelayed(recognitionTimeout, 30000);
        } catch (IOException | RuntimeException error) {
            endRecognition();
            notice("Unable to start Hindi transcription");
        }
    }

    private void endRecognition() {
        if (recognitionTimeout != null) handler.removeCallbacks(recognitionTimeout);
        recognitionTimeout = null;
        SpeechRecognizer session = recognizer;
        recognizer = null;
        if (session != null) session.destroy();
        if (audioSource != null) {
            try { audioSource.close(); } catch (IOException ignored) {}
        }
        audioSource = null;
    }

    void open(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!ready(thread)) return;
        thread.files.child(message.snapshot.getKey()).get().addOnCompleteListener(task -> {
            if (!ready(thread)) return;
            String data = task.isSuccessful() ? task.getResult().getValue(String.class) : null;
            if (data == null || data.length() > 1_067_000) {
                notice("Voice note unavailable");
                return;
            }
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                dialog = new ChatFormDialog(fragment.requireContext()).setTitle("Voice note")
                        .setMessage(message.body).setNegativeButton("Close", null)
                        .setPositiveButton("Play", null).show();
                dialog.setOnDismissListener(ignored -> stopPlayback());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> play(bytes));
                play(bytes);
            } catch (IllegalArgumentException error) {
                notice("Unable to read voice note");
            }
        });
    }

    private void play(byte[] bytes) {
        stopPlayback();
        try {
            playingFile = File.createTempFile("voice-note-", ".wav", fragment.requireContext().getCacheDir());
            Files.write(playingFile.toPath(), bytes);
            MediaPlayer audio = new MediaPlayer();
            player = audio;
            audio.setDataSource(playingFile.getAbsolutePath());
            audio.setOnPreparedListener(ready -> { if (player == ready) ready.start(); });
            audio.setOnCompletionListener(ready -> { if (player == ready) stopPlayback(); });
            audio.setOnErrorListener((failed, what, extra) -> {
                stopPlayback();
                notice("Unable to play voice note");
                return true;
            });
            audio.prepareAsync();
        } catch (IOException | RuntimeException error) {
            stopPlayback();
            notice("Unable to play voice note");
        }
    }

    private void stopPlayback() {
        if (player != null) player.release();
        player = null;
        if (playingFile != null) playingFile.delete();
        playingFile = null;
    }

    void stop() {
        if (current != null) current.stopping = true;
        current = null;
        if (dialog != null) dialog.dismiss();
        dialog = null;
        endRecognition();
        stopPlayback();
    }

    void close() {
        stop();
        io.shutdownNow();
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer wave = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return wave.array();
    }

    private boolean ready(FamilyChatStore.ThreadState thread) {
        return fragment.isAdded() && fragment.getView() != null && fragment.isStartedForVoice() && store.canUse(thread);
    }

    private void notice(String text) {
        if (fragment.isAdded()) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private static final class Recording {
        volatile boolean stopping;
        volatile boolean discard;
    }
}
