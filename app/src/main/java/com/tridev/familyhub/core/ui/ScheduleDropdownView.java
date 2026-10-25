package com.tridev.familyhub.core.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.material.textfield.*;

/** Presentation-only chooser using the Grocery floating field and popup dimensions. */
public final class ScheduleDropdownView extends LinearLayout {
    private Spinner spinner;
    public ScheduleDropdownView(Context c, boolean floating, String label, String[] values) {
        super(c); setOrientation(VERTICAL);

            TextView caption=new TextView(c);caption.setText(label);caption.setTextSize(10);caption.setTextColor(Color.rgb(83,91,102));caption.setPadding(dp(c,2),0,0,dp(c,3));addView(caption,new LayoutParams(-1,dp(c,16)));
            spinner=new Spinner(c,Spinner.MODE_DROPDOWN);
            spinner.setAdapter(new ArrayAdapter<String>(c,android.R.layout.simple_spinner_item,values){
                @Override public View getView(int p,View old,ViewGroup parent){TextView text=new TextView(c);text.setText(getItem(p)+" ▾");text.setTextColor(Color.rgb(36,36,36));text.setTextSize(11);text.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);text.setIncludeFontPadding(false);text.setMaxLines(2);text.setPadding(dp(c,7),dp(c,2),dp(c,18),dp(c,2));return text;}
                @Override public View getDropDownView(int p,View old,ViewGroup parent){return popupRow(c,getItem(p));}
            });
            spinner.setBackground(surface(c,false));spinner.setElevation(dp(c,1));spinner.setPopupBackgroundDrawable(surface(c,true));spinner.setDropDownVerticalOffset(dp(c,4));spinner.setDropDownWidth(popupWidth(c,values));addView(spinner,new LayoutParams(-1,dp(c,48)));

    }

    public static void alignQuickRow(TextInputLayout title, ScheduleDropdownView choice, com.google.android.material.button.MaterialButton add, String label) {
        Context c=title.getContext();LinearLayout row=(LinearLayout)title.getParent();int index=row.indexOfChild(title);LinearLayout.LayoutParams original=(LinearLayout.LayoutParams)title.getLayoutParams();row.removeView(title);
        LinearLayout column=new LinearLayout(c);column.setOrientation(VERTICAL);TextView caption=new TextView(c);caption.setText(label);caption.setTextSize(10);caption.setTextColor(Color.rgb(83,91,102));caption.setPadding(dp(c,2),0,0,dp(c,3));column.addView(caption,new LayoutParams(-1,dp(c,16)));
        title.setHintEnabled(false);title.getEditText().setMinimumHeight(dp(c,48));column.addView(title,new LayoutParams(-1,dp(c,48)));row.addView(column,index,original);
        row.setGravity(Gravity.TOP);LayoutParams chooser=(LayoutParams)choice.getLayoutParams();chooser.setMarginStart(dp(c,4));choice.setLayoutParams(chooser);
        LayoutParams button=new LayoutParams(dp(c,48),dp(c,48));button.topMargin=dp(c,16);button.setMarginStart(dp(c,4));add.setLayoutParams(button);add.setInsetTop(0);add.setInsetBottom(0);add.setMinimumHeight(dp(c,48));
    }
    public static void styleFilters(View view){
        if(view instanceof com.google.android.material.button.MaterialButton){
            com.google.android.material.button.MaterialButton b=(com.google.android.material.button.MaterialButton)view;
            b.setBackgroundTintList(null);b.setBackground(surface(view.getContext(),false));b.setElevation(dp(view.getContext(),1));b.setTextColor(Color.rgb(36,36,36));b.setTextSize(11);b.setIncludeFontPadding(false);b.setSingleLine(true);b.setPadding(dp(view.getContext(),9),0,dp(view.getContext(),9),0);
            ViewGroup.LayoutParams p=b.getLayoutParams();p.height=dp(view.getContext(),38);b.setLayoutParams(p);
        }else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleFilters(group.getChildAt(i));}
    }
    /** Select an existing option when binding an editor or resetting its fields. */
    public void setText(CharSequence value) {
        if (value == null || spinner.getAdapter() == null) return;
        String selected = value.toString().trim();
        for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
            Object option = spinner.getAdapter().getItem(i);
            if (option != null && selected.equalsIgnoreCase(option.toString().trim())) {
                spinner.setSelection(i);
                return;
            }
        }
    }
    public CharSequence getText(){return String.valueOf(spinner.getSelectedItem());}
    public static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static int popupWidth(Context c,String[] values){Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(12.5f*c.getResources().getDisplayMetrics().scaledDensity);float longest=0;for(String v:values)longest=Math.max(longest,paint.measureText(v));return Math.min(Math.max(dp(c,120),Math.round(longest)+dp(c,42)),c.getResources().getDisplayMetrics().widthPixels-dp(c,40));}
    public static GradientDrawable surface(Context c,boolean popup){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.WHITE,popup?Color.rgb(243,249,252):Color.rgb(244,249,252)});d.setCornerRadius(dp(c,popup?14:12));d.setStroke(dp(c,1),Color.rgb(204,214,222));if(popup)d.setPadding(dp(c,5),dp(c,5),dp(c,5),dp(c,5));return d;}
    public static TextView popupRow(Context c,String value){TextView t=new TextView(c);t.setText(value);t.setTextSize(12.5f);t.setTextColor(Color.rgb(31,42,49));t.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);t.setSingleLine(true);t.setPadding(dp(c,12),dp(c,2),dp(c,12),dp(c,2));t.setMinHeight(dp(c,36));GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.WHITE,Color.rgb(239,248,246)});d.setCornerRadius(dp(c,10));d.setStroke(dp(c,1),Color.rgb(213,224,228));t.setBackground(d);t.setLayoutParams(new AbsListView.LayoutParams(-1,dp(c,36)));return t;}
}
