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
        if (title == null || title.isEmpty()) heading.setVisibility(View.GONE);
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
    interface OptionAction { void selected(String option); }
    static android.widget.PopupWindow dropdown(Context context, boolean overlay, View anchor,
            java.util.List<String> options, OptionAction action) {
        LinearLayout rows = content(context, "");
        rows.setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4));
        android.widget.ScrollView scroll = (android.widget.ScrollView) android.view.LayoutInflater.from(context)
                .inflate(com.tridev.familyhub.R.layout.notes_panel_scroll, null, false);
        scroll.addView(rows, new android.widget.ScrollView.LayoutParams(-1, -2));
        android.widget.PopupWindow menu = new android.widget.PopupWindow(context);
        for (String option : options) {
            TextView item = new TextView(context); item.setText(option); item.setTextSize(12);
            item.setTextColor("Remove".equals(option) ? Color.rgb(176, 52, 65) : Color.rgb(108, 76, 155));
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
            pressed.setColor(Color.rgb(235, 226, 249)); pressed.setCornerRadius(dp(context, 10));
            android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
            background.addState(new int[]{android.R.attr.state_pressed}, pressed);
            background.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            item.setBackground(background);
            rows.addView(item, new LinearLayout.LayoutParams(-1, dp(context, 44)));
            item.setOnClickListener(v -> { menu.dismiss(); action.selected(option); });
        }
        rows.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(context, 24), rows.getMeasuredWidth());
        menu.setContentView(scroll); menu.setWidth(width); menu.setHeight(dropdownHeight(rows, context));
        menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        menu.setElevation(dp(context, 4)); menu.setFocusable(true); menu.setOutsideTouchable(true);
        if (overlay) menu.setWindowLayoutType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        menu.showAsDropDown(anchor, 0, dp(context, 4), android.view.Gravity.END);
        return menu;
    }
    private static int dropdownHeight(View rows, Context context) {
        return Math.min(rows.getMeasuredHeight(), context.getResources().getDisplayMetrics().heightPixels - dp(context, 96));
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
