package com.tridev.familyhub.core.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import java.lang.ref.WeakReference;

/** Presentation-only styling for explicitly selected Add/Edit form roots. */
public final class CompactFormStyle {
    private CompactFormStyle() { }

    public static void applyInputs(View root) {
        if (root instanceof TextInputLayout) {
            apply(root);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) applyInputs(group.getChildAt(i));
        }
    }

    public static void apply(View root) {
        if (root instanceof TextInputLayout) {
            TextInputLayout layout = (TextInputLayout) root;
            layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            layout.setBoxCornerRadii(dp(root, 15), dp(root, 15), dp(root, 15), dp(root, 15));
            layout.setBoxStrokeWidth(dp(root, 1));
            layout.setBoxBackgroundColor(ContextCompat.getColor(root.getContext(), R.color.fh_form_surface));
            layout.setBoxStrokeColor(ContextCompat.getColor(root.getContext(), R.color.fh_form_outline));
            layout.setHintTextAppearance(R.style.TextAppearance_FamilyHub_Caption);
            EditText input = layout.getEditText();
            if (input != null && eligibleForVoice(input)
                    && layout.getEndIconMode() == TextInputLayout.END_ICON_NONE) {
                layout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
                layout.setEndIconDrawable(R.drawable.ic_mic);
                layout.setEndIconContentDescription(R.string.family_tasks_voice_add);
                layout.setEndIconOnClickListener(v -> capture(input));
            }
        }
        if (root instanceof EditText) {
            EditText input = (EditText) root;
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            input.setIncludeFontPadding(false);
            input.setMinimumHeight(dp(root, 44));
            input.setPaddingRelative(dp(root, 10), dp(root, 6), dp(root, 8), dp(root, 6));
            input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            ViewGroup.LayoutParams params = input.getLayoutParams();
            if (params != null && params.height > 0) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                input.setLayoutParams(params);
            }
            if (input instanceof MaterialAutoCompleteTextView) {
                input.setSingleLine(false);
                input.setMaxLines(2);
                input.setHorizontallyScrolling(false);
            }
        } else if (root instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) root;
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            button.setCornerRadius(dp(root, 14));
            button.setAllCaps(false);
        } else if (root instanceof RadioButton) {
            ((RadioButton) root).setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }

    /** Preserve the original EditText object and its listeners when giving native forms an outline. */
    public static TextInputLayout field(EditText input) {
        TextInputLayout layout = new TextInputLayout(input.getContext());
        layout.setHint(input.getHint());
        input.setHint(null);
        layout.addView(input, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(input, 5);
        layout.setLayoutParams(params);
        apply(layout);
        return layout;
    }

    private static boolean eligibleForVoice(EditText input) {
        int type = input.getInputType();
        int variation = type & InputType.TYPE_MASK_VARIATION;
        return !"form_no_voice".equals(input.getTag())
                && !(input instanceof MaterialAutoCompleteTextView) && input.isFocusable()
                && (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_NORMAL
                    || variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
                    || variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
    }

    private static void capture(EditText input) {
        WeakReference<EditText> target = new WeakReference<>(input);
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(start, input.getSelectionEnd());
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, Bundle result) {
                EditText field = target.get();
                if (resultCode != 1 || result == null || field == null
                        || !field.isAttachedToWindow()) return;
                String text = result.getString("text", "");
                if (text.isEmpty() || field.getText() == null) return;
                int from = Math.min(start, field.length());
                int to = Math.min(end, field.length());
                field.getText().replace(from, to, text);
                field.setSelection(Math.min(from + text.length(), field.length()));
            }
        };
        Intent intent = new Intent(input.getContext(), FormVoiceCaptureActivity.class);
        intent.putExtra("receiver", receiver);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        input.getContext().startActivity(intent);
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
