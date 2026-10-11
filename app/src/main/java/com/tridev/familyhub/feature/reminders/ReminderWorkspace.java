package com.tridev.familyhub.feature.reminders;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.reminders.ReminderScheduler;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.ReminderRepository;
import com.tridev.familyhub.databinding.DialogReminderBinding;
import com.tridev.familyhub.databinding.FragmentRemindersBinding;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.reminders.adapter.ReminderAdapter;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.grocery.GroceryFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.property.PropertyFragment;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.io.File;
import java.util.Date;
import java.util.concurrent.Executors;

/** Create, manage, and schedule private local reminders. */
public final class ReminderWorkspace {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 501;

    private FragmentRemindersBinding binding;
    private ReminderAdapter reminderAdapter;
    private ReminderRepository repository;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final List<Reminder> loadedReminders = new ArrayList<>();
    private final List<Reminder> visibleReminders = new ArrayList<>();
    private String periodFilter = "ALL";
    private String priorityFilter = "ALL";
    private String categoryFilter = "ALL";
    private String statusFilter = "ACTIVE";
    private String memberFilter = "ALL";

    private final Context context;
    private final boolean floating;
    private final Runnable menu;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    private final List<android.widget.PopupWindow> popups = new ArrayList<>();
    private ReminderInlineVoice voice;
    private ReminderQuickAddView quick;
    private String sort = "DUE";

