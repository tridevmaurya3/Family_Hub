package com.tridev.familyhub.feature.planner;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.function.Consumer;

/** Compact anchored menus matching the existing form surfaces. */
final class PlannerPanels {
    static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static LinearLayout content(Context c, String title) {
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.rgb(248,252,250)); shape.setCornerRadius(dp(c,16)); shape.setStroke(dp(c,1),Color.rgb(184,207,199)); box.setBackground(shape);
        TextView label = new TextView(c); label.setText(title); label.setTextSize(12); label.setTextColor(Color.rgb(32,92,77)); box.addView(label); return box;
    }
    static void choices(Context c, View anchor, boolean floating, String[] values, String current, Consumer<String> selected, List<PopupWindow> tracked) {
        LinearLayout box = content(c, ""); box.removeAllViews();
        {box.setPadding(dp(c,5),dp(c,5),dp(c,5),dp(c,5));box.setBackground(com.tridev.familyhub.core.ui.ScheduleDropdownView.surface(c,true));}
        int rowHeight = dp(c,36);
        int width = dp(c,120);
        android.graphics.Paint paint = new android.graphics.Paint(); paint.setTextSize(13*c.getResources().getDisplayMetrics().scaledDensity);
        for (String value : values) width = Math.max(width, (int)paint.measureText(value.replace('_',' '))+dp(c,48));
        ScrollView scroll = new ScrollView(c); scroll.addView(box);
        PopupWindow popup = new PopupWindow(scroll, Math.min(width,c.getResources().getDisplayMetrics().widthPixels-dp(c,24)),
                Math.min(rowHeight*values.length+dp(c,10),c.getResources().getDisplayMetrics().heightPixels/2),true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); popup.setOutsideTouchable(true); popup.setElevation(dp(c,8));
        if (floating) popup.setWindowLayoutType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        for (String value : values) {
            TextView row = new TextView(c); row.setText((value.equals(current)?"✓  ":"")+value.replace('_',' ')); row.setTextSize(13); row.setTextColor(Color.rgb(32,92,77)); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(dp(c,6),0,dp(c,6),0);
            {row.setTextSize(12.5f);row.setIncludeFontPadding(false);row.setPadding(dp(c,12),dp(c,2),dp(c,12),dp(c,2));row.setBackground(com.tridev.familyhub.core.ui.ScheduleDropdownView.surface(c,false));}
            box.addView(row,new LinearLayout.LayoutParams(-1,rowHeight)); row.setOnClickListener(v->{popup.dismiss();selected.accept(value);});
        }
        tracked.add(popup); popup.setOnDismissListener(()->tracked.remove(popup)); popup.showAsDropDown(anchor);
    }
}
