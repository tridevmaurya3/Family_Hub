package com.tridev.familyhub.feature.chat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.tridev.familyhub.feature.chat.ChatVoiceControls.*;

final class ChatVoiceNotes {
    private static final int FILE_BYTES = 1_048_576;
    private static final int SAMPLE_INTERVAL_MS = 80;
    private final FamilyChatFragment fragment;
    private final FamilyChatStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> permission;
    private final List<WeakReference<VoiceMessage>> visiblePlayers = new ArrayList<>();
    private final Map<String, Integer> rates = new HashMap<>();
    private final Runnable recordTick = this::sample;
    private final Runnable playerTick = this::updatePlayback;
    private FamilyChatStore.ThreadState awaitingPermission;
    private FamilyChatStore.ThreadState thread;
    private LinearLayout composer;
    private LinearLayout input;
    private View textSend;
    private TextView timer;
    private ChatVoiceControls.Wave liveWave;
    private MediaRecorder recorder;
    private File draft;
    private String waveform;
    private long started;
    private int duration;
    private boolean locked;
    private boolean sending;
    private float touchStart;
    private int pointer = -1;
    private long playbackRequest;
    private boolean inputVisible;
    private final StringBuilder liveLevels = new StringBuilder(BARS);
    private final StringBuilder levels = new StringBuilder();
    private int samplesPerLevel = 1;
    private int sampleCount;
    private char peak;
    private MediaPlayer player;
    private File playbackFile;
    private String playingKey;
    private boolean preparing;
    private boolean prepared;
    private ChatVoiceControls preview;

    ChatVoiceNotes(FamilyChatFragment fragment, FamilyChatStore store) {
        this.fragment = fragment;
        this.store = store;
        permission = fragment.registerForActivityResult(new ActivityResultContracts.RequestPermission(), allowed -> {
            FamilyChatStore.ThreadState requested = awaitingPermission;
            awaitingPermission = null;
            if (allowed && ready(requested)) start(requested, true);
            else if (!allowed && requested != null) notice("Allow microphone access to record a voice note");
        });
    }

    void bind(LinearLayout input, LinearLayout composer, View textSend) {
        this.input = input;
        this.composer = composer;
        this.textSend = textSend;
    }

    void setInputVisible(boolean visible) {
        inputVisible = visible;
        boolean holding = recorder != null && !locked;
        textSend.setVisibility(holding ? View.GONE : View.VISIBLE);
        input.setVisibility(visible && (draft == null || holding) ? View.VISIBLE : View.GONE);
        for (int i = 0; i < input.getChildCount() - 2; i++) {
            input.getChildAt(i).setVisibility(holding ? View.INVISIBLE : View.VISIBLE);
        }
    }

