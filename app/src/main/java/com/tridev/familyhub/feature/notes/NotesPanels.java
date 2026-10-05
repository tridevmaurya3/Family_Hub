package com.tridev.familyhub.feature.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;

/** Shared Notes surfaces for action menus, checklist viewing and quick options. */
final class NotesPanels {
    static LinearLayout content(Context context, String title) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(250, 247, 255));
        background.setCornerRadius(dp(context, 20)); background.setStroke(dp(context, 1), Color.rgb(227, 218, 243)); root.setBackground(background);
        TextView heading = new TextView(context); heading.setText(title); heading.setTextSize(16);
        heading.setTextColor(Color.rgb(108, 76, 155)); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(context, 10)); root.addView(heading);
        return root;
    }
    static MaterialButton action(Context context, String text) {
        MaterialButton button = new MaterialButton(context); button.setText(text); button.setAllCaps(false);
        button.setTextSize(12); button.setCornerRadius(dp(context, 14)); button.setInsetTop(0); button.setInsetBottom(0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(241, 235, 252)));
        button.setTextColor(Color.rgb(116, 80, 167)); button.setStrokeWidth(dp(context, 1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(224, 214, 241)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(context, 48)); params.topMargin = dp(context, 5);
        button.setLayoutParams(params); return button;
    }
    static AlertDialog show(Context context, boolean overlay, LinearLayout content) {
        android.widget.ScrollView scroll = (android.widget.ScrollView) android.view.LayoutInflater.from(context)
                .inflate(com.tridev.familyhub.R.layout.notes_panel_scroll, null, false);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setView(scroll).create();
        NotesEditor.present(dialog, overlay);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (dialog.getWindow() != null) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int maxWidth = Math.max(1, metrics.widthPixels - dp(context, 40));
            int maxHeight = Math.max(1, metrics.heightPixels - dp(context, 96));
            // Measure natural text width first, then wrap long content within the screen.
            content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int width = Math.min(maxWidth, Math.max(dp(context, 160), content.getMeasuredWidth()));
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = Math.min(maxHeight, content.getMeasuredHeight());
            dialog.getWindow().setLayout(width, height);
        }
        return dialog;
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
