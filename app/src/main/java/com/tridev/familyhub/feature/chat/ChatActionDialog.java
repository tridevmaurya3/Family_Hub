package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

final class ChatActionDialog {
    static final int GREEN = 0xff136650;
    private final Context context;
    final LinearLayout content;

    ChatActionDialog(Context context, String title, int icon) {
        this.context = context;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(12));
        content.setBackground(surface(0xfff4faf7, 0xfff4faf7, 22));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), dp(12), dp(12), dp(12));
        heading.setBackground(surface(0xffe2f2ea, 0xffe2f2ea, 14));
        if (icon != 0) {
            ImageView symbol = new ImageView(context);
            symbol.setImageResource(icon);
            symbol.setImageTintList(ColorStateList.valueOf(GREEN));
            heading.addView(symbol, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        TextView name = label(title, 21, GREEN, true);
        if (icon != 0) name.setPadding(dp(10), 0, 0, 0);
        heading.addView(name);
        androidx.core.view.ViewCompat.setAccessibilityHeading(name, true);
        add(content, heading, 0);
    }

    int dp(int value) {
        return FloatingChatSizing.pixels(context, value);
    }

    GradientDrawable surface(int fill, int edge, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(1), edge);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(FloatingChatSizing.text(context, size));
        label.setTextColor(color);
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    MaterialButton button(String text, boolean primary) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setAllCaps(false);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(FloatingChatSizing.target(context, 48));
        FloatingChatSizing.compactButton(button);
        button.setPadding(dp(4), dp(4), dp(4), dp(4));
        button.setCornerRadius(dp(12));
        button.setTextColor(primary ? 0xffffffff : GREEN);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? GREEN : 0xffffffff));
        button.setStrokeColor(ColorStateList.valueOf(primary ? GREEN : 0xffcbded4));
        return button;
    }

    void add(LinearLayout target, android.view.View child, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        target.addView(child, size);
    }
}
