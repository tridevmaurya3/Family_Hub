package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.local.entity.PlannerItem;
import java.util.*;
import java.util.function.Consumer;

/** Read-only compact counters; selecting a tile changes only the current view filter. */
public final class ScheduleSummaryView extends LinearLayout {
    private final TextView[] values = new TextView[4];
    private Consumer<Integer> selection;
    public ScheduleSummaryView(Context c) {this(c,null);}
    public ScheduleSummaryView(Context c, AttributeSet attrs) {
        super(c,attrs);setOrientation(HORIZONTAL);
        String[] labels={"Pending","Completed","Today","Overdue"};
        int[] icons={R.drawable.ic_family_task,R.drawable.ic_family_task,R.drawable.ic_planner,R.drawable.ic_reminder};
        int[] accents={R.color.fh_info,R.color.fh_success,R.color.fh_warning,R.color.fh_error};
        int[] fills={R.color.fh_info_container,R.color.fh_success_container,R.color.fh_warning_container,R.color.fh_error_container};
        for(int i=0;i<4;i++){
            final int index=i;MaterialCardView card=new MaterialCardView(c);card.setRadius(dp(14));card.setCardElevation(0);card.setStrokeWidth(dp(1));card.setStrokeColor(ContextCompat.getColor(c,R.color.fh_outline));card.setCardBackgroundColor(ContextCompat.getColor(c,fills[i]));card.setClickable(true);card.setFocusable(true);card.setContentDescription(labels[i]);
            LayoutParams params=new LayoutParams(0,-2,1);params.setMargins(i==0?0:dp(3),dp(4),i==3?0:dp(3),dp(4));addView(card,params);
            LinearLayout body=new LinearLayout(c);body.setOrientation(VERTICAL);body.setGravity(Gravity.CENTER);body.setPadding(dp(4),dp(6),dp(4),dp(6));body.setMinimumHeight(dp(64));card.addView(body);
            LinearLayout top=new LinearLayout(c);top.setGravity(Gravity.CENTER);ImageView icon=new ImageView(c);icon.setImageResource(icons[i]);icon.setColorFilter(ContextCompat.getColor(c,accents[i]));top.addView(icon,new LayoutParams(dp(14),dp(14)));
            values[i]=new TextView(c);values[i].setText("0");values[i].setTextSize(17);values[i].setTextColor(ContextCompat.getColor(c,accents[i]));values[i].setTypeface(null,1);LayoutParams number=new LayoutParams(-2,-2);number.leftMargin=dp(4);top.addView(values[i],number);body.addView(top);
            TextView label=new TextView(c);label.setText(labels[i]);label.setTextSize(10);label.setGravity(Gravity.CENTER);label.setTextColor(ContextCompat.getColor(c,R.color.fh_on_surface));body.addView(label);
            card.setOnClickListener(v->{if(selection!=null)selection.accept(index);});
        }
    }
    public void setSelection(Consumer<Integer> listener){selection=listener;}
    public void setReminders(List<Reminder> items){int[] counts=new int[4];long now=System.currentTimeMillis();long[] day=today();for(Reminder r:items){boolean done="COMPLETED".equalsIgnoreCase(r.collaborationStatus);counts[done?1:0]++;long at=r.reminderAt;if(!Reminder.REPEAT_ONCE.equals(r.repeatType)){Calendar next=Calendar.getInstance();next.setTimeInMillis(at);int field=Reminder.REPEAT_WEEKLY.equals(r.repeatType)?Calendar.WEEK_OF_YEAR:Reminder.REPEAT_MONTHLY.equals(r.repeatType)?Calendar.MONTH:Reminder.REPEAT_YEARLY.equals(r.repeatType)?Calendar.YEAR:Calendar.DATE;while(next.getTimeInMillis()<now)next.add(field,1);at=next.getTimeInMillis();}if(!done&&at>=day[0]&&at<day[1])counts[2]++;if(!done&&r.isEnabled&&Reminder.REPEAT_ONCE.equals(r.repeatType)&&at<now)counts[3]++;}update(counts);}
    public void setPlanner(List<PlannerItem> items){int[] counts=new int[4];long now=System.currentTimeMillis();long[] day=today();for(PlannerItem p:items){counts[p.isCompleted?1:0]++;if(!p.isCompleted&&p.startAt>=day[0]&&p.startAt<day[1])counts[2]++;if(!p.isCompleted&&p.startAt<now)counts[3]++;}update(counts);}
    private void update(int[] counts){for(int i=0;i<4;i++)values[i].setText(String.valueOf(counts[i]));}
    private long[] today(){Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);long start=c.getTimeInMillis();c.add(Calendar.DATE,1);return new long[]{start,c.getTimeInMillis()};}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
