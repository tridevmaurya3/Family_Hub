package com.tridev.familyhub.core.ui;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Item actions matching the Notes dropdown, also safe for service overlay windows. */
public final class FloatingItemActions {
    private FloatingItemActions() { }
    public static com.google.android.material.button.MaterialButton button(Context context, String title) {
        context = new android.view.ContextThemeWrapper(context, com.tridev.familyhub.R.style.Theme_FamilyHub);
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonStyle);
        button.setText("⋮"); button.setTextSize(20); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0); button.setInsetTop(0); button.setInsetBottom(0);
        button.setContentDescription("Actions for " + title);
        button.setTextColor(Color.rgb(108, 76, 155));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context,44),dp(context,44)));
        return button;
    }
    public interface OptionAction { void selected(String option); }
    public static android.widget.PopupWindow dropdown(Context context, boolean overlay, View anchor,
            java.util.List<String> options, OptionAction action) {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(250, 247, 255)); shape.setCornerRadius(dp(context, 20));
        shape.setStroke(dp(context, 1), Color.rgb(227, 218, 243)); rows.setBackground(shape);
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
        View.OnAttachStateChangeListener detach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { menu.dismiss(); }
        };
        anchor.addOnAttachStateChangeListener(detach);
        menu.setOnDismissListener(() -> anchor.removeOnAttachStateChangeListener(detach));
        return menu;
    }
    private static int dropdownHeight(View rows, Context context) {
        return Math.min(rows.getMeasuredHeight(), context.getResources().getDisplayMetrics().heightPixels - dp(context, 96));
    }
    static int dp(Context context,int n) { return Math.round(n*context.getResources().getDisplayMetrics().density); }
}
