package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class ChatVoiceControls extends LinearLayout {
    static final int BLUE = 0xff146da9;
    static final int PURPLE = 0xff7650a1;
    static final int MUTED = 0xff59646d;
    static final int BARS = 32;

    final TextView play;
    final TextView speed;
    final TextView time;
    final Wave wave;

    ChatVoiceControls(Context context) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        play = button(context, "▶", "Play voice note", BLUE, 0xfff5faff);
        addView(play, new LayoutParams(dp(context, 48), dp(context, 48)));
        FrameLayout track = new FrameLayout(context);
        wave = new Wave(context);
        track.addView(wave, new FrameLayout.LayoutParams(-1, dp(context, 25), Gravity.TOP));
        time = new TextView(context);
        time.setTextSize(11);
        time.setTextColor(MUTED);
        time.setSingleLine();
        track.addView(time, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LayoutParams trackSize = new LayoutParams(0, dp(context, 44), 1);
        trackSize.setMargins(dp(context, 6), 0, dp(context, 6), 0);
        addView(track, trackSize);
        speed = button(context, "1×", "Playback speed, 1 times", BLUE, 0xfff5faff);
        addView(speed, new LayoutParams(dp(context, 48), dp(context, 48)));
    }

    void show(boolean playing, boolean loading, int position, int duration, int rate, String samples) {
        play.setText(loading ? "…" : playing ? "Ⅱ" : "▶");
        play.setContentDescription(loading ? "Loading voice note" : playing ? "Pause voice note" : "Play voice note");
        play.setEnabled(!loading);
        String label = rate == 0 ? "1×" : rate == 1 ? "1.5×" : "2×";
        speed.setText(label);
        speed.setContentDescription("Playback speed " + label + ". Tap to change");
        time.setText(duration <= 0 ? "Voice note" : position == 0 ? elapsed(duration)
                : elapsed(position) + " / " + elapsed(duration));
        wave.setSamples(samples, duration > 0 ? (float) position / duration : 0);
    }

    static TextView button(Context context, String text, String description, int ink, int fill) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(ink);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(surface(context, fill));
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        return button;
    }

    static GradientDrawable surface(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 16));
        return shape;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static String elapsed(long millis) {
        return DateUtils.formatElapsedTime(Math.max(0, millis) / 1000);
    }

    static final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String samples = "";
        private float progress;
        int idleColor = 0xff909aa7;
        int activeColor = BLUE;

        Wave(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setSamples(String samples, float progress) {
            this.samples = samples == null ? "" : samples;
            this.progress = progress;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float step = (float) getWidth() / BARS;
            float center = getHeight() / 2f;
            paint.setStrokeWidth(Math.min(dp(getContext(), 3), step * 0.64f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < BARS; i++) {
                int amplitude = i < samples.length() ? Character.digit(samples.charAt(i), 16) : 0;
                float half = Math.max(1f, Math.max(0, amplitude) * center * 0.83f / 15f);
                float x = step * (i + 0.5f);
                paint.setColor((i + 1f) / BARS <= progress ? activeColor : idleColor);
                canvas.drawLine(x, center - half, x, center + half, paint);
            }
        }
    }
}
