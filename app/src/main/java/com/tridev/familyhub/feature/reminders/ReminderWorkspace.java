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
        if (floating) binding.remindersOverview.setVisibility(View.GONE);
        initialize();
    }
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
        repository.startRealtimeSync(new ReminderRepository.RealtimeCallback() {
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
        loadReminders("");
    }

    public void add() { showReminderEditor(null); }
    public void reload() { if (binding != null) loadReminders(binding.reminderSearchInput.getText().toString()); }
    public void toggleSearch() { if (binding != null) binding.reminderSearchLayout.setVisibility(
            binding.reminderSearchLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
    public void setQuickControlsCollapsed(boolean collapsed) {
        if (binding != null) { binding.reminderQuickHost.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            binding.reminderFilterScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE); }
    }

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
        binding.reminderRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
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
        L…11282 tokens truncated…iew card=new MaterialCardView(c);card.setRadius(dp(14));card.setCardElevation(0);card.setStrokeWidth(dp(1));card.setStrokeColor(ContextCompat.getColor(c,R.color.fh_outline));card.setCardBackgroundColor(ContextCompat.getColor(c,fills[i]));card.setClickable(true);card.setFocusable(true);card.setContentDescription(labels[i]);
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