    public ReminderWorkspace(Context context, boolean floating, Runnable menu) {
        this.context = context; this.floating = floating; this.menu = menu;
        binding = FragmentRemindersBinding.inflate(LayoutInflater.from(context));
        binding.remindersOverview.setNavigationAction(R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title, clickedView -> menu.run());
        if (floating) { binding.remindersOverview.setVisibility(View.GONE); binding.reminderDashboardSummary.setVisibility(View.GONE); }
        initialize();
        observedDatabase=com.tridev.familyhub.data.local.FamilyHubDatabase.getInstance(context);observedDatabase.getInvalidationTracker().addObserver(localObserver);
        com.tridev.familyhub.core.ui.ScheduleDropdownView.styleFilters(binding.reminderFilterScroll);
    }
    private String syncState="● Syncing…";
    private java.util.function.Consumer<String> syncListener;
    public void setSyncStatusListener(java.util.function.Consumer<String> listener){syncListener=listener;listener.accept(syncState);}
    private void syncStatus(boolean live,boolean connecting){if(binding==null)return;syncState=live?"● Live · Family sync":connecting?"● Syncing…":"● Offline · Local data";binding.remindersOverview.setDetail(syncState);if(syncListener!=null)syncListener.accept(syncState);}
    private com.tridev.familyhub.data.local.FamilyHubDatabase observedDatabase;
    private final android.os.Handler observerHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final androidx.room.InvalidationTracker.Observer localObserver=new androidx.room.InvalidationTracker.Observer("reminders"){
        @Override public void onInvalidated(@NonNull java.util.Set<String> tables){observerHandler.post(()->{if(binding!=null)reload();});}
    };
    public View getView() { return binding.getRoot(); }
    private Context requireContext() { return context; }
    private android.content.res.Resources getResources() { return context.getResources(); }
    private String getString(int id, Object... args) { return context.getString(id, args); }
    private LayoutInflater getLayoutInflater() { return LayoutInflater.from(context); }
    private boolean isAdded() { return binding != null; }
    private void startActivity(Intent intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private void initialize() {
        repository = new ReminderRepository(requireContext());
        binding.reminderDashboardSummary.setSelection(index -> {
            statusFilter = index == 1 ? "COMPLETED" : "ACTIVE";
            periodFilter = index == 2 ? "TODAY" : index == 3 ? "OVERDUE" : "ALL";
            applyFilters();
        });
        reminderAdapter = new ReminderAdapter(new ReminderAdapter.ReminderActionListener() {
            @Override
            public void onEdit(Reminder reminder) {
                showReminderEditor(reminder);
            }

            @Override
            public void onDelete(Reminder reminder) {
                confirmDelete(reminder);
            }

            @Override
            public void onEnabledChanged(Reminder reminder, boolean isEnabled) {
                updateEnabledState(reminder, isEnabled);
            }

            @Override
            public void onStatusChanged(Reminder reminder) {
                showStatusSelector(reminder);
            }

            @Override
            public void onSeen(Reminder reminder) {
                if (reminder.seenAt > 0L) return;
                reminder.seenAt = System.currentTimeMillis();
                repository.save(reminder, saved -> {
                    if (binding != null) loadReminders(
                            binding.reminderSearchInput.getText().toString());
                });
            }

            @Override
            public void onOpenModule(Reminder reminder) {
                openRelatedModule(reminder.relatedModule);
            }
        });

        binding.reminderRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.reminderRecyclerView.setAdapter(reminderAdapter);
        ((com.tridev.familyhub.core.ui.ScrollableWorkspaceLayout) binding.getRoot())
                .makeWorkspaceScrollable(1, binding.reminderListHost,
                        binding.reminderRecyclerView, binding.remindersEmptyState);
        repository.startRealtimeSync(new ReminderRepository.RealtimeCallback() {
            @Override public void onSyncStateChanged(boolean live,boolean connecting){syncStatus(live,connecting);}
            @Override public void onChanged(@NonNull Reminder reminder) {
                if (binding == null || !isAdded()) return;
                if (reminder.isEnabled) {
                    ReminderScheduler.schedule(requireContext(), reminder);
                } else {
                    ReminderScheduler.cancel(requireContext(), reminder.id);
                }
                loadReminders(binding.reminderSearchInput.getText().toString());
            }

            @Override public void onRemoved(long localId) {
                if (binding == null || !isAdded()) return;
                ReminderScheduler.cancel(requireContext(), localId);
                loadReminders(binding.reminderSearchInput.getText().toString());
            }
        });
        binding.emptyAddReminderButton.setOnClickListener(v -> showReminderEditor(null));
        binding.reminderPeriodFilter.setOnClickListener(v -> showFilterChoices(
                "Calendar view", new String[]{"ALL", "OVERDUE", "TODAY", "THIS WEEK", "THIS MONTH"},
                periodFilter, value -> { periodFilter = value; applyFilters(); }));
        binding.reminderPriorityFilter.setOnClickListener(v -> showFilterChoices(
                "Priority", new String[]{"ALL", "LOW", "MEDIUM", "HIGH", "URGENT"},
                priorityFilter, value -> { priorityFilter = value; applyFilters(); }));
        binding.reminderCategoryFilter.setOnClickListener(v -> showFilterChoices(
                "Category", new String[]{"ALL", "GENERAL", "FAMILY", "FINANCE", "HEALTH", "GROCERY", "VEHICLE", "DOCUMENT", "PROPERTY"},
                categoryFilter, value -> { categoryFilter = value; applyFilters(); }));
        binding.reminderStatusFilter.setOnClickListener(v -> showFilterChoices(
                "Status", new String[]{"ACTIVE", "ALL", "PENDING", "ACCEPTED", "IN_PROGRESS", "COMPLETED"},
                statusFilter, value -> { statusFilter = value; applyFilters(); }));
        binding.reminderMemberFilter.setOnClickListener(v -> showMemberFilter());
        binding.reminderReportButton.setOnClickListener(v -> showReportOptions());
        binding.reminderSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No action required before searching.
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // The final text is handled below.
            }

            @Override
            public void afterTextChanged(Editable searchText) {
                loadReminders(searchText.toString());
            }
        });
        binding.reminderSortFilter.setOnClickListener(v -> showFilterChoices("Sort",
                new String[]{"DUE", "LATEST", "PRIORITY", "TITLE"}, sort, value -> { sort = value; applyFilters(); }));
        com.tridev.familyhub.core.ui.CompactFormStyle.applyInputs(binding.reminderSearchLayout);
        binding.reminderSearchLayout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        binding.reminderSearchLayout.setEndIconDrawable(R.drawable.ic_mic);
        voice = new ReminderInlineVoice(context);
        voice.bind(binding.reminderSearchLayout);
        quick = new ReminderQuickAddView(context, floating, this::saveQuick, () -> showReminderEditor(quick.draft()));
        binding.reminderQuickHost.addView(quick);
        voice.bind(quick);
        com.google.android.material.button.MaterialButton alarmAccess=new com.google.android.material.button.MaterialButton(context);alarmAccess.setText("Alarm access / settings");alarmAccess.setAllCaps(false);alarmAccess.setTextSize(11);alarmAccess.setOnClickListener(v->context.startActivity(new Intent(context,com.tridev.familyhub.core.reminders.ReminderAlarmAccessActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));quick.addView(alarmAccess,new android.widget.LinearLayout.LayoutParams(-1,-2));
        loadReminders("");
    }

    public void add() { showReminderEditor(null); }
    public void reload() { if (binding != null) loadReminders(binding.reminderSearchInput.getText().toString()); }
    public void toggleSearch() {
        if (binding == null) return;
        boolean show = binding.reminderSearchLayout.getVisibility() != View.VISIBLE;
        binding.reminderSearchLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.reminderRecyclerView.stopScroll();
            ((LinearLayoutManager) binding.reminderRecyclerView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
    }
    public void setQuickControlsCollapsed(boolean collapsed) { if (binding != null && quick != null) quick.setDetailsCollapsed(collapsed); }

    private void loadReminders(String query) {
        repository.loadReminders(query, reminders -> {
            if (binding == null) {
                return;
            }
            loadedReminders.clear();
            loadedReminders.addAll(reminders);
            binding.reminderDashboardSummary.setReminders(reminders);
            applyFilters();
        });
    }

    private interface FilterSelection {
        void onSelected(@NonNull String value);
    }

    private void showFilterChoices(@NonNull String title, @NonNull String[] values,
                                   @NonNull String current,
                                   @NonNull FilterSelection listener) {
        View anchor = binding.reminderPeriodFilter;
        if ("Priority".equals(title)) anchor = binding.reminderPriorityFilter;
        else if ("Category".equals(title)) anchor = binding.reminderCategoryFilter;
        else if ("Status".equals(title)) anchor = binding.reminderStatusFilter;
        else if ("Family member".equals(title)) anchor = binding.reminderMemberFilter;
        else if ("Sort".equals(title)) anchor = binding.reminderSortFilter;
        ReminderPanels.choices(context, anchor, floating, values, current, value -> listener.onSelected(value), popups);
    }

    private void showMemberFilter() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Reminder reminder : loadedReminders) {
            if (!reminder.assignedMemberName.trim().isEmpty()) {
                names.add(reminder.assignedMemberName.trim());
            }
        }
        List<String> values = new ArrayList<>();
        values.add("ALL");
        values.add("UNASSIGNED");
        values.addAll(names);
        showFilterChoices("Family member", values.toArray(new String[0]), memberFilter,
                value -> { memberFilter = value; applyFilters(); });
    }

    private void applyFilters() {
        if (binding == null) return;
        List<Reminder> filtered = new ArrayList<>();
        long now = System.currentTimeMillis();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long startToday = start.getTimeInMillis();
        Calendar end = (Calendar) start.clone();
        if ("TODAY".equals(periodFilter)) end.add(Calendar.DATE, 1);
        else if ("THIS WEEK".equals(periodFilter)) end.add(Calendar.DATE, 7);
        else if ("THIS MONTH".equals(periodFilter)) end.add(Calendar.MONTH, 1);
        long endAt = end.getTimeInMillis();

        for (Reminder reminder : loadedReminders) {
            if (!"ALL".equals(priorityFilter)
                    && !priorityFilter.equalsIgnoreCase(reminder.priority)) continue;
            if (!"ALL".equals(categoryFilter)
                    && !categoryFilter.equalsIgnoreCase(reminder.category)) continue;
            if ("ACTIVE".equals(statusFilter) && "COMPLETED".equalsIgnoreCase(reminder.collaborationStatus)) continue;
            if (!"ALL".equals(statusFilter) && !"ACTIVE".equals(statusFilter)
                    && !statusFilter.equalsIgnoreCase(reminder.collaborationStatus)) continue;
            if ("UNASSIGNED".equals(memberFilter)
                    && !reminder.assignedMemberName.trim().isEmpty()) continue;
            if (!"ALL".equals(memberFilter) && !"UNASSIGNED".equals(memberFilter)
                    && !memberFilter.equalsIgnoreCase(reminder.assignedMemberName)) continue;
            if (!"ALL".equals(periodFilter)) {
                long date = effectiveFilterTime(reminder, now);
                if ("OVERDUE".equals(periodFilter)) {
                    if (!Reminder.REPEAT_ONCE.equals(reminder.repeatType) || date >= now
                            || "COMPLETED".equalsIgnoreCase(reminder.collaborationStatus)) continue;
                } else if (date < startToday || date >= endAt) continue;
            }
            filtered.add(reminder);
        }

        filtered.sort((left, right) -> {
            if ("TITLE".equals(sort)) return left.title.compareToIgnoreCase(right.title);
            if ("LATEST".equals(sort)) return Long.compare(right.updatedAt, left.updatedAt);
            if ("PRIORITY".equals(sort)) { int order = Integer.compare(priorityRank(right.priority), priorityRank(left.priority)); if (order != 0) return order; }
            return Long.compare(effectiveFilterTime(left, now), effectiveFilterTime(right, now));
        });
        binding.reminderResults.setText(getString(R.string.reminders_smart_results, filtered.size(), loadedReminders.size()));
        binding.reminderSortFilter.setText(filterLabel(sort, "Due"));
        reminderAdapter.submitList(filtered);
        visibleReminders.clear();
        visibleReminders.addAll(filtered);
        boolean empty = filtered.isEmpty();
        binding.remindersEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.reminderPeriodFilter.setText(filterLabel(periodFilter, "All dates"));
        binding.reminderPriorityFilter.setText(filterLabel(priorityFilter, "Priority"));
        binding.reminderCategoryFilter.setText(filterLabel(categoryFilter, "Category"));
        binding.reminderStatusFilter.setText(filterLabel(statusFilter, "Status"));
        binding.reminderMemberFilter.setText(filterLabel(memberFilter, "Member"));
    }

    private long effectiveFilterTime(@NonNull Reminder reminder, long now) {
        if (Reminder.REPEAT_ONCE.equals(reminder.repeatType)) return reminder.reminderAt;
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(reminder.reminderAt);
        int field = Calendar.DATE;
        if (Reminder.REPEAT_WEEKLY.equals(reminder.repeatType)) field = Calendar.WEEK_OF_YEAR;
        else if (Reminder.REPEAT_MONTHLY.equals(reminder.repeatType)) field = Calendar.MONTH;
        else if (Reminder.REPEAT_YEARLY.equals(reminder.repeatType)) field = Calendar.YEAR;
        while (next.getTimeInMillis() < now) next.add(field, 1);
        return next.getTimeInMillis();
    }

    @NonNull
    private String filterLabel(@NonNull String value, @NonNull String fallback) {
        return ("ALL".equals(value) ? fallback : value.replace('_', ' ')) + "  ▾";
    }

    private void showReportOptions() {
        String[] options = {"View analytics", "Export PDF", "Export Excel", "Share PDF"};
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reminder analytics & report")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showAnalytics();
                    else exportReminderReport(which != 2);
                })
                .setNegativeButton(R.string.cancel, null)
                .create());
    }

    private void showAnalytics() {
        int completed = 0, overdue = 0, urgent = 0, shared = 0;
        long now = System.currentTimeMillis();
        for (Reminder reminder : visibleReminders) {
            if ("COMPLETED".equalsIgnoreCase(reminder.collaborationStatus)) completed++;
            if (Reminder.REPEAT_ONCE.equals(reminder.repeatType)
                    && reminder.isEnabled && reminder.reminderAt < now) overdue++;
            if ("URGENT".equalsIgnoreCase(reminder.priority)) urgent++;
            if (reminder.isShared) shared++;
        }
        int total = visibleReminders.size();
        double completion = total == 0 ? 0D : completed * 100D / total;
        String message = "Current filtered reminders  •  " + total
                + "\n\nCompleted  •  " + completed
                + "\nPending / active  •  " + Math.max(0, total - completed)
                + "\nOverdue  •  " + overdue
                + "\nUrgent  •  " + urgent
                + "\nShared with family  •  " + shared
                + "\n\nCompletion rate  •  "
                + String.format(Locale.getDefault(), "%.1f%%", completion);
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Smart reminder analytics")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    private void exportReminderReport(boolean pdf) {
        Context context = requireContext().getApplicationContext();
        List<Reminder> reportRows = new ArrayList<>(visibleReminders);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File folder = new File(context.getCacheDir(), "reminder_reports");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                File file = new File(folder, "Reminder_Report_" + stamp
                        + (pdf ? ".pdf" : ".xls"));
                if (pdf) ReminderReportExporter.pdf(file, reportRows);
                else ReminderReportExporter.excel(file, reportRows);
                if (isAdded()) handler.post(() -> shareReminderFile(
                        file, pdf ? "application/pdf" : "application/vnd.ms-excel"));
            } catch (Exception error) {
                if (isAdded()) handler.post(() -> Snackbar.make(
                        binding.getRoot(), "Reminder report could not be created",
                        Snackbar.LENGTH_SHORT).show());
            }
        });
    }

    private void shareReminderFile(@NonNull File file, @NonNull String mime) {
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".backupfiles", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Reminder report"));
    }

    private void showReminderEditor(@Nullable Reminder existingReminder) {
        DialogReminderBinding dialogBinding = DialogReminderBinding.inflate(getLayoutInflater());
        com.tridev.familyhub.core.ui.CompactFormStyle.apply(dialogBinding.getRoot());
        String[] repeatLabels = {getString(R.string.reminder_once),
                getString(R.string.reminder_daily), "Weekly", "Monthly", "Yearly"};
        int[] repeatIds = {R.id.repeat_once_button, R.id.repeat_daily_button,
                R.id.repeat_weekly_button, R.id.repeat_monthly_button, R.id.repeat_yearly_button};
        dialogBinding.reminderRepeatInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, repeatLabels));
        dialogBinding.reminderRepeatInput.setText(repeatLabels[0], false);
        dialogBinding.reminderRepeatInput.setOnItemClickListener((parent, view, position, id) ->
                dialogBinding.reminderRepeatGroup.check(repeatIds[position]));
        dialogBinding.reminderRepeatGroup.setOnCheckedChangeListener((group, checkedId) -> {
            for (int index = 0; index < repeatIds.length; index++) {
                if (repeatIds[index] == checkedId) {
                    dialogBinding.reminderRepeatInput.setText(repeatLabels[index], false);
                    break;
                }
            }
        });
        String[] collaborationLabels =
                getResources().getStringArray(R.array.collaboration_status_labels);
        dialogBinding.reminderCollaborationStatusInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, collaborationLabels
        ));
        String[] priorities = {"LOW", "MEDIUM", "HIGH", "URGENT"};
        String[] categories = {"GENERAL", "FAMILY", "FINANCE", "HEALTH", "GROCERY", "VEHICLE", "DOCUMENT", "PROPERTY"};
        dialogBinding.reminderPriorityInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, priorities));
        dialogBinding.reminderCategoryInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, categories));
        String[] preAlertLabels = {"At reminder time", "10 minutes before", "30 minutes before", "1 hour before", "1 day before"};
        int[] preAlertValues = {0, 10, 30, 60, 1440};
        dialogBinding.reminderPreAlertInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, preAlertLabels));
        String[] relatedModules = {"None", "FINANCE", "GROCERY", "HEALTH", "VEHICLE", "DOCUMENTS", "PROPERTY"};
        dialogBinding.reminderRelatedModuleInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, relatedModules));
        final List<FamilyMember> memberChoices = new ArrayList<>();
        List<String> memberLabels = new ArrayList<>();
        memberLabels.add("Unassigned");
        dialogBinding.reminderAssigneeInput.setAdapter(new android.widget.ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, memberLabels));
        repository.loadFamilyMembers(members -> {
            if (binding == null || !isAdded()) return;
            memberChoices.clear();
            memberChoices.addAll(members);
            memberLabels.clear();
            memberLabels.add("Unassigned");
            for (FamilyMember member : members) memberLabels.add(member.name);
            dialogBinding.reminderAssigneeInput.setAdapter(new android.widget.ArrayAdapter<>(
                    requireContext(), R.layout.item_form_dropdown, memberLabels));
        });
        boolean isNewReminder = existingReminder == null || existingReminder.id == 0;
        final boolean quickDraft = existingReminder != null && existingReminder.id == 0;
        Calendar selectedTime = Calendar.getInstance();
        selectedTime.add(Calendar.HOUR_OF_DAY, 1);
        selectedTime.set(Calendar.SECOND, 0);
        selectedTime.set(Calendar.MILLISECOND, 0);

        dialogBinding.reminderEditorTitle.setText(isNewReminder
                ? R.string.add_reminder_title
                : R.string.edit_reminder_title);
        if (existingReminder != null) {
            selectedTime.setTimeInMillis(existingReminder.reminderAt);
            dialogBinding.reminderTitleInput.setText(existingReminder.title);
            dialogBinding.reminderNoteInput.setText(existingReminder.note);
            dialogBinding.reminderPriorityInput.setText(existingReminder.priority, false);
            dialogBinding.reminderCategoryInput.setText(existingReminder.category, false);
            dialogBinding.reminderAssigneeInput.setText(
                    existingReminder.assignedMemberName.isEmpty()
                            ? "Unassigned" : existingReminder.assignedMemberName, false);
            int preAlertIndex = 0;
            for (int i = 0; i < preAlertValues.length; i++) {
                if (preAlertValues[i] == existingReminder.preAlertMinutes) preAlertIndex = i;
            }
            dialogBinding.reminderPreAlertInput.setText(preAlertLabels[preAlertIndex], false);
            dialogBinding.reminderRelatedModuleInput.setText(
                    existingReminder.relatedModule.isEmpty()
                            ? "None" : existingReminder.relatedModule, false);
            dialogBinding.reminderRelatedItemInput.setText(existingReminder.relatedItemTitle);
            dialogBinding.reminderRepeatGroup.check(repeatButtonId(existingReminder.repeatType));
            dialogBinding.reminderEnabledSwitch.setChecked(existingReminder.isEnabled);
            dialogBinding.reminderSharedSwitch.setChecked(existingReminder.isShared);
            dialogBinding.reminderCollaborationStatusInput.setText(
                    existingReminder.collaborationStatus, false
            );
        } else {
            dialogBinding.reminderCollaborationStatusInput.setText(collaborationLabels[0], false);
            dialogBinding.reminderPriorityInput.setText("MEDIUM", false);
            dialogBinding.reminderCategoryInput.setText("GENERAL", false);
            dialogBinding.reminderAssigneeInput.setText("Unassigned", false);
            dialogBinding.reminderPreAlertInput.setText(preAlertLabels[0], false);
            dialogBinding.reminderRelatedModuleInput.setText("None", false);
        }
        updateDateTimeInputs(dialogBinding, selectedTime);

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

        dialogBinding.reminderDateInput.setOnClickListener(v -> showDatePicker(dialogBinding, selectedTime));
        dialogBinding.reminderDateLayout.setEndIconOnClickListener(v -> showDatePicker(
                dialogBinding, selectedTime
        ));
        dialogBinding.reminderTimeInput.setOnClickListener(v -> showTimePicker(dialogBinding, selectedTime));
        dialogBinding.reminderTimeLayout.setEndIconOnClickListener(v -> showTimePicker(
                dialogBinding, selectedTime
        ));
        dialogBinding.cancelReminderButton.setOnClickListener(v -> dialog.dismiss());
        dialogBinding.saveReminderButton.setOnClickListener(v -> {
            String title = dialogBinding.reminderTitleInput.getText().toString().trim();
            String repeatType = selectedRepeatType(
                    dialogBinding.reminderRepeatGroup.getCheckedRadioButtonId());
            boolean repeating = !Reminder.REPEAT_ONCE.equals(repeatType);
            if (!validateEditor(dialogBinding.reminderTitleLayout, title, selectedTime, repeating)) {
                return;
            }

            Reminder reminder = existingReminder == null ? new Reminder() : existingReminder;
            reminder.title = title;
            reminder.note = dialogBinding.reminderNoteInput.getText().toString().trim();
            reminder.reminderAt = selectedTime.getTimeInMillis();
            reminder.repeatType = repeatType;
            reminder.priority = dialogBinding.reminderPriorityInput.getText().toString().trim();
            reminder.category = dialogBinding.reminderCategoryInput.getText().toString().trim();
            reminder.preAlertMinutes = 0;
            String selectedPreAlert = dialogBinding.reminderPreAlertInput.getText().toString();
            for (int i = 0; i < preAlertLabels.length; i++) {
                if (preAlertLabels[i].equals(selectedPreAlert)) reminder.preAlertMinutes = preAlertValues[i];
            }
            String relatedModule = dialogBinding.reminderRelatedModuleInput
                    .getText().toString().trim();
            reminder.relatedModule = "None".equalsIgnoreCase(relatedModule)
                    ? "" : relatedModule;
            reminder.relatedItemTitle = dialogBinding.reminderRelatedItemInput
                    .getText().toString().trim();
            String assignee = dialogBinding.reminderAssigneeInput.getText().toString().trim();
            reminder.assignedMemberName = "Unassigned".equals(assignee) ? "" : assignee;
            reminder.assignedMemberId = 0L;
            for (FamilyMember member : memberChoices) {
                if (member.name.equalsIgnoreCase(reminder.assignedMemberName)) {
                    reminder.assignedMemberId = member.id;
                    break;
                }
            }
            reminder.isEnabled = dialogBinding.reminderEnabledSwitch.isChecked();
            reminder.isShared = dialogBinding.reminderSharedSwitch.isChecked();
            reminder.collaborationStatus = dialogBinding.reminderCollaborationStatusInput
                    .getText().toString().trim();
            stampCollaborationStatus(reminder, reminder.collaborationStatus);

            dialogBinding.saveReminderButton.setEnabled(false);
            repository.save(reminder, savedReminder -> {
                if (savedReminder.isEnabled) {
                    if (binding != null) requestNotificationPermissionIfNeeded();
                    ReminderScheduler.schedule(requireContext(), savedReminder);
                } else {
                    ReminderScheduler.cancel(requireContext(), savedReminder.id);
                }
                if (binding == null) return;
                if (quickDraft) quick.saved();
                dialog.dismiss();
                loadReminders(binding.reminderSearchInput.getText().toString());
                Snackbar.make(
                        binding.getRoot(),
                        isNewReminder ? R.string.reminder_added : R.string.reminder_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        voice.bind(dialogBinding.getRoot());
        showDialog(dialog);
    }

    private boolean validateEditor(TextInputLayout titleLayout, String title, Calendar selectedTime,
                                   boolean repeating) {
        if (TextUtils.isEmpty(title)) {
            titleLayout.setError(getString(R.string.reminder_title_required));
            return false;
        }
        titleLayout.setError(null);
        if (!repeating && selectedTime.getTimeInMillis() <= System.currentTimeMillis()) {
            Snackbar.make(binding.getRoot(), R.string.reminder_future_time_required,
                    Snackbar.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private int repeatButtonId(@NonNull String repeatType) {
        if (Reminder.REPEAT_DAILY.equals(repeatType)) return R.id.repeat_daily_button;
        if (Reminder.REPEAT_WEEKLY.equals(repeatType)) return R.id.repeat_weekly_button;
        if (Reminder.REPEAT_MONTHLY.equals(repeatType)) return R.id.repeat_monthly_button;
        if (Reminder.REPEAT_YEARLY.equals(repeatType)) return R.id.repeat_yearly_button;
        return R.id.repeat_once_button;
    }

    @NonNull
    private String selectedRepeatType(int buttonId) {
        if (buttonId == R.id.repeat_daily_button) return Reminder.REPEAT_DAILY;
        if (buttonId == R.id.repeat_weekly_button) return Reminder.REPEAT_WEEKLY;
        if (buttonId == R.id.repeat_monthly_button) return Reminder.REPEAT_MONTHLY;
        if (buttonId == R.id.repeat_yearly_button) return Reminder.REPEAT_YEARLY;
        return Reminder.REPEAT_ONCE;
    }

    private void updateDateTimeInputs(DialogReminderBinding dialogBinding, Calendar selectedTime) {
        dialogBinding.reminderDateInput.setText(dateFormat.format(selectedTime.getTime()));
        dialogBinding.reminderTimeInput.setText(timeFormat.format(selectedTime.getTime()));
    }

    private void showDatePicker(DialogReminderBinding dialogBinding, Calendar selectedTime) {
        showDialog(new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedTime.set(Calendar.YEAR, year);
                    selectedTime.set(Calendar.MONTH, month);
                    selectedTime.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    updateDateTimeInputs(dialogBinding, selectedTime);
                },
                selectedTime.get(Calendar.YEAR),
                selectedTime.get(Calendar.MONTH),
                selectedTime.get(Calendar.DAY_OF_MONTH)
        ));
    }

    private void showTimePicker(DialogReminderBinding dialogBinding, Calendar selectedTime) {
        showDialog(new TimePickerDialog(
                requireContext(),
                (view, hourOfDay, minute) -> {
                    selectedTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    selectedTime.set(Calendar.MINUTE, minute);
                    selectedTime.set(Calendar.SECOND, 0);
                    selectedTime.set(Calendar.MILLISECOND, 0);
                    updateDateTimeInputs(dialogBinding, selectedTime);
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                false
        ));
    }

    private void updateEnabledState(Reminder reminder, boolean isEnabled) {
        reminder.isEnabled = isEnabled;
        repository.save(reminder, savedReminder -> {
            if (savedReminder.isEnabled) {
                requestNotificationPermissionIfNeeded();
                ReminderScheduler.schedule(requireContext(), savedReminder);
            } else {
                ReminderScheduler.cancel(requireContext(), savedReminder.id);
            }
            if (binding != null) {
                Snackbar.make(
                        binding.getRoot(),
                        savedReminder.isEnabled ? R.string.reminder_enabled : R.string.reminder_disabled,
                        Snackbar.LENGTH_SHORT
                ).show();
            }
        });
    }

    private void showStatusSelector(@NonNull Reminder reminder) {
        String[] statuses = getResources().getStringArray(R.array.collaboration_status_labels);
        int selected = 0;
        for (int i = 0; i < statuses.length; i++) {
            if (statuses[i].equalsIgnoreCase(reminder.collaborationStatus)) selected = i;
        }
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Update reminder status")
                .setSingleChoiceItems(statuses, selected, (dialog, which) -> {
                    applyCollaborationStatus(reminder, statuses[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .create());
    }

    private void applyCollaborationStatus(@NonNull Reminder reminder,
                                          @NonNull String status) {
        reminder.collaborationStatus = status;
        stampCollaborationStatus(reminder, status);
        repository.save(reminder, saved -> {
            if (binding == null) return;
            loadReminders(binding.reminderSearchInput.getText().toString());
            Snackbar.make(binding.getRoot(), "Reminder status updated",
                    Snackbar.LENGTH_SHORT).show();
        });
    }

    private void stampCollaborationStatus(@NonNull Reminder reminder,
                                          @NonNull String status) {
        long now = System.currentTimeMillis();
        if (reminder.seenAt == 0L) reminder.seenAt = now;
        if ("ACCEPTED".equals(status) && reminder.acceptedAt == 0L) {
            reminder.acceptedAt = now;
        } else if ("IN_PROGRESS".equals(status)) {
            if (reminder.acceptedAt == 0L) reminder.acceptedAt = now;
            if (reminder.startedAt == 0L) reminder.startedAt = now;
        } else if ("COMPLETED".equals(status)) {
            if (reminder.acceptedAt == 0L) reminder.acceptedAt = now;
            if (reminder.startedAt == 0L) reminder.startedAt = now;
            reminder.completedAt = now;
            reminder.completedByName = reminder.assignedMemberName.trim().isEmpty()
                    ? "Family member" : reminder.assignedMemberName.trim();
        }
    }

    private void openRelatedModule(@Nullable String module) {
        if (module == null || module.trim().isEmpty()) return;
        String route = "FINANCE".equalsIgnoreCase(module) ? MainActivity.ROUTE_FINANCE
                : "GROCERY".equalsIgnoreCase(module) ? MainActivity.ROUTE_GROCERY
                : "HEALTH".equalsIgnoreCase(module) ? MainActivity.ROUTE_HEALTH
                : "VEHICLE".equalsIgnoreCase(module) ? MainActivity.ROUTE_VEHICLES
                : "DOCUMENTS".equalsIgnoreCase(module) ? MainActivity.ROUTE_DOCUMENTS
                : "PROPERTY".equalsIgnoreCase(module) ? MainActivity.ROUTE_PROPERTY : null;
        if (route != null) {
            startActivity(new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_ROUTE, route));
            if (floating) context.stopService(new Intent(context, com.tridev.familyhub.feature.reminders.overlay.RemindersOverlayService.class));
        }

    }

    private void confirmDelete(Reminder reminder) {
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_reminder_title)
                .setMessage(getString(R.string.delete_reminder_message, reminder.title))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_delete, (dialog, which) -> {
                    ReminderScheduler.cancel(requireContext(), reminder.id);
                    repository.delete(reminder, deletedReminder -> {
                        if (binding == null) {
                            return;
                        }
                        loadReminders(binding.reminderSearchInput.getText().toString());
                        Snackbar.make(binding.getRoot(), R.string.reminder_deleted,
                                Snackbar.LENGTH_SHORT).show();
                    });
                })
                .create());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(context, com.tridev.familyhub.feature.notes.overlay.NotesNotificationPermissionActivity.class));
            Snackbar.make(binding.getRoot(), R.string.notification_permission_explanation,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    private int priorityRank(String value) { return "URGENT".equals(value) ? 3 : "HIGH".equals(value) ? 2 : "MEDIUM".equals(value) ? 1 : 0; }
    private void showDialog(android.app.Dialog dialog) {
        if (binding == null) return;
        if (floating && dialog.getWindow() != null) dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialogs.add(dialog); dialog.setOnDismissListener(d -> { dialogs.remove(dialog); if (voice != null) voice.stop(); });
        dialog.show();
    }
    private void saveQuick(Reminder reminder) {
        repository.save(reminder, saved -> {
            if (saved.isEnabled) { if (binding != null) requestNotificationPermissionIfNeeded(); ReminderScheduler.schedule(context, saved); }
            if (binding == null) return;
            quick.saved(); reload();
            Snackbar.make(binding.getRoot(), R.string.reminder_added, Snackbar.LENGTH_SHORT).show();
        });
    }
    public void dispose() {
        observedDatabase.getInvalidationTracker().removeObserver(localObserver);observerHandler.removeCallbacksAndMessages(null);
        repository.stopRealtimeSync();
        if (voice != null) voice.stop();
        if (quick != null) quick.dispose();
        for (android.widget.PopupWindow popup : new ArrayList<>(popups)) popup.dismiss();
        for (android.app.Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        handler.removeCallbacksAndMessages(null); binding = null;
    }
}
