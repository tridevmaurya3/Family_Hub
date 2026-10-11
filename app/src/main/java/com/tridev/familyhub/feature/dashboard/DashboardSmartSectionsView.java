package com.tridev.familyhub.feature.dashboard;

import android.content.*;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.room.InvalidationTracker;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.*;
import com.tridev.familyhub.data.model.DashboardData;
import com.tridev.familyhub.feature.main.MainActivity;
import java.util.*;
import java.util.concurrent.*;

/** Local read-only Home digest; no additional subscriptions, writes or schema changes. */
public final class DashboardSmartSectionsView extends LinearLayout {
    private final FamilyHubDatabase database;
    private static final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private DashboardData dashboard;
    private boolean attached;
    private int generation;
    private final InvalidationTracker.Observer observer=new InvalidationTracker.Observer("family_tasks","planner_items","reminders","notes","grocery_items"){
        @Override public void onInvalidated(java.util.Set<String> tables){handler.post(DashboardSmartSectionsView.this::reload);}
    };
    public DashboardSmartSectionsView(Context c,AttributeSet attrs){super(c,attrs);setOrientation(VERTICAL);database=FamilyHubDatabase.getInstance(c);}
    public void setDashboardData(DashboardData data){dashboard=data;reload();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;database.getInvalidationTracker().addObserver(observer);reload();}
    @Override protected void onDetachedFromWindow(){attached=false;generation++;database.getInvalidationTracker().removeObserver(observer);handler.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}
    private void reload(){if(!attached)return;int request=++generation;executor.execute(()->{
        List<FamilyTask> tasks=database.familyTaskDao().getAll();List<PlannerItem> plans=database.plannerItemDao().getAll();List<Reminder> reminders=database.reminderDao().getAll();List<NoteEntry> notes=database.noteDao().getActive();List<GroceryItem> groceries=database.groceryItemDao().getAll();
        handler.post(()->{if(attached&&request==generation)render(tasks,plans,reminders,notes,groceries);});
    });}
    private static final class Entry {
        String title,detail,route;long time;int icon;
        Entry(String title,String detail,String route,long time,int icon){this.title=title;this.detail=detail;this.route=route;this.time=time;this.icon=icon;}
    }
    private void render(List<FamilyTask> tasks,List<PlannerItem> plans,List<Reminder> reminders,List<NoteEntry> notes,List<GroceryItem> groceries){
        removeAllViews();long now=System.currentTimeMillis();Calendar day=Calendar.getInstance();day.set(Calendar.HOUR_OF_DAY,0);day.set(Calendar.MINUTE,0);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);long start=day.getTimeInMillis();day.add(Calendar.DATE,1);long end=day.getTimeInMillis();int todayTasks=0,todayPlans=0,todayAlerts=0,overdueTasks=0,overduePlans=0,overdueAlerts=0,pendingNotes=0,pinned=0;
        List<Entry> upcoming=new ArrayList<>(),recent=new ArrayList<>();
        for(FamilyTask t:tasks){boolean pending=FamilyTask.STATUS_PENDING.equals(t.status);if(pending&&t.dueAt>=start&&t.dueAt<end)todayTasks++;if(pending&&t.dueAt>0&&t.dueAt<now)overdueTasks++;if(pending&&t.dueAt>=now)upcoming.add(new Entry(t.title,"To-Do",MainActivity.ROUTE_TASKS,t.dueAt,R.drawable.ic_family_task));recent.add(new Entry(t.title,pending?"To-Do · Pending":"To-Do · Completed",MainActivity.ROUTE_TASKS,Math.max(t.updatedAt,t.createdAt),R.drawable.ic_family_task));}
        for(PlannerItem p:plans){if(!p.isCompleted&&p.startAt>=start&&p.startAt<end)todayPlans++;if(!p.isCompleted&&p.startAt<now)overduePlans++;if(!p.isCompleted&&p.startAt>=now)upcoming.add(new Entry(p.title,"Planner · "+p.itemType,MainActivity.ROUTE_PLANNER,p.startAt,R.drawable.ic_planner));recent.add(new Entry(p.title,p.isCompleted?"Planner · Completed":"Planner · Pending",MainActivity.ROUTE_PLANNER,Math.max(p.updatedAt,p.createdAt),R.drawable.ic_planner));}
        for(Reminder r:reminders){boolean pending=!"COMPLETED".equalsIgnoreCase(r.collaborationStatus);long at=nextTime(r,now);if(pending&&r.isEnabled){if(at>=start&&at<end)todayAlerts++;if(Reminder.REPEAT_ONCE.equals(r.repeatType)&&at<now)overdueAlerts++;if(at>=now)upcoming.add(new Entry(r.title,"Reminder",MainActivity.ROUTE_REMINDERS,at,R.drawable.ic_reminder));}recent.add(new Entry(r.title,pending?"Reminder · Pending":"Reminder · Completed",MainActivity.ROUTE_REMINDERS,Math.max(r.updatedAt,r.createdAt),R.drawable.ic_reminder));}
        for(NoteEntry n:notes){if(!"COMPLETED".equalsIgnoreCase(n.collaborationStatus))pendingNotes++;if(n.isPinned)pinned++;recent.add(new Entry(n.title,"Notes · "+(n.isPinned?"Pinned":n.isShared?"Shared":"Private"),MainActivity.ROUTE_NOTES,Math.max(n.updatedAt,n.createdAt),R.drawable.ic_note));}
        for(GroceryItem g:groceries)recent.add(new Entry(g.name,"Grocery · "+(g.isPurchased?"Purchased":"Pending"),MainActivity.ROUTE_GROCERY,Math.max(g.updatedAt,g.createdAt),R.drawable.ic_grocery));
        heading("Today’s overview","Today · "+date(now,false));LinearLayout tiles=new LinearLayout(getContext());addView(tiles,new LayoutParams(-1,-2));tile(tiles,"To-Do",todayTasks,MainActivity.ROUTE_TASKS,R.drawable.ic_family_task);tile(tiles,"Planner",todayPlans,MainActivity.ROUTE_PLANNER,R.drawable.ic_planner);tile(tiles,"Reminders",todayAlerts,MainActivity.ROUTE_REMINDERS,R.drawable.ic_reminder);
        if(overdueTasks>0)card("Overdue To-Do",overdueTasks+" pending tasks need attention",R.drawable.ic_family_task,R.color.fh_warning_container,MainActivity.ROUTE_TASKS);
        if(overduePlans>0)card("Overdue Planner",overduePlans+" pending plans need attention",R.drawable.ic_planner,R.color.fh_warning_container,MainActivity.ROUTE_PLANNER);
        if(overdueAlerts>0)card("Overdue reminders",overdueAlerts+" reminders need attention",R.drawable.ic_reminder,R.color.fh_warning_container,MainActivity.ROUTE_REMINDERS);
        upcoming.sort(Comparator.comparingLong(e->e.time));label("Next scheduled",12);if(upcoming.isEmpty())card("Nothing scheduled next","Add a timed task, plan or reminder",R.drawable.ic_planner,R.color.fh_info_container,MainActivity.ROUTE_PLANNER);else for(int i=0;i<Math.min(3,upcoming.size());i++){Entry e=upcoming.get(i);card(e.title,e.detail+" · "+date(e.time,true),e.icon,R.color.fh_info_container,e.route);}
        if(dashboard!=null)card("Monthly spend · "+money(dashboard.getExpense()),"Income "+money(dashboard.getIncome())+" · Balance "+money(dashboard.getBalance()),R.drawable.ic_wallet,R.color.fh_success_container,MainActivity.ROUTE_FINANCE);
        heading("Family highlights","People, notes and upcoming payments");
        if(dashboard!=null&&dashboard.hasUpcomingBirthday()&&dashboard.getNextBirthdayMember()!=null)card("Next birthday · "+dashboard.getNextBirthdayMember().name,date(dashboard.getNextBirthdayAt(),false),R.drawable.ic_family,R.color.fh_info_container,MainActivity.ROUTE_FAMILY);
        else card("Family",dashboard==null?"Open family details":dashboard.getTotalMembers()+" members · Open family details",R.drawable.ic_family,R.color.fh_info_container,MainActivity.ROUTE_FAMILY);
        card("Notes at a glance",pendingNotes+" pending · "+pinned+" pinned",R.drawable.ic_note,R.color.fh_module_notes_container,MainActivity.ROUTE_NOTES);
        if(dashboard!=null&&dashboard.hasUpcomingBill()&&dashboard.getNextBillReminder()!=null)card(dashboard.getNextBillReminder().title,"Payment reminder · "+date(dashboard.getNextBillTriggerAt(),true),R.drawable.ic_reminder,R.color.fh_warning_container,MainActivity.ROUTE_REMINDERS);
        if(dashboard!=null){int documents=dashboard.getStats().getDocumentsExpiringSoon(),vehicles=dashboard.getStats().getVehiclesDueSoon();if(documents>0)card("Documents",documents+" expiring soon",R.drawable.ic_document,R.color.fh_warning_container,MainActivity.ROUTE_DOCUMENTS);if(vehicles>0)card("Vehicle renewals",vehicles+" due soon",R.drawable.ic_vehicle,R.color.fh_warning_container,MainActivity.ROUTE_VEHICLES);}
        heading("Recent activity","Recently updated records");recent.removeIf(e->e.time<=0);recent.sort((a,b)->Long.compare(b.time,a.time));if(recent.isEmpty())label("Your recent updates will appear here.",12);else for(int i=0;i<Math.min(5,recent.size());i++){Entry e=recent.get(i);card(e.title,e.detail+" · "+date(e.time,true),e.icon,R.color.fh_surface,e.route);}
    }
    static long nextTime(Reminder r,long now){if(Reminder.REPEAT_ONCE.equals(r.repeatType)||r.reminderAt<=0)return r.reminderAt;Calendar c=Calendar.getInstance();c.setTimeInMillis(r.reminderAt);int field=Reminder.REPEAT_WEEKLY.equals(r.repeatType)?Calendar.WEEK_OF_YEAR:Reminder.REPEAT_MONTHLY.equals(r.repeatType)?Calendar.MONTH:Reminder.REPEAT_YEARLY.equals(r.repeatType)?Calendar.YEAR:Calendar.DATE;while(c.getTimeInMillis()<now)c.add(field,1);return c.getTimeInMillis();}
    private void heading(String title,String detail){TextView heading=label(title,17);heading.setTypeface(null,1);heading.setPadding(0,dp(16),0,dp(2));label(detail,11);}
    private TextView label(String value,int size){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(ContextCompat.getColor(getContext(),R.color.fh_on_surface));addView(t,new LayoutParams(-1,-2));return t;}
    private void tile(LinearLayout row,String title,int count,String route,int icon){MaterialCardView card=surface(R.color.fh_info_container);LinearLayout body=new LinearLayout(getContext());body.setOrientation(VERTICAL);body.setGravity(Gravity.CENTER);body.setPadding(dp(6),dp(10),dp(6),dp(10));ImageView symbol=new ImageView(getContext());symbol.setImageResource(icon);symbol.setColorFilter(ContextCompat.getColor(getContext(),R.color.fh_primary));body.addView(symbol,new LayoutParams(dp(20),dp(20)));TextView value=new TextView(getContext());value.setText(count+"\n"+title);value.setGravity(Gravity.CENTER);value.setTextSize(12);value.setTextColor(ContextCompat.getColor(getContext(),R.color.fh_on_surface));body.addView(value);card.addView(body);LayoutParams p=new LayoutParams(0,-2,1);p.setMargins(dp(2),dp(6),dp(2),dp(4));row.addView(card,p);card.setOnClickListener(v->open(route));}
    private void card(String title,String detail,int icon,int fill,String route){MaterialCardView card=surface(fill);LinearLayout body=new LinearLayout(getContext());body.setGravity(Gravity.CENTER_VERTICAL);body.setPadding(dp(12),dp(10),dp(12),dp(10));ImageView symbol=new ImageView(getContext());symbol.setImageResource(icon);symbol.setColorFilter(ContextCompat.getColor(getContext(),R.color.fh_primary));body.addView(symbol,new LayoutParams(dp(24),dp(24)));LinearLayout text=new LinearLayout(getContext());text.setOrientation(VERTICAL);LayoutParams tp=new LayoutParams(0,-2,1);tp.setMarginStart(dp(10));body.addView(text,tp);TextView name=new TextView(getContext());name.setText(title);name.setTextSize(13);name.setTypeface(null,1);name.setTextColor(ContextCompat.getColor(getContext(),R.color.fh_on_surface));text.addView(name);TextView caption=new TextView(getContext());caption.setText(detail);caption.setTextSize(11);caption.setTextColor(ContextCompat.getColor(getContext(),R.color.fh_on_surface_variant));text.addView(caption);card.addView(body);LayoutParams p=new LayoutParams(-1,-2);p.setMargins(0,dp(6),0,0);addView(card,p);card.setOnClickListener(v->open(route));}
    private MaterialCardView surface(int fill){MaterialCardView card=new MaterialCardView(getContext());card.setCardElevation(0);card.setRadius(dp(16));card.setStrokeWidth(dp(1));card.setStrokeColor(ContextCompat.getColor(getContext(),R.color.fh_outline));card.setCardBackgroundColor(ContextCompat.getColor(getContext(),fill));card.setClickable(true);card.setFocusable(true);return card;}
    private void open(String route){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE,route).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    private String date(long at,boolean time){return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(at))+(time?" · "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(at)):"");}
    private String money(double value){return java.text.NumberFormat.getCurrencyInstance(new Locale("en","IN")).format(value);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
