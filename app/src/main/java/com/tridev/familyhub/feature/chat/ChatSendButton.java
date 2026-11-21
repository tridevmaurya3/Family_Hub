package com.tridev.familyhub.feature.chat;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.tridev.familyhub.R;

final class ChatSendButton {
    private ChatSendButton() {}

    static void style(TextView button, CharSequence description) {
        button.setText(null);
        button.setContentDescription(description);
        button.setGravity(Gravity.CENTER);
        if (button instanceof MaterialButton) {
            MaterialButton material = (MaterialButton) button;
            material.setIconResource(R.drawable.ic_chat_send);
            material.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            material.setIconPadding(0);
            material.setIconTint(button.getTextColors());
        } else {
            Drawable icon = ContextCompat.getDrawable(button.getContext(), R.drawable.ic_chat_send).mutate();
            icon.setTintList(button.getTextColors());
            int width = ChatVoiceControls.dp(button.getContext(), 48);
            int padding = (width - icon.getIntrinsicWidth()) / 2;
            button.setMinWidth(width);
            button.setMinimumWidth(width);
            button.setPaddingRelative(padding, button.getPaddingTop(), padding, button.getPaddingBottom());
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        }
    }

    static void stylePositive(AlertDialog dialog) {
        TextView button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        style(button, button.getText());
    }
}
