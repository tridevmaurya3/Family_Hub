package com.tridev.familyhub.feature.planner;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.planner.PlannerScheduler;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.PlannerItem;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.PlannerRepository;
import com.tridev.familyhub.databinding.DialogPlannerBinding;
import com.tridev.familyhub.databinding.FragmentPlannerBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Offline-first Office-style family calendar and task planner. */
public final class PlannerWorkspace {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 4102;
    private static final String[] TYPES = {
            PlannerItem.TYPE_EVENT, PlannerItem.TYPE_TASK
    };
    private static final String[] PRIORITIES = {
            PlannerItem.PRIORITY_NORMAL,
            PlannerItem.PRIORITY_HIGH,
            PlannerItem.PRIORITY_URGENT
    };
    private static final String[] REPEATS = {
            PlannerItem.REPEAT_NONE,
            PlannerItem.REPEAT_DAILY,
            PlannerItem.REPEAT_WEEKLY,
            PlannerItem.REPEAT_MONTHLY,
            PlannerItem.REPEAT_YEARLY
    };

    private FragmentPlannerBinding binding;
    private PlannerRepository repository;
    private FamilyMemberRepository memberRepository;
    private PlannerAdapter adapter;

    private final android.content.Context context;
    private final boolean floating;
    private final java.util.List<android.app.Dialog> dialogs = new ArrayList<>();
    private final java.util.List<android.widget.PopupWindow> popups = new ArrayList<>();
    private final List<PlannerItem> loaded = new ArrayList<>();
    private PlannerInlineVoice voice;
    private PlannerQuickAddView quick;
    private String status = "PENDING", period = "ALL", type = "ALL", priority = "ALL", sort = "DUE";
    public PlannerWorkspace(android.content.Context context, boolean floating, Runnable menu) {
        this.context=context;this.floating=floating;
        binding=FragmentPlannerBinding.inflate(LayoutInflater.from(context));
        binding.plannerOverview.setNavigationAction(R.drawable.ic_menu_hamburger,R.string.feature_menu_title,v->menu.run());
        if(floating){binding.plannerOverview.setVisibility(View.GONE);binding.plannerSummary.setVisibility(View.GONE);}
        initialize();
        observedDatabase=com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(context);observedDatabase.getInvalidationTracker().addObserver(localObserver);
        com.tridev.familyhub.core.ui.ScheduleDropdownView.styleFilters(binding.plannerFilterScroll);
    }
    private String syncState="● Syncing…";
    private java.util.function.Consumer<String> syncListener;
    public void setSyncStatusListener(java.util.function.Consumer<String> listener){syncListener=listener;listener.accept(syncState);}
    private void syncStatus(boolean live,boolean connecting){if(binding==null)return;syncState=live?"● Live · Family sync":connecting?"● Syncing…":"● Offline · Local data";binding.plannerOverview.setDetail(syncState);if(syncListener!=null)syncListener.accept(syncState);}
    private com.tridev.familyhub.data.local.FamilyHubDatabase observedDatabase;
    private final android.os.Handler observerHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final androidx.room.InvalidationTracker.Observer localObserver=new androidx.room.InvalidationTracker.Observer("planner_items"){
        @Override public void onInvalidated(@NonNull java.util.Set<String> tables){observerHandler.post(()->{if(binding!=null)reload();});}
    };
    public View getView(){return binding.getRoot();}
    private android.content.Context requireContext(){return context;}
    private android.content.res.Resources getResources(){return context.getResources();}
    private String getString(int id,Object...args){return context.getString(id,args);}
    private LayoutInflater getLayoutInflater(){return LayoutInflater.from(context);}
    private boolean isAdded(){return binding!=null;}
    private void initialize(){
        repository = new PlannerRepository(requireContext());
        memberRepository = new FamilyMemberRepository(requireContext());
        adapter = new PlannerAdapter(new PlannerAdapter.ActionListener() {
            @Override
            public void onCompletedChanged(
                    @NonNull PlannerItem item,
                    boolean completed
            ) {
                repository.setCompleted(item, completed, () -> {
                    if (completed) {
                        PlannerScheduler.cancel(requireContext(), item.id);
                    } else {
                        PlannerScheduler.schedule(requireContext(), item);
                    }
                    reload();
                });
            }

            @Override
            public void onEdit(@NonNull PlannerItem item) {
                prepareEditor(item);
            }

            @Override
            public void onDelete(@NonNull PlannerItem item) {
                confirmDelete(item);
            }
        });
        binding.plannerRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.plannerRecyclerView.setAdapter(adapter);
        ((com.tridev.familyhub.core.ui.ScrollableWorkspaceLayout) binding.getRoot())
                .makeWorkspaceScrollable(1, binding.plannerListHost,
                        binding.plannerRecyclerView, binding.plannerEmptyState);
        if (floating) binding.plannerSearchLayout.setVisibility(View.GONE);
        repository.startRealtimeSync(new PlannerRepository.RealtimeCallback() {
            @Override public void onSyncStateChanged(boolean live,boolean connecting){syncStatus(live,connecting);}
            @Override public void onChanged(@NonNull PlannerItem item) {
                if (binding == null || !isAdded()) return;
                if (item.isReminderEnabled && !item.isCompleted) {
                    PlannerScheduler.schedule(requireContext(), item);
                } else {
                    PlannerScheduler.cancel(requireContext(), item.id);
                }
                reload();
            }

            @Override public void onRemoved(long localId) {
                if (binding == null || !isAdded()) return;
                PlannerScheduler.cancel(requireContext(), localId);
                reload();
            }
        });
        binding.emptyAddPlannerButton.setOnClickListener(
                viewClicked -> prepareEditor(null)
        );
        binding.plannerSearchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after
                    ) { }
                    public void onTextChanged(
                            CharSequence s, int start, int before, int count
                    ) {
                        reload();
                    }
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) { }
                }
        );
        voice=new PlannerInlineVoice(context);
        com.tridev.familyhub.core.ui.CompactFormStyle.applyInputs(binding.plannerSearchLayout);
        binding.plannerSearchLayout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM);
        binding.plannerSearchLayout.setEndIconDrawable(R.drawable.ic_mic);voice.bind(binding.plannerSearchLayout);
        quick=new PlannerQuickAddView(context,floating,this::saveQuick,()->prepareEditor(quick.draft()));
        binding.plannerQuickHost.addView(quick);voice.bind(quick);
        binding.plannerStatusFilter.setOnClickListener(v->choices(v,new String[]{"PENDING","COMPLETED","ALL"},status,value->{status=value;applyFilters();}));
        binding.plannerPeriodFilter.setOnClickListener(v->choices(v,new String[]{"ALL","TODAY","TOMORROW","WEEK","OVERDUE"},period,value->{period=value;applyFilters();}));
        binding.plannerTypeFilter.setOnClickListener(v->choices(v,new String[]{"ALL","EVENT","TASK"},type,value->{type=value;applyFilters();}));
        binding.plannerPriorityFilter.setOnClickListener(v->choices(v,new String[]{"ALL","NORMAL","HIGH","URGENT"},priority,value->{priority=value;applyFilters();}));
        binding.plannerSortFilter.setOnClickListener(v->choices(v,new String[]{"DUE","LATEST","PRIORITY","TITLE"},sort,value->{sort=value;applyFilters();}));
        binding.plannerSummary.setSelection(index->{status=index==1?"COMPLETED":"PENDING";period=index==2?"TODAY":index==3?"OVERDUE":"ALL";applyFilters();});
        reload();
    }

    public void add() {
        prepareEditor(null);
    }

    private void prepareEditor(@Nullable PlannerItem existing) {
        memberRepository.loadMembers("", members -> {
            if (binding != null) {
                showEditor(members, existing);
            }
        });
    }

    private void showEditor(
            @NonNull List<FamilyMember> members,
            @Nullable PlannerItem existing
    ) {
        DialogPlannerBinding form =
                DialogPlannerBinding.inflate(getLayoutInflater());
        com.tridev.familyhub.core.ui.CompactFormStyle.apply(form.getRoot());
        PlannerItem item = existing == null ? new PlannerItem() : existing;
        final boolean newItem = existing == null || existing.id == 0;
        final boolean quickDraft = existing != null && existing.id == 0;
        String[] typeLabels =
                getResources().getStringArray(R.array.planner_type_labels);
        String[] priorityLabels =
                getResources().getStringArray(R.array.planner_priority_labels);
        String[] repeatLabels =
                getResources().getStringArray(R.array.planner_repeat_labels);
        List<String> memberNames = new ArrayList<>();
        memberNames.add(getString(R.string.planner_whole_family));
        for (FamilyMember member : members) {
            memberNames.add(member.name);
        }
        form.plannerTypeInput.setAdapter(dropdown(typeLabels));
        form.plannerPriorityInput.setAdapter(dropdown(priorityLabels));
        form.plannerRepeatInput.setAdapter(dropdown(repeatLabels));
        form.plannerMemberInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                memberNames
        ));
        String[] collaborationLabels = getResources().getStringArray(R.array.collaboration_status_labels);
        form.plannerCollaborationStatusInput.setAdapter(dropdown(collaborationLabels));

        long[] startAt = {
                item.startAt > 0L
                        ? item.startAt
                        : System.currentTimeMillis() + 3600000L
        };
        long[] endAt = {item.endAt};
        if (existing == null) {
            form.plannerTypeInput.setText(typeLabels[0], false);
            form.plannerPriorityInput.setText(priorityLabels[0], false);
            form.plannerRepeatInput.setText(repeatLabels[0], false);
            form.plannerMemberInput.setText(memberNames.get(0), false);
            form.plannerCollaborationStatusInput.setText(collaborationLabels[0], false);
        } else {
            form.plannerDialogTitle.setText(newItem ? R.string.planner_add : R.string.planner_edit);
            form.plannerTitleInput.setText(item.title);
            form.plannerNotesInput.setText(item.notes);
            form.plannerLocationInput.setText(item.location);
            form.plannerTypeInput.setText(
                    typeLabels[indexOf(TYPES, item.itemType)], false
            );
            form.plannerPriorityInput.setText(
                    priorityLabels[indexOf(PRIORITIES, item.priority)], false
            );
            form.plannerRepeatInput.setText(
                    repeatLabels[indexOf(REPEATS, item.repeatType)], false
            );
            form.plannerAllDaySwitch.setChecked(item.isAllDay);
            form.plannerReminderSwitch.setChecked(item.isReminderEnabled);
            form.plannerReminderMinutesInput.setText(
                    String.valueOf(item.reminderMinutesBefore)
            );
            form.plannerSharedSwitch.setChecked(item.isShared);
            form.plannerCollaborationStatusInput.setText(item.collaborationStatus, false);
            if (item.assignedMemberId != null) {
                for (int i = 0; i < members.size(); i++) {
                    if (members.get(i).id == item.assignedMemberId) {
                        form.plannerMemberInput.setText(
                                members.get(i).name, false
                        );
                        break;
                    }
                }
            } else {
                form.plannerMemberInput.setText(memberNames.get(0), false);
            }
        }
        updateDateText(form, startAt[0], endAt[0]);
        form.plannerStartInput.setOnClickListener(
                view -> pickDateTime(startAt[0], selected -> {
                    startAt[0] = selected;
                    updateDateText(form, startAt[0], endAt[0]);
                })
        );
        form.plannerEndInput.setOnClickListener(
                view -> pickDateTime(
                        endAt[0] > 0 ? endAt[0] : startAt[0] + 3600000L,
                        selected -> {
                            endAt[0] = selected;
                            updateDateText(form, startAt[0], endAt[0]);
                        }
                )
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot()).create();
        form.cancelPlannerButton.setOnClickListener(view -> dialog.dismiss());
        form.savePlannerButton.setOnClickListener(view -> {
            String title = textOf(form.plannerTitleInput);
            if (title.isEmpty()) {
                form.plannerTitleLayout.setError(
                        getString(R.string.planner_title_required)
                );
                return;
            }
            if (endAt[0] > 0L && endAt[0] < startAt[0]) {
                form.plannerEndLayout.setError(
                        getString(R.string.planner_end_invalid)
                );
                return;
            }
            form.plannerTitleLayout.setError(null);
            form.plannerEndLayout.setError(null);
            item.title = title;
            item.notes = textOf(form.plannerNotesInput);
            item.location = textOf(form.plannerLocationInput);
            item.itemType = TYPES[indexOf(
                    typeLabels, textOf(form.plannerTypeInput)
            )];
            item.priority = PRIORITIES[indexOf(
                    priorityLabels, textOf(form.plannerPriorityInput)
            )];
            item.repeatType = REPEATS[indexOf(
                    repeatLabels, textOf(form.plannerRepeatInput)
            )];
            item.startAt = startAt[0];
            item.endAt = endAt[0];
            item.isAllDay = form.plannerAllDaySwitch.isChecked();
            item.isReminderEnabled =
                    form.plannerReminderSwitch.isChecked();
            item.reminderMinutesBefore = parseInt(
                    textOf(form.plannerReminderMinutesInput)
            );
            int memberIndex = indexOf(
                    memberNames.toArray(new String[0]),
                    textOf(form.plannerMemberInput)
            );
            item.assignedMemberId = memberIndex == 0
                    ? null : members.get(memberIndex - 1).id;
            item.assignedMemberName = memberIndex == 0
                    ? getString(R.string.planner_whole_family) : members.get(memberIndex - 1).name;
            item.isShared = form.plannerSharedSwitch.isChecked();
            item.collaborationStatus = textOf(form.plannerCollaborationStatusInput);
            form.savePlannerButton.setEnabled(false);
            repository.save(item, () -> {
                    if (item.isReminderEnabled) {
                        PlannerScheduler.schedule(requireContext(), item);
                    } else {
                        PlannerScheduler.cancel(requireContext(), item.id);
                    }
                    if (binding != null) {
                    if (item.isReminderEnabled) ensureNotificationPermission();
                    if (quickDraft) quick.saved();
                    dialog.dismiss();
                    reload();
                    Snackbar.make(
                            binding.getRoot(),
                            newItem
                                    ? R.string.planner_added
                                    : R.string.planner_updated,
                            Snackbar.LENGTH_SHORT
                    ).show();
                }
            });
        });
        voice.bind(form.getRoot());
        showDialog(dialog);
    }

    private ArrayAdapter<String> dropdown(@NonNull String[] labels) {
        return new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                labels
        );
    }

    private interface DateTimeCallback {
        void onSelected(long value);
    }

    private void pickDateTime(long initial, @NonNull DateTimeCallback callback) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(initial);
        showDialog(new DatePickerDialog(
                requireContext(),
                (picker, year, month, day) -> {
                    calendar.set(year, month, day);
                    showDialog(new TimePickerDialog(
                            requireContext(),
                            (timePicker, hour, minute) -> {
                                calendar.set(Calendar.HOUR_OF_DAY, hour);
                                calendar.set(Calendar.MINUTE, minute);
                                calendar.set(Calendar.SECOND, 0);
                                callback.onSelected(calendar.getTimeInMillis());
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            false
                    ));
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ));
    }

    private void updateDateText(
            @NonNull DialogPlannerBinding form,
            long start,
            long end
    ) {
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT
        );
        form.plannerStartInput.setText(format.format(start));
        form.plannerEndInput.setText(
                end > 0L ? format.format(end) : ""
        );
    }

    private void confirmDelete(@NonNull PlannerItem item) {
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.planner_delete_title)
                .setMessage(getString(
                        R.string.planner_delete_message, item.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    PlannerScheduler.cancel(requireContext(), item.id);
                    repository.delete(item, this::reload);
                }).create());
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(new android.content.Intent(context,
                    com.tridev.familyhub.feature.notes.overlay.NotesNotificationPermissionActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public void reload() {
        if (binding == null) {
            return;
        }
        repository.loadAll(textOf(binding.plannerSearchInput), items -> {
            if (binding == null) {
                return;
            }
            loaded.clear();loaded.addAll(items);binding.plannerSummary.setPlanner(items);applyFilters();
        });
    }

    private int indexOf(@NonNull String[] values, @NonNull String selected) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(selected)) {
                return i;
            }
        }
        return 0;
    }

    private int parseInt(@NonNull String value) {
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? "" : input.getText().toString().trim();
    }

    private void choices(View anchor,String[] values,String current,java.util.function.Consumer<String> selected){PlannerPanels.choices(context,anchor,floating,values,current,selected,popups);}
    private void applyFilters(){
        if(binding==null)return;
        Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);long today=c.getTimeInMillis();c.add(Calendar.DATE,1);long tomorrow=c.getTimeInMillis();c.add(Calendar.DATE,1);long after=c.getTimeInMillis();c.add(Calendar.DATE,5);long week=c.getTimeInMillis();long now=System.currentTimeMillis();
        List<PlannerItem> filtered=new ArrayList<>();
        for(PlannerItem item:loaded){
            if("PENDING".equals(status)&&item.isCompleted||"COMPLETED".equals(status)&&!item.isCompleted)continue;
            if(!"ALL".equals(type)&&!type.equals(item.itemType))continue;
            if(!"ALL".equals(priority)&&!priority.equals(item.priority))continue;
            if("TODAY".equals(period)&&(item.startAt<today||item.startAt>=tomorrow))continue;
            if("TOMORROW".equals(period)&&(item.startAt<tomorrow||item.startAt>=after))continue;
            if("WEEK".equals(period)&&(item.startAt<today||item.startAt>=week))continue;
            if("OVERDUE".equals(period)&&(item.isCompleted||item.startAt>=now))continue;
            filtered.add(item);
        }
        filtered.sort((a,b)->{if("TITLE".equals(sort))return a.title.compareToIgnoreCase(b.title);if("LATEST".equals(sort))return Long.compare(b.updatedAt,a.updatedAt);if("PRIORITY".equals(sort)){int result=Integer.compare(rank(b.priority),rank(a.priority));if(result!=0)return result;}return Long.compare(a.startAt,b.startAt);});
        adapter.submitList(filtered);binding.plannerResults.setText(getString(R.string.planner_smart_results,filtered.size(),loaded.size()));
        binding.plannerEmptyState.setVisibility(filtered.isEmpty()?View.VISIBLE:View.GONE);
        binding.plannerStatusFilter.setText(status+" ▾");binding.plannerPeriodFilter.setText(("ALL".equals(period)?"Dates":period)+" ▾");binding.plannerTypeFilter.setText(("ALL".equals(type)?"Type":type)+" ▾");binding.plannerPriorityFilter.setText(("ALL".equals(priority)?"Priority":priority)+" ▾");binding.plannerSortFilter.setText(sort+" ▾");
    }
    private int rank(String priority){return "URGENT".equals(priority)?2:"HIGH".equals(priority)?1:0;}
    private void showDialog(android.app.Dialog dialog){if(binding==null)return;if(floating&&dialog.getWindow()!=null)dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialogs.add(dialog);dialog.setOnDismissListener(d->{dialogs.remove(dialog);voice.stop();});dialog.show();}
    private void saveQuick(PlannerItem item){repository.save(item,()->{if(item.isReminderEnabled){PlannerScheduler.schedule(context,item);if(binding!=null)ensureNotificationPermission();}if(binding!=null){quick.saved();reload();Snackbar.make(binding.getRoot(),R.string.planner_added,Snackbar.LENGTH_SHORT).show();}});}
    public void toggleSearch() {
        if (binding == null) return;
        boolean show = binding.plannerSearchLayout.getVisibility() != View.VISIBLE;
        binding.plannerSearchLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.plannerRecyclerView.stopScroll();
            ((LinearLayoutManager) binding.plannerRecyclerView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
    }
    public void setQuickControlsCollapsed(boolean collapsed) { if (binding != null && quick != null) quick.setDetailsCollapsed(collapsed); }
    public void dispose(){observedDatabase.getInvalidationTracker().removeObserver(localObserver);observerHandler.removeCallbacksAndMessages(null);repository.stopRealtimeSync();voice.stop();quick.dispose();for(android.app.Dialog dialog:new ArrayList<>(dialogs))dialog.dismiss();for(android.widget.PopupWindow popup:new ArrayList<>(popups))popup.dismiss();binding.plannerRecyclerView.setAdapter(null);binding=null;}
}
