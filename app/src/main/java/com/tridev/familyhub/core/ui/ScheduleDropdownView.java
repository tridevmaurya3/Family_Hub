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
import com.tridev.familyhub.R;

/** Presentation-only chooser using the Grocery floating field and popup dimensions. */
public final class ScheduleDropdownView extends LinearLayout {
    private Spinner spinner;
    private MaterialAutoCompleteTextView input;
    public ScheduleDropdownView(Context c, boolean floating, String label, String[] values) {
        super(c); setOrientation(VERTICAL);
        if (floating) {
            TextView caption=new TextView(c);caption.setText(label);caption.setTextSize(10);caption.setTextColor(Color.rgb(83,91,102));caption.setPadding(dp(c,2),0,0,dp(c,3));addView(caption,new LayoutParams(-1,-2));
            spinner=new Spinner(c,Spinner.MODE_DROPDOWN);
            spinner.setAdapter(new ArrayAdapter<String>(c,android.R.layout.simple_spinner_item,values){
                @Override public View getView(int p,View old,ViewGroup parent){TextView text=new TextView(c);text.setText(getItem(p)+" ▾");text.setTextColor(Color.rgb(36,36,36));text.setTextSize(11);text.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);text.setIncludeFontPadding(false);text.setMaxLines(2);text.setPadding(dp(c,7),dp(c,2),dp(c,18),dp(c,2));return text;}
                @Override public View getDropDownView(int p,View old,ViewGroup parent){return popupRow(c,getItem(p));}
            });
            spinner.setBackground(surface(c,false));spinner.setElevation(dp(c,1));spinner.setPopupBackgroundDrawable(surface(c,true));spinner.setDropDownVerticalOffset(dp(c,4));spinner.setDropDownWidth(popupWidth(c,values));addView(spinner,new LayoutParams(-1,dp(c,38)));
        } else {
            TextInputLayout field=new TextInputLayout(c);field.setHint(label);input=new MaterialAutoCompleteTextView(c);input.setInputType(0);field.addView(input,new LayoutParams(-1,-2));CompactFormStyle.apply(field);field.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);input.setAdapter(new ArrayAdapter<>(c,R.layout.item_form_dropdown,values));input.setText(values[0],false);input.setDropDownWidth(popupWidth(c,values));addView(field,new LayoutParams(-1,-2));
        }
    }
    public static void styleFilters(View view){
        if(view instanceof com.google.android.material.button.MaterialButton){
            com.google.android.material.button.MaterialButton b=(com.google.android.material.button.MaterialButton)view;
            b.setBackgroundTintList(null);b.setBackground(surface(view.getContext(),false));b.setElevation(dp(view.getContext(),1));b.setTextColor(Color.rgb(36,36,36));b.setTextSize(11);b.setIncludeFontPadding(false);b.setSingleLine(true);b.setPadding(dp(view.getContext(),9),0,dp(view.getContext(),9),0);
            ViewGroup.LayoutParams p=b.getLayoutParams();p.height=dp(view.getContext(),38);b.setLayoutParams(p);
        }else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleFilters(group.getChildAt(i));}
    }
    public CharSequence getText(){return spinner!=null?String.valueOf(spinner.getSelectedItem()):input.getText();}
    public static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static int popupWidth(Context c,String[] values){Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(12.5f*c.getResources().getDisplayMetrics().scaledDensity);float longest=0;for(String v:values)longest=Math.max(longest,paint.measureText(v));return Math.min(Math.max(dp(c,120),Math.round(longest)+dp(c,42)),c.getResources().getDisplayMetrics().widthPixels-dp(c,40));}
    public static GradientDrawable surface(Context c,boolean popup){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.WHITE,popup?Color.rgb(243,249,252):Color.rgb(244,249,252)});d.setCornerRadius(dp(c,popup?14:12));d.setStroke(dp(c,1),Color.rgb(204,214,222));if(popup)d.setPadding(dp(c,5),dp(c,5),dp(c,5),dp(c,5));return d;}
    public static TextView popupRow(Context c,String value){TextView t=new TextView(c);t.setText(value);t.setTextSize(12.5f);t.setTextColor(Color.rgb(31,42,49));t.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);t.setSingleLine(true);t.setPadding(dp(c,12),dp(c,2),dp(c,12),dp(c,2));t.setMinHeight(dp(c,36));GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.WHITE,Color.rgb(239,248,246)});d.setCornerRadius(dp(c,10));d.setStroke(dp(c,1),Color.rgb(213,224,228));t.setBackground(d);t.setLayoutParams(new AbsListView.LayoutParams(-1,dp(c,36)));return t;}
}
