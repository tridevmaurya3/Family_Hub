package com.tridev.familyhub.feature.tasks.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Compact red listening waveform. GONE while idle so it occupies no layout space. */
public final class TaskVoiceWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean listening;
    private boolean processing;
    private float level;
    private long startedAt;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            invalidate();
            postDelayed(this, 70L);
        }
    };

    public TaskVoiceWaveView(Context context) {
        this(context, null);
    }

    public TaskVoiceWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.rgb(220, 45, 82));
        paint.setStrokeCap(Paint.Cap.ROUND);
        setVisibility(GONE);
        setContentDescription("Voice listening waveform");
    }

    public void startListening() {
        processing = false;
        level = Math.max(level, 0.18f);
        if (!listening) {
            listening = true;
            startedAt = SystemClock.uptimeMillis();
            setVisibility(VISIBLE);
            removeCallbacks(frame);
            post(frame);
        }
    }

    public void setLevel(float rmsDb) {
        if (!listening) startListening();
        level = Math.max(0.12f, Math.min(1f, (rmsDb + 2f) / 12f));
    }

    public void showProcessing() {
        if (!listening) startListening();
        processing = true;
        level = 0.35f;
    }

    public void stopListening() {
        listening = false;
        processing = false;
        level = 0f;
        removeCallbacks(frame);
        setVisibility(GONE);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!listening) return;

        float width = getWidth();
        float height = getHeight();
        float centerY = height / 2f;
        int bars = 21;
        float gap = width / (bars + 1f);
        float phase = (SystemClock.uptimeMillis() - startedAt) / 155f;
        paint.setStrokeWidth(Math.max(2f, gap * 0.34f));

        for (int i = 0; i < bars; i++) {
            float wave = (float) Math.abs(Math.sin(phase + i * 0.61f));
            float secondary = (float) Math.abs(Math.cos(phase * 0.72f + i * 0.37f));
            float energy = processing
                    ? (0.22f + 0.25f * wave)
                    : (0.18f + (0.48f * wave + 0.24f * secondary) * level);
            float half = Math.max(2f, height * Math.min(0.46f, energy) / 2f);
            float x = gap * (i + 1);
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
    }
}
