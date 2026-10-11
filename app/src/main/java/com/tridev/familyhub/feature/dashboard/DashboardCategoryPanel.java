package com.tridev.familyhub.feature.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.tridev.familyhub.R;

/** Connects the selected tab to its content by leaving that tab's bottom border open. */
public final class DashboardCategoryPanel extends LinearLayout {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public DashboardCategoryPanel(Context context,AttributeSet attrs){super(context,attrs);setWillNotDraw(false);}
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        super.onLayout(changed,left,top,right,bottom);
        MaterialButtonToggleGroup tabs=findViewById(R.id.dashboard_category_tabs);
        com.google.android.material.button.MaterialButton selected=tabs==null?null:tabs.findViewById(tabs.getCheckedButtonId());
        if(selected!=null)selected.setShapeAppearanceModel(selected.getShapeAppearanceModel().toBuilder()
            .setBottomLeftCornerSize(0).setBottomRightCornerSize(0).build());
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        MaterialButtonToggleGroup tabs=findViewById(R.id.dashboard_category_tabs);
        View selected=tabs==null?null:tabs.findViewById(tabs.getCheckedButtonId());
        float dp=getResources().getDisplayMetrics().density;
        int fill=ContextCompat.getColor(getContext(),R.color.fh_surface_container);
        int border=ContextCompat.getColor(getContext(),R.color.fh_outline);
        if(tabs!=null){
            float top=tabs.getBottom()-dp;
            paint.setStyle(Paint.Style.FILL);paint.setColor(fill);
            canvas.drawRoundRect(new RectF(dp,top,getWidth()-dp,getHeight()-dp),12*dp,12*dp,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp);paint.setColor(border);
            canvas.drawRoundRect(new RectF(dp,top,getWidth()-dp,getHeight()-dp),12*dp,12*dp,paint);
        }
        super.dispatchDraw(canvas);
        if(selected!=null){
            // Erase only the selected bottom edge and the matching content border beneath it.
            float left=tabs.getLeft()+selected.getLeft()+dp;
            float right=tabs.getLeft()+selected.getRight()-dp;
            float bottom=tabs.getTop()+selected.getBottom();
            paint.setStyle(Paint.Style.FILL);paint.setColor(fill);
            canvas.drawRect(left,bottom-2*dp,right,bottom+2*dp,paint);
        }
    }
}
