package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.graphics.Point;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.function.BiConsumer;

final class ChatMessageBubble extends LinearLayout {
    private final GestureDetector gestures;
    private boolean consumedLongPress;
    private boolean selectionTap;
    private final java.util.function.BooleanSupplier selecting;

    ChatMessageBubble(Context context, BiConsumer<View, Point> showMenu,
                    java.util.function.BooleanSupplier selecting, Runnable toggle) {
        super(context);
        this.selecting = selecting;
        setOnLongClickListener(view -> {
            if (!consumedLongPress) showMenu.accept(view, null);
            return true;
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                if (selectionTap) toggle.run();
                return selectionTap;
            }

            @Override public void onLongPress(MotionEvent event) {
                if (!isAttachedToWindow()) return;
                consumedLongPress = true;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                ChatMessageBubble.super.dispatchTouchEvent(cancel);
                cancel.recycle();
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showMenu.accept(ChatMessageBubble.this, new Point(Math.round(event.getRawX()), Math.round(event.getRawY())));
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            consumedLongPress = false;
            selectionTap = selecting.getAsBoolean();
        }
        gestures.onTouchEvent(event);
        return selectionTap || consumedLongPress || super.dispatchTouchEvent(event);
    }
}