    boolean touch(View mic, MotionEvent event, FamilyChatStore.ThreadState selected) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                touchStart = event.getRawY();
                record(selected, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pointer == event.getPointerId(0) && recorder != null && !locked
                        && event.getRawY() - touchStart >= dp(mic.getContext(), 24)) {
                    locked = true;
                    renderRecording();
                }
                return true;
            case MotionEvent.ACTION_UP:
                pointer = -1;
                if (recorder != null && !locked) finish(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                        || event.getPointerId(event.getActionIndex()) == pointer) {
                    pointer = -1;
                    if (recorder != null && !locked) finish(false);
                }
                return true;
            default:
                return true;
        }
    }

    void record(FamilyChatStore.ThreadState selected) {
        record(selected, true);
    }

    private void record(FamilyChatStore.ThreadState selected, boolean handsFree) {
        if (!ready(selected) || draft != null || awaitingPermission != null) return;
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = selected;
            permission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        start(selected, handsFree);
    }

    @SuppressWarnings("deprecation")
    private void start(FamilyChatStore.ThreadState selected, boolean handsFree) {
        stopPlayback();
        Context context = fragment.requireContext();
        try {
            draft = File.createTempFile("chat-voice-", ".m4a", context.getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(24000);
            // Leave room for the MP4 index within the existing attachment byte budget.
            recorder.setMaxFileSize(FILE_BYTES - 64 * 1024L);
            recorder.setOutputFile(draft.getAbsolutePath());
            recorder.setOnInfoListener((source, what, extra) -> {
                if (source == recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    finish(false);
                    notice("Attachment size reached. Review your recording before sending.");
                }
            });
            recorder.setOnErrorListener((source, what, extra) -> {
                if (source == recorder) {
                    clearDraft();
                    notice("Recording stopped because of a microphone error");
                }
            });
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException error) {
            clearDraft();
            notice("Unable to start recording. Check microphone access.");
            return;
        }
        thread = selected;
        locked = handsFree;
        started = SystemClock.elapsedRealtime();
        levels.setLength(0);
        liveLevels.setLength(0);
        samplesPerLevel = 1;
        sampleCount = 0;
        peak = '0';
        renderRecording();
        sample();
    }

    private void sample() {
        if (recorder == null) return;
        if (!ready(thread)) {
            clearDraft();
            return;
        }
        int amplitude;
        try {
            amplitude = recorder.getMaxAmplitude();
        } catch (IllegalStateException error) {
            finish(false);
            return;
        }
        char level = Character.forDigit(Math.min(15, Math.round((float) Math.sqrt(amplitude / 32767f) * 19)), 16);
        peak = (char) Math.max(peak, level);
        if (++sampleCount == samplesPerLevel) {
            levels.append(peak);
            peak = '0';
            sampleCount = 0;
        }
        if (levels.length() == 512) {
            for (int i = 0; i < 256; i++) {
                levels.setCharAt(i, (char) Math.max(levels.charAt(i * 2), levels.charAt(i * 2 + 1)));
            }
            levels.setLength(256);
            samplesPerLevel *= 2;
        }
        if (liveLevels.length() == BARS) liveLevels.deleteCharAt(0);
        liveLevels.append(level);
        liveWave.setSamples(liveLevels.toString(), 0);
        timer.setText((locked ? "🔒  " : "●  ") + elapsed(SystemClock.elapsedRealtime() - started));
        handler.postDelayed(recordTick, SAMPLE_INTERVAL_MS);
    }

    private void renderRecording() {
        Context context = composer.getContext();
        composer.removeAllViews();
        composer.setVisibility(View.VISIBLE);
        setInputVisible(inputVisible);
        TextView hint = new TextView(context);
        hint.setText("↓  Slide down to lock");
        hint.setTextColor(PURPLE);
        hint.setGravity(Gravity.END);
        if (!locked) composer.addView(hint);
        LinearLayout track = row(context);
        track.setBackground(surface(context, 0xfff6ecfa));
        track.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        timer = new TextView(context);
        timer.setTextColor(locked ? PURPLE : 0xffaa1735);
        timer.setText((locked ? "🔒  " : "●  ") + elapsed(SystemClock.elapsedRealtime() - started));
        track.addView(timer, new LinearLayout.LayoutParams(dp(context, 75), -2));
        liveWave = new ChatVoiceControls.Wave(context);
        liveWave.idleColor = PURPLE;
        liveWave.setSamples(liveLevels.toString(), 0);
        track.addView(liveWave, new LinearLayout.LayoutParams(0, dp(context, 54), 1));
        LinearLayout.LayoutParams trackSize = new LinearLayout.LayoutParams(-1, -2);
        if (!locked) trackSize.setMarginEnd(dp(context, 56));
        composer.addView(track, trackSize);
        if (locked) {
            LinearLayout actions = row(context);
            action(actions, "Cancel", MUTED, 0xffedf0f3, this::clearDraft);
            action(actions, "■ Stop", 0xffaa1735, 0xfffbe0e7, () -> finish(false));
            sendAction(actions, () -> finish(true));
            composer.addView(actions);
        }
    }

    private void finish(boolean sendNow) {
        if (recorder == null) return;
        handler.removeCallbacks(recordTick);
        try {
            recorder.stop();
        } catch (RuntimeException error) {
            clearDraft();
            notice("Voice note is too short or could not be recorded");
            return;
        }
        recorder.release();
        recorder = null;
        if (draft.length() == 0 || draft.length() > FILE_BYTES) {
            clearDraft();
            notice("Voice note could not fit in a chat attachment");
            return;
        }
        duration = (int) Math.max(1, SystemClock.elapsedRealtime() - started);
        if (sampleCount > 0) levels.append(peak);
        waveform = summarize(levels);
        renderPreview();
        if (sendNow) send();
    }

    private static String summarize(CharSequence source) {
        StringBuilder result = new StringBuilder(BARS);
        for (int i = 0; i < BARS; i++) {
            int start = i * source.length() / BARS;
            int end = (i + 1) * source.length() / BARS;
            char peak = '0';
            for (int j = start; j < Math.max(start + 1, end) && j < source.length(); j++) {
                peak = (char) Math.max(peak, source.charAt(j));
            }
            result.append(peak);
        }
        return result.toString();
    }

    private void renderPreview() {
        Context context = composer.getContext();
        input.setVisibility(View.GONE);
        composer.setVisibility(View.VISIBLE);
        composer.removeAllViews();
        LinearLayout track = row(context);
        track.setBackground(surface(context, 0xffeef2f7));
        preview = new ChatVoiceControls(context);
        preview.speed.setVisibility(View.GONE);
        preview.play.setOnClickListener(v -> {
            if ("draft".equals(playingKey)) togglePlayback();
            else playFile("draft", draft, false);
        });
        preview.show(false, false, 0, duration, 0, waveform);
        track.addView(preview, new LinearLayout.LayoutParams(0, -2, 3));
        action(track, "Delete", MUTED, 0xffedf0f3, this::clearDraft);
        sendAction(track, this::send);
        composer.addView(track);
    }

    private void send() {
        if (!ready(thread) || draft == null || sending) return;
        sending = true;
        File source = draft;
        FamilyChatStore.ThreadState destination = thread;
        stopPlayback();
        Map<String, Object> details = new HashMap<>();
        String capturedWave = waveform;
        int capturedDuration = duration;
        String replyTarget = fragment.replyTarget();
        if (replyTarget != null) details.put("replyTo", replyTarget);
        details.put("kind", "file");
        details.put("mime", ChatVoiceAttachment.contentType(capturedDuration, capturedWave));
        io.execute(() -> {
            String encoded;
            try {
                encoded = Base64.encodeToString(Files.readAllBytes(source.toPath()), Base64.NO_WRAP);
            } catch (IOException error) {
                handler.post(() -> {
                    if (draft == source) {
                        sending = false;
                        notice("Unable to read recording");
                    }
                });
                return;
            }
            handler.post(() -> {
                if (draft != source || !ready(destination)) return;
                draft = null;
                clearDraft();
                store.send(destination, "Voice note", details, encoded, () -> {}, success -> {
                    if (!success && ready(destination) && draft == null) {
                        draft = source;
                        thread = destination;
                        waveform = capturedWave;
                        duration = capturedDuration;
                        renderPreview();
                    } else {
                        source.delete();
                    }
                    if (success && ready(destination)) fragment.completeVoiceReply(destination, replyTarget);
                    if (!success && ready(destination)) notice("Unable to send voice note");
                });
            });
        });
    }

    private LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void action(LinearLayout row, String label, int ink, int fill, Runnable click) {
        Context context = row.getContext();
        TextView button = button(context, label, label, ink, fill);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 48), 1);
        params.setMarginStart(dp(context, 4));
        params.topMargin = dp(context, 3);
        row.addView(button, params);
        button.setOnClickListener(v -> click.run());
    }

    private void sendAction(LinearLayout row, Runnable click) {
        Context context = row.getContext();
        com.google.android.material.button.MaterialButton send =
                new com.google.android.material.button.MaterialButton(context);
        send.setMinWidth(0);
        send.setMinimumWidth(0);
        send.setPadding(0, 0, 0, 0);
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        send.setTextColor(0xffffffff);
        ChatSendButton.style(send, "Send voice note");
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        size.setMarginStart(dp(context, 4));
        row.addView(send, size);
        send.setOnClickListener(v -> click.run());
    }

    View messageView(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        return new VoiceMessage(thread, message);
    }

    void open(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!ready(thread) || message.deleted() || recorder != null) return;
        String key = message.snapshot.getRef().toString();
        if (key.equals(playingKey)) {
            togglePlayback();
            return;
        }
        stopPlayback();
        playingKey = key;
        preparing = true;
        refreshPlayers();
        long request = playbackRequest;
        thread.files.child(message.snapshot.getKey()).get().addOnCompleteListener(task -> {
            if (request != playbackRequest || !ready(thread) || !preparing) return;
            String data = task.isSuccessful() ? task.getResult().getValue(String.class) : null;
            if (data == null || data.length() > 1_398_104) {
                failPlayback("Voice note unavailable");
                return;
            }
            File cache = fragment.requireContext().getCacheDir();
            io.execute(() -> loadPlayback(thread, key, request, cache, data));
        });
    }

    private void loadPlayback(FamilyChatStore.ThreadState thread, String key, long request, File cache, String encoded) {
        File file = null;
        try {
            file = File.createTempFile("chat-play-", ".audio", cache);
            Files.write(file.toPath(), Base64.decode(encoded, Base64.DEFAULT));
        } catch (IOException | IllegalArgumentException error) {
            if (file != null) file.delete();
            handler.post(() -> { if (request == playbackRequest) failPlayback("Unable to read voice note"); });
            return;
        }
        File audio = file;
        handler.post(() -> {
            if (request == playbackRequest && ready(thread) && preparing) playFile(key, audio, true);
            else audio.delete();
        });
    }

    private void playFile(String key, File source, boolean temporary) {
        stopPlayback();
        playingKey = key;
        preparing = true;
        if (temporary) playbackFile = source;
        try {
            MediaPlayer audio = new MediaPlayer();
            player = audio;
            audio.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            try (java.io.FileInputStream stream = new java.io.FileInputStream(source)) {
                audio.setDataSource(stream.getFD());
            }
            audio.setOnPreparedListener(loaded -> {
                if (player != loaded) return;
                preparing = false;
                prepared = true;
                try {
                    loaded.start();
                    applyRate();
                    updatePlayback();
                } catch (RuntimeException error) {
                    failPlayback("Unable to play voice note");
                }
            });
            audio.setOnCompletionListener(finished -> {
                if (player == finished) stopPlayback();
            });
            audio.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) failPlayback("Unable to play voice note");
                return true;
            });
            audio.prepareAsync();
            refreshPlayers();
        } catch (IOException | RuntimeException error) {
            failPlayback("Unable to play voice note");
        }
    }

    private void togglePlayback() {
        if (!prepared) return;
        try {
            if (player.isPlaying()) player.pause();
            else {
                player.start();
                applyRate();
            }
            updatePlayback();
        } catch (RuntimeException error) {
            failPlayback("Unable to play voice note");
        }
    }

    private void applyRate() {
        int rate = rates.getOrDefault(playingKey, 0);
        player.setPlaybackParams(player.getPlaybackParams().setSpeed(1f + rate * 0.5f));
    }

    private void updatePlayback() {
        handler.removeCallbacks(playerTick);
        refreshPlayers();
        if (prepared && player.isPlaying()) handler.postDelayed(playerTick, 100);
    }

    private void refreshPlayers() {
        Iterator<WeakReference<VoiceMessage>> iterator = visiblePlayers.iterator();
        while (iterator.hasNext()) {
            VoiceMessage view = iterator.next().get();
            if (view == null || !view.isAttachedToWindow()) iterator.remove();
            else view.update();
        }
        if (preview != null) update(preview, "draft", duration, waveform);
    }

    private void update(ChatVoiceControls view, String key, int duration, String samples) {
        boolean active = key.equals(playingKey);
        boolean loaded = active && prepared;
        view.show(loaded && player.isPlaying(), active && preparing,
                loaded ? player.getCurrentPosition() : 0, loaded ? player.getDuration() : duration,
                rates.getOrDefault(key, 0), samples);
    }

    private void failPlayback(String message) {
        stopPlayback();
        notice(message);
    }

    void stopMessagePlayback(FamilyChatStore.Message message) {
        if (message.snapshot.getRef().toString().equals(playingKey)) stopPlayback();
    }

    private void stopPlayback() {
        playbackRequest++;
        handler.removeCallbacks(playerTick);
        if (player != null) player.release();
        player = null;
        if (playbackFile != null) playbackFile.delete();
        playbackFile = null;
        playingKey = null;
        prepared = false;
        preparing = false;
        refreshPlayers();
    }

    private void clearDraft() {
        handler.removeCallbacks(recordTick);
        if (recorder != null) recorder.release();
        recorder = null;
        if ("draft".equals(playingKey)) stopPlayback();
        if (draft != null) draft.delete();
        draft = null;
        thread = null;
        preview = null;
        sending = false;
        if (composer != null) {
            composer.removeAllViews();
            composer.setVisibility(View.GONE);
        }
        if (input != null) setInputVisible(inputVisible);
    }

    void stop() {
        awaitingPermission = null;
        clearDraft();
        stopPlayback();
        rates.clear();
    }

    void close() {
        stop();
        io.shutdown();
    }

    private boolean ready(FamilyChatStore.ThreadState thread) {
        return thread != null && composer != null && fragment.isAdded() && fragment.getView() != null
                && fragment.isStartedForVoice() && fragment.isVoiceThreadOpen(thread) && store.canUse(thread);
    }

    private void notice(String text) {
        if (fragment.isAdded()) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private final class VoiceMessage extends LinearLayout {
        private final ChatVoiceControls controls;
        private final String key;
        private final int duration;
        private final String waveform;

        VoiceMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
            super(fragment.requireContext());
            setOrientation(VERTICAL);
            key = message.snapshot.getRef().toString();
            ChatVoiceAttachment attachment = message.voiceAttachment();
            Integer millis = message.snapshot.child("voiceDuration").getValue(Integer.class);
            duration = attachment != null ? attachment.duration : millis == null ? 0 : millis;
            waveform = attachment != null ? attachment.waveform
                    : message.snapshot.child("voiceWave").getValue(String.class);
            controls = new ChatVoiceControls(getContext());
            controls.play.setOnClickListener(v -> open(thread, message));
            controls.speed.setOnClickListener(v -> {
                int old = rates.getOrDefault(key, 0);
                rates.put(key, (old + 1) % 3);
                if (key.equals(playingKey) && prepared && player.isPlaying()) {
                    try {
                        applyRate();
                    } catch (RuntimeException error) {
                        rates.put(key, old);
                        notice("This playback speed is unavailable");
                    }
                }
                update();
            });
            addView(controls, new LayoutParams(-1, -2));
            if (!"Voice note".equals(message.body)) {
                TextView transcript = new TextView(getContext());
                transcript.setText(message.body);
                addView(transcript);
            }
            update();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            visiblePlayers.add(new WeakReference<>(this));
            update();
        }

        @Override protected void onDetachedFromWindow() {
            visiblePlayers.removeIf(ref -> ref.get() == this || ref.get() == null);
            super.onDetachedFromWindow();
        }

        void update() {
            ChatVoiceNotes.this.update(controls, key, duration, waveform);
        }
    }
}
