package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;

/** Shared Family Hub presentation for new chat dialogs and their form fields. */
final class ChatFormDialog extends MaterialAlertDialogBuilder {
    private CharSequence[] options;
    private DialogInterface.OnClickListener selection;
    private boolean singleChoice;
    private int checked;

    ChatFormDialog(Context context) {
        super(context, R.style.ThemeOverlay_FamilyHub_FormDialog);
    }

    @NonNull @Override public ChatFormDialog setItems(@NonNull CharSequence[] items,
                                                      @Nullable DialogInterface.OnClickListener listener) {
        options = items;
        selection = listener;
        singleChoice = false;
        return this;
    }

    @NonNull @Override public ChatFormDialog setSingleChoiceItems(@NonNull CharSequence[] items,
            int checkedItem, @Nullable DialogInterface.OnClickListener listener) {
        options = items;
        checked = checkedItem;
        selection = listener;
        singleChoice = true;
        return this;
    }

    @NonNull @Override public AlertDialog create() {
        if (options == null) return super.create();
        Context context = getContext();
        // Keep actions reachable in landscape and on short screens.
        int screenHeight = context.getResources().getConfiguration().screenHeightDp;
        int maxHeight = dp(context, Math.max(112, screenHeight * 55 / 100));
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        scroll.addView(choices);
        super.setView(scroll);
        AlertDialog dialog = super.create();
        for (int index = 0; index < options.length; index++) {
            int item = index;
            MaterialButton option = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            option.setAllCaps(false);
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setMinHeight(dp(context, 48));
            option.setMaxLines(Integer.MAX_VALUE);
            option.setText(options[index]);
            option.setCheckable(singleChoice);
            if (singleChoice) {
                option.setChecked(index == checked);
                option.setText(choiceLabel(index, checked));
            }
            option.setOnClickListener(view -> {
                if (singleChoice) {
                    for (int i = 0; i < choices.getChildCount(); i++) {
                        MaterialButton row = (MaterialButton) choices.getChildAt(i);
                        row.setChecked(i == item);
                        row.setText(choiceLabel(i, item));
                    }
                } else {
                    dialog.dismiss();
                }
                if (selection != null) selection.onClick(dialog, item);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(context, 4);
            choices.addView(option, params);
        }
        return dialog;
    }

    private CharSequence choiceLabel(int index, int selected) {
        return (index == selected ? "✓  " : "○  ") + options[index];
    }

    static TextInputLayout outlined(EditText input) {
        Context context = input.getContext();
        TextInputLayout layout = new TextInputLayout(context, null,
                com.google.android.material.R.attr.textInputStyle);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setHint(input.getHint());
        input.setHint(null);
        input.setBackground(null);
        input.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        input.setMinHeight(dp(context, 50));
        input.setTextColor(ContextCompat.getColor(context, R.color.fh_on_surface));
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 8);
        layout.setLayoutParams(params);
        return layout;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
