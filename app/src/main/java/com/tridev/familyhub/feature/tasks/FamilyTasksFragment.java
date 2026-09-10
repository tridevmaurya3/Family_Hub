package com.tridev.familyhub.feature.tasks;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import com.tridev.familyhub.databinding.DialogFamilyTaskBinding;
import com.tridev.familyhub.databinding.FragmentFamilyTasksBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.tasks.overlay.FamilyTaskOverlayService;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** Grocery-inspired, realtime family To-Do surface. */
public final class FamilyTasksFragment extends Fragment implements AddActionHost {
    private FragmentFamilyTasksBinding binding;
    private FamilyTaskRepository repository;
    private FamilyMemberRepository memberRepository;
    private FamilyTaskAdapter adapter;
    private int activeFilter = R.id.task_filter_today;
    private int activeStatus = R.id.task_status_pending;
    private int activeAssignment = R.id.task_assignment_everyone;
    private boolean overdueOnly;
    private long customDateStart;
    private long customDateEnd;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFamilyTasksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.taskOverview.setNavigationAction(R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title, v -> ((MainActivity) requireActivity()).showFeatureMenu());
        repository = new FamilyTaskRepository(requireContext());
        memberRepository = new FamilyMemberRepository(requireContext());
        adapter = new FamilyTaskAdapter(new FamilyTaskAdapter.Listener() {
            @Override public void onCompletedChanged(@NonNull FamilyTask task, boolean completed) {
                repository.setCompleted(task, completed, () -> {
                    if (completed) FamilyTaskScheduler.cancel(requireContext(), task.id);
                    else FamilyTaskScheduler.schedule(requireContext(), task);
                    scheduleAllPendingTasks();
                    reload();
                });
            }
            @Override public void onEdit(@NonNull FamilyTask task) { prepareEditor(task); }
            @Override public void onDelete(@NonNull FamilyTask task) { confirmDelete(task); }
        });
        binding.taskRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.taskRecyclerView.setAdapter(adapter);
        binding.taskQuickAddButton.setOnClickListener(v -> quickAdd());
        binding.taskFloatingToggle.setOnClickListener(v -> toggleFloatingStrip());
        binding.taskDueCalendarButton.setOnClickListener(v -> pickCalendarDay());
        binding.taskQuickAddInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { quickAdd(); return true; }
            return false;
        });
        binding.taskFilterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) activeFilter = checkedIds.get(0);
            overdueOnly = false;
            customDateStart = 0L;
            customDateEnd = 0L;
            binding.taskDueCalendarButton.setText(R.string.family_tasks_due_calendar);
            reload();
        });
        binding.taskStatusGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) activeStatus = checkedIds.get(0);
            overdueOnly = false;
            reload();
        });
        binding.taskAssignmentGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) activeAssignment = checkedIds.get(0);
            reload();
        });
        binding.taskPendingCard.setOnClickListener(v -> selectSummary(false,
                R.id.task_filter_all, R.id.task_status_pending));
        binding.taskTodayCard.setOnClickListener(v -> selectSummary(false,
                R.id.task_filter_today, R.id.task_status_pending));
        binding.taskOverdueCard.setOnClickListener(v -> selectSummary(true,
                R.id.task_filter_all, R.id.task_status_pending));
        binding.taskCompletedCard.setOnClickListener(v -> selectSummary(false,
                R.id.task_filter_all, R.id.task_status_completed));
        binding.taskSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { reload(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull FamilyTask task) {
                if (binding != null) {
                    FamilyTaskScheduler.schedule(requireContext(), task);
                    reload();
                }
            }
            @Override public void onRemoved(long localId) { if (binding != null) reload(); }
        });
        reload();
        updateFloatingButton();
    }

    @Override public void onResume() {
        super.onResume();
        if (binding == null) return;
        boolean requested = requireContext().getSharedPreferences(
                FamilyTaskOverlayService.PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(FamilyTaskOverlayService.KEY_REQUESTED, false);
        if (requested && Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(FamilyTaskOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean(FamilyTaskOverlayService.KEY_REQUESTED, false).apply();
            startFloatingStrip();
        }
        updateFloatingButton();
    }

    private void toggleFloatingStrip() {
        boolean enabled = requireContext().getSharedPreferences(
                FamilyTaskOverlayService.PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(FamilyTaskOverlayService.KEY_ENABLED, false);
        if (enabled) {
            requireContext().startService(new Intent(requireContext(),
                    FamilyTaskOverlayService.class).setAction(FamilyTaskOverlayService.ACTION_STOP));
            binding.taskFloatingToggle.postDelayed(this::updateFloatingButton, 180);
            return;
        }
        if (!Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(FamilyTaskOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean(FamilyTaskOverlayService.KEY_REQUESTED, true).apply();
            android.widget.Toast.makeText(requireContext(),
                    R.string.family_tasks_overlay_permission, android.widget.Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
            return;
        }
        startFloatingStrip();
    }

    private void startFloatingStrip() {
        ContextCompat.startForegroundService(requireContext(), new Intent(requireContext(),
                FamilyTaskOverlayService.class).setAction(FamilyTaskOverlayService.ACTION_SHOW));
        binding.taskFloatingToggle.postDelayed(this::updateFloatingButton, 180);
    }

    private void updateFloatingButton() {
        if (binding == null) return;
        boolean enabled = requireContext().getSharedPreferences(
                FamilyTaskOverlayService.PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(FamilyTaskOverlayService.KEY_ENABLED, false);
        binding.taskFloatingToggle.setText(enabled
                ? R.string.family_tasks_floating_hide : R.string.family_tasks_floating_show);
    }

    @Override public void onAddRequested() { prepareEditor(null); }

    private void quickAdd() {
        String title = text(binding.taskQuickAddInput);
        if (title.isEmpty()) return;
        FamilyTask task = new FamilyTask();
        task.title = title;
        task.dueAt = defaultDueTime();
        repository.save(task, () -> { if (binding != null) { binding.taskQuickAddInput.setText(""); reload(); } });
    }

    private void reload() {
        if (binding == null) return;
        repository.loadAll("", this::renderSummary);
        repository.loadAll(text(binding.taskSearchInput), tasks -> {
            if (binding == null) return;
            List<FamilyTask> visible = new ArrayList<>();
            int pending = 0;
            long[] range = activeRange();
            for (FamilyTask task : tasks) {
                if (!FamilyTask.STATUS_COMPLETED.equals(task.status)) pending++;
                boolean completed = FamilyTask.STATUS_COMPLETED.equals(task.status);
                boolean statusMatches = activeStatus == R.id.task_status_all
                        || (activeStatus == R.id.task_status_completed && completed)
                        || (activeStatus == R.id.task_status_pending && !completed);
                boolean dateMatches = activeFilter == R.id.task_filter_all
                        || (task.dueAt >= range[0] && task.dueAt < range[1]);
                if (customDateStart > 0L) {
                    dateMatches = task.dueAt >= customDateStart
                            && task.dueAt < customDateEnd;
                }
                boolean assignmentMatches = matchesAssignment(task);
                if (overdueOnly) dateMatches = !completed
                        && task.dueAt < startOfToday();
                if (statusMatches && dateMatches && assignmentMatches) visible.add(task);
            }
            adapter.submitList(visible);
            binding.taskResultSummary.setText(getString(R.string.family_tasks_result_count, visible.size(), pending));
            binding.taskEmptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
            binding.taskRecyclerView.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void pickCalendarDay() {
        Calendar selected = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
            selected.set(year, month, day, 0, 0, 0);
            selected.set(Calendar.MILLISECOND, 0);
            long selectedDateStart = selected.getTimeInMillis();
            selected.add(Calendar.DAY_OF_YEAR, 1);
            long selectedDateEnd = selected.getTimeInMillis();
            overdueOnly = false;
            activeFilter = R.id.task_filter_all;
            binding.taskFilterGroup.check(R.id.task_filter_all);
            customDateStart = selectedDateStart;
            customDateEnd = selectedDateEnd;
            binding.taskDueCalendarButton.setText(getString(
                    R.string.family_tasks_calendar_date,
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(new Date(customDateStart))));
            reload();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean matchesAssignment(@NonNull FamilyTask task) {
        if (activeAssignment == R.id.task_assignment_everyone) return true;
        if (activeAssignment == R.id.task_assignment_family) {
            return task.assignedMemberId.isEmpty()
                    && task.assignedMemberName.isEmpty();
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return false;
        if (user.getUid().equals(task.assignedMemberId)) return true;
        String displayName = user.getDisplayName();
        return displayName != null && !displayName.trim().isEmpty()
                && displayName.trim().equalsIgnoreCase(task.assignedMemberName);
    }

    private void scheduleAllPendingTasks() {
        repository.loadAll("", tasks -> {
            if (!isAdded()) return;
            for (FamilyTask pending : tasks) {
                if (FamilyTask.STATUS_PENDING.equals(pending.status)
                        && pending.reminderEnabled) {
                    FamilyTaskScheduler.schedule(requireContext(), pending);
                }
            }
        });
    }

    private void renderSummary(@NonNull List<FamilyTask> tasks) {
        if (binding == null) return;
        int pending = 0, today = 0, overdue = 0, completed = 0;
        long todayStart = startOfToday();
        long tomorrowStart = todayStart + 24L * 60L * 60L * 1000L;
        for (FamilyTask task : tasks) {
            if (FamilyTask.STATUS_COMPLETED.equals(task.status)) {
                completed++;
            } else {
                pending++;
                if (task.dueAt < todayStart) overdue++;
                else if (task.dueAt < tomorrowStart) today++;
            }
        }
        binding.taskPendingValue.setText(String.valueOf(pending));
        binding.taskTodayValue.setText(String.valueOf(today));
        binding.taskOverdueValue.setText(String.valueOf(overdue));
        binding.taskCompletedValue.setText(String.valueOf(completed));
    }

    private void selectSummary(boolean overdue, int dateFilter, int statusFilter) {
        overdueOnly = false;
        activeFilter = dateFilter;
        activeStatus = statusFilter;
        binding.taskFilterGroup.check(dateFilter);
        binding.taskStatusGroup.check(statusFilter);
        overdueOnly = overdue;
        reload();
    }

    private long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void prepareEditor(@Nullable FamilyTask existing) {
        memberRepository.loadMembers("", members -> { if (binding != null) showEditor(existing, members); });
    }

    private void showEditor(@Nullable FamilyTask existing, @NonNull List<FamilyMember> members) {
        DialogFamilyTaskBinding form = DialogFamilyTaskBinding.inflate(getLayoutInflater());
        FamilyTask task = existing == null ? new FamilyTask() : existing;
        long[] dueAt = {task.dueAt > 0 ? task.dueAt : defaultDueTime()};
        String[] priorities = {getString(R.string.task_priority_normal), getString(R.string.task_priority_high), getString(R.string.task_priority_urgent)};
        String[] priorityValues = {FamilyTask.PRIORITY_NORMAL, FamilyTask.PRIORITY_HIGH, FamilyTask.PRIORITY_URGENT};
        String[] repeats = {getString(R.string.task_repeat_none), getString(R.string.task_repeat_daily), getString(R.string.task_repeat_weekly), getString(R.string.task_repeat_monthly)};
        String[] repeatValues = {FamilyTask.REPEAT_NONE, FamilyTask.REPEAT_DAILY, FamilyTask.REPEAT_WEEKLY, FamilyTask.REPEAT_MONTHLY};
        String[] reminderLabels = {getString(R.string.task_reminder_at_time), getString(R.string.task_reminder_5_minutes), getString(R.string.task_reminder_15_minutes), getString(R.string.task_reminder_30_minutes), getString(R.string.task_reminder_1_hour), getString(R.string.task_reminder_1_day)};
        int[] reminderValues = {0, 5, 15, 30, 60, 1440};
        List<String> memberNames = new ArrayList<>();
        memberNames.add(getString(R.string.family_tasks_whole_family));
        for (FamilyMember member : members) memberNames.add(member.name);
        form.taskPriorityInput.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, priorities));
        form.taskRepeatInput.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, repeats));
        form.taskMemberInput.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, memberNames));
        form.taskReminderLeadInput.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, reminderLabels));
        form.taskDialogTitle.setText(existing == null ? R.string.family_tasks_add : R.string.family_tasks_edit);
        form.saveTaskButton.setText(existing == null ? R.string.family_tasks_save : R.string.family_tasks_update);
        form.taskTitleInput.setText(task.title);
        form.taskNotesInput.setText(task.notes);
        form.taskPriorityInput.setText(priorities[indexOf(priorityValues, task.priority)], false);
        form.taskRepeatInput.setText(repeats[indexOf(repeatValues, task.repeatType)], false);
        form.taskMemberInput.setText(task.assignedMemberName.isEmpty() ? memberNames.get(0) : task.assignedMemberName, false);
        form.taskReminderSwitch.setChecked(task.reminderEnabled || existing == null);
        int reminderIndex = indexOf(reminderValues, task.reminderMinutesBefore);
        form.taskReminderLeadInput.setText(reminderLabels[reminderIndex], false);
        form.taskReminderLeadLayout.setVisibility(form.taskReminderSwitch.isChecked() ? View.VISIBLE : View.GONE);
        form.taskReminderSwitch.setOnCheckedChangeListener((button, checked) ->
                form.taskReminderLeadLayout.setVisibility(checked ? View.VISIBLE : View.GONE));
        updateDueText(form, dueAt[0]);
        form.taskDueInput.setOnClickListener(v -> pickDateTime(dueAt[0], selected -> { dueAt[0] = selected; updateDueText(form, selected); }));
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setView(form.getRoot()).create();
        form.cancelTaskButton.setOnClickListener(v -> dialog.dismiss());
        form.saveTaskButton.setOnClickListener(v -> {
            String title = text(form.taskTitleInput);
            if (title.isEmpty()) { form.taskTitleLayout.setError(getString(R.string.family_tasks_required)); return; }
            task.title = title; task.notes = text(form.taskNotesInput); task.dueAt = dueAt[0];
            task.priority = priorityValues[Math.max(0, indexOf(priorities, text(form.taskPriorityInput)))];
            task.repeatType = repeatValues[Math.max(0, indexOf(repeats, text(form.taskRepeatInput)))];
            String memberName = text(form.taskMemberInput);
            task.assignedMemberName = memberName.equals(memberNames.get(0)) ? "" : memberName;
            task.assignedMemberId = "";
            boolean validMember = task.assignedMemberName.isEmpty();
            for (FamilyMember member : members) if (member.name.equals(task.assignedMemberName)) {
                task.assignedMemberId = member.cloudUid.isEmpty() ? member.cloudProfileId : member.cloudUid;
                validMember = true;
                break;
            }
            if (!validMember) {
                task.assignedMemberName = "";
                task.assignedMemberId = "";
            }
            task.reminderEnabled = form.taskReminderSwitch.isChecked();
            task.reminderMinutesBefore = reminderValues[indexOf(
                    reminderLabels, text(form.taskReminderLeadInput))];
            repository.save(task, () -> {
                FamilyTaskScheduler.schedule(requireContext(), task);
                dialog.dismiss(); reload();
            });
        });
        dialog.show();
    }

    private void confirmDelete(FamilyTask task) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.family_tasks_delete_title)
                .setMessage(R.string.family_tasks_delete_message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    FamilyTaskScheduler.cancel(requireContext(), task.id);
                    repository.delete(task, this::reload);
                }).show();
    }

    private long defaultDueTime() {
        Calendar c = Calendar.getInstance();
        if (activeFilter == R.id.task_filter_tomorrow) c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 18); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    private long[] activeRange() {
        Calendar start = Calendar.getInstance(); start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        if (activeFilter == R.id.task_filter_tomorrow) { start.add(Calendar.DAY_OF_YEAR, 1); end.add(Calendar.DAY_OF_YEAR, 2); }
        else if (activeFilter == R.id.task_filter_week) end.add(Calendar.DAY_OF_YEAR, 7);
        else if (activeFilter == R.id.task_filter_15_days) end.add(Calendar.DAY_OF_YEAR, 15);
        else if (activeFilter == R.id.task_filter_month) end.add(Calendar.MONTH, 1);
        else end.add(Calendar.DAY_OF_YEAR, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }
    private interface DateCallback { void onSelected(long value); }
    private void pickDateTime(long initial, DateCallback callback) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(initial);
        new DatePickerDialog(requireContext(), (picker, y, m, d) -> {
            c.set(y, m, d);
            new TimePickerDialog(requireContext(), (time, h, minute) -> { c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, minute); c.set(Calendar.SECOND, 0); callback.onSelected(c.getTimeInMillis()); }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void updateDueText(DialogFamilyTaskBinding form, long value) { form.taskDueInput.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(value))); }
    private static String text(android.widget.TextView view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private static int indexOf(String[] values, String target) { for (int i=0;i<values.length;i++) if (values[i].equals(target)) return i; return 0; }
    private static int indexOf(int[] values, int target) { for (int i=0;i<values.length;i++) if (values[i] == target) return i; return 3; }
    @Override public void onDestroyView() { if (repository != null) repository.stopRealtimeSync(); binding = null; super.onDestroyView(); }
}
