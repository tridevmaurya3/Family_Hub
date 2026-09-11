package com.tridev.familyhub.feature.tasks;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.databinding.DialogFamilyTaskBinding;
import com.tridev.familyhub.databinding.FragmentFamilyTasksBinding;
import com.tridev.familyhub.feature.grocery.GroceryOptionCatalog;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.tasks.overlay.FamilyTaskOverlayService;
import com.tridev.familyhub.feature.quickhub.UniversalQuickHubController;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Grocery-style, realtime Family To-Do surface without changing task ownership or sync. */
public final class FamilyTasksFragment extends Fragment implements AddActionHost {
    private static final String ARG_OPEN_NEW_TASK = "open_new_task";
    private static final int SORT_DUE = 0;
    private static final int SORT_PRIORITY = 1;
    private static final int SORT_NEWEST = 2;
    private static final int SORT_TITLE = 3;

    private FragmentFamilyTasksBinding binding;
    private FamilyTaskRepository repository;
    private FamilyMemberRepository memberRepository;
    private FamilyTaskAdapter adapter;
    private int activeFilter = R.id.task_filter_today;
    private int activeStatus = R.id.task_status_pending;
    private int activeAssignment = R.id.task_assignment_everyone;
    private int activeSort = SORT_DUE;
    private boolean overdueOnly;
    private long customDateStart;
    private long customDateEnd;
    private int quickDateMode;
    private int quickPriorityMode;
    private int quickRepeatMode;
    private long quickCustomDueAt;
    @Nullable private MaterialButton taskDateDropdown;
    @Nullable private MaterialButton taskStatusDropdown;
    @Nullable private MaterialButton taskAssignmentDropdown;
    @Nullable private MaterialButton taskCategoryCollapseButton;
    @Nullable private android.widget.EditText pendingVoiceTarget;
    @Nullable private SpeechRecognizer speechRecognizer;

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && pendingVoiceTarget != null) startVoiceCapture(pendingVoiceTarget);
                else if (isAdded()) android.widget.Toast.makeText(requireContext(),
                        R.string.family_tasks_voice_permission, android.widget.Toast.LENGTH_LONG).show();
            });

    @NonNull
    public static FamilyTasksFragment forNewTask() {
        FamilyTasksFragment fragment = new FamilyTasksFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_OPEN_NEW_TASK, true);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFamilyTasksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.taskOverview.setNavigationAction(R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title,
                v -> ((MainActivity) requireActivity()).showFeatureMenu());
        repository = new FamilyTaskRepository(requireContext());
        memberRepository = new FamilyMemberRepository(requireContext());
        adapter = new FamilyTaskAdapter(new FamilyTaskAdapter.Listener() {
            @Override
            public void onCompletedChanged(@NonNull FamilyTask task, boolean completed) {
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

        setupGroceryStyleControls();
        setupQuickAddOptions();

        binding.taskQuickAddButton.setOnClickListener(v -> quickAdd());
        binding.taskQuickVoiceButton.setOnClickListener(v ->
                requestVoiceCapture(binding.taskQuickAddInput));
        binding.taskFloatingToggle.setOnClickListener(v -> toggleFloatingStrip());
        binding.taskDueCalendarButton.setOnClickListener(v -> pickCalendarDay());
        binding.taskQuickAddInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                quickAdd();
                return true;
            }
            return false;
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
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { reload(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });

        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull FamilyTask task) {
                if (binding != null) {
                    FamilyTaskScheduler.schedule(requireContext(), task);
                    reload();
                }
            }
            @Override public void onRemoved(long localId) {
                if (binding != null) reload();
            }
        });
        reload();
        updateFloatingButton();
        Bundle args = getArguments();
        if (args != null && args.getBoolean(ARG_OPEN_NEW_TASK, false)) {
            args.remove(ARG_OPEN_NEW_TASK);
            view.post(() -> {
                if (binding != null) prepareEditor(null);
            });
        }
    }

    private void setupGroceryStyleControls() {
        binding.taskFilterGroup.setSelectionRequired(false);
        binding.taskFilterGroup.setSingleSelection(false);
        binding.taskFilterGroup.clearCheck();

        taskDateDropdown = createFilterDropdown(getString(R.string.family_tasks_date_today),
                R.color.fh_success_container, R.color.fh_success,
                R.color.fh_on_success_container, 86);
        taskStatusDropdown = createFilterDropdown(getString(R.string.task_status_pending),
                R.color.fh_warning_container, R.color.fh_warning,
                R.color.fh_on_warning_container, 88);
        taskAssignmentDropdown = createFilterDropdown(
                getString(R.string.family_tasks_assignment_all_label),
                R.color.fh_info_container, R.color.fh_module_grocery,
                R.color.fh_module_grocery, 96);
        taskCategoryCollapseButton = createFilterDropdown("Collapse All",
                R.color.fh_success_container, R.color.fh_success,
                R.color.fh_on_success_container, 94);
        taskCategoryCollapseButton.setText("Collapse All");
        taskCategoryCollapseButton.setGravity(Gravity.CENTER);

        taskDateDropdown.setOnClickListener(this::showDateDropdown);
        taskStatusDropdown.setOnClickListener(this::showStatusDropdown);
        taskAssignmentDropdown.setOnClickListener(this::showAssignmentDropdown);
        binding.taskFilterGroup.addView(taskStatusDropdown, 0,
                new ViewGroup.MarginLayoutParams(dp(88), dp(38)));
        binding.taskFilterGroup.addView(taskDateDropdown, 1,
                new ViewGroup.MarginLayoutParams(dp(86), dp(38)));
        binding.taskFilterGroup.addView(taskAssignmentDropdown, 2,
                new ViewGroup.MarginLayoutParams(dp(96), dp(38)));
        binding.taskFilterGroup.addView(taskCategoryCollapseButton, 3,
                new ViewGroup.MarginLayoutParams(dp(94), dp(38)));
        taskCategoryCollapseButton.setOnClickListener(v -> {
            boolean collapsed = adapter.toggleAllCategories();
            taskCategoryCollapseButton.setText(collapsed ? "Expand All" : "Collapse All");
            resetTaskScroll();
        });

        binding.taskFilterButton.setOnClickListener(v -> {
            boolean show = binding.taskFilterScroll.getVisibility() != View.VISIBLE;
            binding.taskFilterScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.taskFilterButton.setContentDescription(getString(show
                    ? R.string.family_tasks_filters_hide : R.string.family_tasks_filters_show));
        });
        binding.taskSortButton.setOnClickListener(this::showSortDropdown);
        syncPremiumFilterControls();
        fitPremiumFilterControls();
    }

    @NonNull
    private MaterialButton createFilterDropdown(@NonNull String label,
                                                int backgroundColor,
                                                int strokeColor,
                                                int textColor,
                                                int widthDp) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setText(label + "  ▾");
        button.setTextSize(10f);
        button.setSingleLine(true);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(7), 0, dp(6), 0);
        button.setCornerRadius(dp(15));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), strokeColor)));
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), backgroundColor)));
        button.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        button.setElevation(dp(1));
        button.setContentDescription(label);
        button.setLayoutParams(new ViewGroup.MarginLayoutParams(dp(widthDp), dp(38)));
        return button;
    }

    private interface FilterChoiceListener { void onChoice(int index); }

    private void setupQuickAddOptions() {
        binding.taskQuickDateButton.setText(getString(
                R.string.family_tasks_overlay_today) + "  ▾");
        binding.taskQuickPriorityButton.setText(getString(
                R.string.task_priority_normal) + "  ▾");
        binding.taskQuickRepeatButton.setText(getString(
                R.string.task_repeat_none) + "  ▾");

        binding.taskQuickDateButton.setOnClickListener(anchor -> showPremiumFilterPopup(
                anchor, new String[]{getString(R.string.family_tasks_overlay_today),
                        getString(R.string.family_tasks_overlay_tomorrow),
                        getString(R.string.task_due_next_week)}, quickDateMode,
                ContextCompat.getColor(requireContext(), R.color.fh_success), index -> {
                    quickDateMode = index;
                    if (index == 2) {
                        long initial = quickCustomDueAt > 0L
                                ? quickCustomDueAt : quickDueAt(0);
                        pickDateTime(initial, selected -> {
                            quickCustomDueAt = selected;
                            binding.taskQuickDateButton.setText(
                                    getString(R.string.task_due_next_week) + "  ▾");
                            binding.taskQuickDateButton.setContentDescription(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                                            DateFormat.SHORT).format(new Date(selected)));
                        });
                    } else {
                        binding.taskQuickDateButton.setText(getString(index == 1
                                ? R.string.family_tasks_overlay_tomorrow
                                : R.string.family_tasks_overlay_today) + "  ▾");
                    }
                }));
        binding.taskQuickPriorityButton.setOnClickListener(anchor -> showPremiumFilterPopup(
                anchor, new String[]{getString(R.string.task_priority_normal),
                        getString(R.string.task_priority_high),
                        getString(R.string.task_priority_urgent)}, quickPriorityMode,
                ContextCompat.getColor(requireContext(), R.color.fh_primary), index -> {
                    quickPriorityMode = index;
                    int[] labels = {R.string.task_priority_normal,
                            R.string.task_priority_high, R.string.task_priority_urgent};
                    binding.taskQuickPriorityButton.setText(getString(labels[index]) + "  ▾");
                }));
        binding.taskQuickRepeatButton.setOnClickListener(anchor -> showPremiumFilterPopup(
                anchor, new String[]{getString(R.string.task_repeat_none), "Repeated"},
                quickRepeatMode, ContextCompat.getColor(requireContext(), R.color.fh_info), index -> {
                    quickRepeatMode = index;
                    binding.taskQuickRepeatButton.setText(
                            (index == 1 ? "Repeated" : getString(R.string.task_repeat_none)) + "  ▾");
                }));
    }

    private void showDateDropdown(@NonNull View anchor) {
        boolean completed = activeStatus == R.id.task_status_completed;
        String[] labels = completed
                ? new String[]{getString(R.string.family_tasks_date_today),
                getString(R.string.family_tasks_overlay_yesterday),
                getString(R.string.family_tasks_overlay_last_7_days),
                getString(R.string.family_tasks_overlay_last_15_days),
                getString(R.string.family_tasks_overlay_last_30_days),
                getString(R.string.family_tasks_date_all)}
                : new String[]{getString(R.string.family_tasks_date_today),
                getString(R.string.family_tasks_date_tomorrow),
                getString(R.string.family_tasks_overlay_next_7_days),
                getString(R.string.family_tasks_overlay_next_15_days),
                getString(R.string.family_tasks_overlay_next_30_days),
                getString(R.string.family_tasks_date_all)};
        int[] values = {
                R.id.task_filter_today, R.id.task_filter_tomorrow,
                R.id.task_filter_week, R.id.task_filter_15_days,
                R.id.task_filter_month, R.id.task_filter_all
        };
        int selected = customDateStart > 0L || overdueOnly ? -1 : indexOf(values, activeFilter);
        showPremiumFilterPopup(anchor, labels, selected,
                ContextCompat.getColor(requireContext(), R.color.fh_success), index -> {
                    activeFilter = values[index];
                    overdueOnly = false;
                    customDateStart = 0L;
                    customDateEnd = 0L;
                    binding.taskDueCalendarButton.setText(R.string.family_tasks_due_calendar);
                    syncPremiumFilterControls();
                    resetTaskScroll();
                    reload();
                });
    }

    private void showStatusDropdown(@NonNull View anchor) {
        String[] labels = {
                getString(R.string.task_status_pending),
                getString(R.string.task_status_completed),
                getString(R.string.family_tasks_status_all_label)
        };
        int[] values = {
                R.id.task_status_pending, R.id.task_status_completed, R.id.task_status_all
        };
        showPremiumFilterPopup(anchor, labels, indexOf(values, activeStatus),
                ContextCompat.getColor(requireContext(), R.color.fh_warning), index -> {
                    activeStatus = values[index];
                    activeFilter = R.id.task_filter_today;
                    overdueOnly = false;
                    customDateStart = 0L;
                    customDateEnd = 0L;
                    binding.taskDueCalendarButton.setText(R.string.family_tasks_due_calendar);
                    syncPremiumFilterControls();
                    resetTaskScroll();
                    reload();
                });
    }

    private void showAssignmentDropdown(@NonNull View anchor) {
        String[] labels = {
                getString(R.string.family_tasks_assignment_all_label),
                getString(R.string.family_tasks_assignment_mine_label),
                getString(R.string.family_tasks_assignment_family_label)
        };
        int[] values = {
                R.id.task_assignment_everyone,
                R.id.task_assignment_mine,
                R.id.task_assignment_family
        };
        showPremiumFilterPopup(anchor, labels, indexOf(values, activeAssignment),
                ContextCompat.getColor(requireContext(), R.color.fh_module_grocery), index -> {
                    activeAssignment = values[index];
                    syncPremiumFilterControls();
                    resetTaskScroll();
                    reload();
                });
    }

    private void showSortDropdown(@NonNull View anchor) {
        String[] labels = {
                getString(R.string.family_tasks_sort_due),
                getString(R.string.family_tasks_sort_priority),
                getString(R.string.family_tasks_sort_newest),
                getString(R.string.family_tasks_sort_title)
        };
        showPremiumFilterPopup(anchor, labels, activeSort,
                ContextCompat.getColor(requireContext(), R.color.fh_primary), index -> {
                    activeSort = index;
                    reload();
                });
    }

    private void showPremiumFilterPopup(@NonNull View anchor,
                                        @NonNull String[] labels,
                                        int selectedIndex,
                                        int accentColor,
                                        @NonNull FilterChoiceListener listener) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.setBackground(premiumFilterPopupBackground());
        root.setElevation(dp(10));

        PopupWindow popup = new PopupWindow(requireContext());
        popup.setContentView(root);
        int widest = anchor.getWidth();
        TextView measure = new TextView(requireContext());
        measure.setTextSize(12.5f);
        for (String label : labels) {
            widest = Math.max(widest,
                    Math.round(measure.getPaint().measureText("✓  " + label)) + dp(36));
        }
        int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(28);
        popup.setWidth(Math.min(Math.max(widest, dp(132)), maxWidth));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        for (int index = 0; index < labels.length; index++) {
            final int choice = index;
            boolean selected = index == selectedIndex;
            TextView row = new TextView(requireContext());
            row.setText((selected ? "✓  " : "   ") + labels[index]);
            row.setTextSize(12.5f);
            row.setSingleLine(true);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), 0, dp(10), 0);
            row.setTextColor(selected ? accentColor
                    : ContextCompat.getColor(requireContext(), R.color.fh_text_primary));
            if (selected) row.setTypeface(row.getTypeface(), Typeface.BOLD);
            row.setBackground(premiumFilterRowBackground(selected, accentColor));
            row.setOnClickListener(v -> {
                listener.onChoice(choice);
                popup.dismiss();
            });
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            if (index < labels.length - 1) params.bottomMargin = dp(4);
            root.addView(row, params);
        }
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    @NonNull
    private GradientDrawable premiumFilterPopupBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(253, 255, 255, 255),
                        Color.argb(249, 243, 249, 252)});
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), Color.argb(210, 199, 211, 221));
        return drawable;
    }

    @NonNull
    private GradientDrawable premiumFilterRowBackground(boolean selected, int accentColor) {
        int fill = selected
                ? Color.argb(38, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                : Color.argb(248, 255, 255, 255);
        int stroke = selected
                ? Color.argb(135, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                : Color.argb(125, 212, 222, 226);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void syncPremiumFilterControls() {
        if (taskDateDropdown == null || taskStatusDropdown == null
                || taskAssignmentDropdown == null) return;
        setDropdownLabel(taskDateDropdown, dateFilterLabel());
        setDropdownLabel(taskStatusDropdown, statusFilterLabel());
        setDropdownLabel(taskAssignmentDropdown, assignmentFilterLabel());
        fitPremiumFilterControls();
    }

    private void setDropdownLabel(@NonNull MaterialButton button, @NonNull String label) {
        button.setText(label + "  ▾");
        button.setContentDescription(label);
    }

    @NonNull
    private String dateFilterLabel() {
        if (overdueOnly) return getString(R.string.task_summary_overdue);
        if (customDateStart > 0L) {
            return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(customDateStart));
        }
        boolean completed = activeStatus == R.id.task_status_completed;
        if (activeFilter == R.id.task_filter_tomorrow) return getString(completed
                ? R.string.family_tasks_overlay_yesterday : R.string.family_tasks_date_tomorrow);
        if (activeFilter == R.id.task_filter_week) return getString(completed
                ? R.string.family_tasks_overlay_last_7_days : R.string.family_tasks_overlay_next_7_days);
        if (activeFilter == R.id.task_filter_15_days) return getString(completed
                ? R.string.family_tasks_overlay_last_15_days : R.string.family_tasks_overlay_next_15_days);
        if (activeFilter == R.id.task_filter_month) return getString(completed
                ? R.string.family_tasks_overlay_last_30_days : R.string.family_tasks_overlay_next_30_days);
        if (activeFilter == R.id.task_filter_all) return getString(R.string.family_tasks_date_all);
        return getString(R.string.family_tasks_date_today);
    }

    @NonNull
    private String statusFilterLabel() {
        if (activeStatus == R.id.task_status_completed) return getString(R.string.task_status_completed);
        if (activeStatus == R.id.task_status_all) return getString(R.string.family_tasks_status_all_label);
        return getString(R.string.task_status_pending);
    }

    @NonNull
    private String assignmentFilterLabel() {
        if (activeAssignment == R.id.task_assignment_mine) {
            return getString(R.string.family_tasks_assignment_mine_label);
        }
        if (activeAssignment == R.id.task_assignment_family) {
            return getString(R.string.family_tasks_assignment_family_label);
        }
        return getString(R.string.family_tasks_assignment_all_label);
    }

    private void fitPremiumFilterControls() {
        if (binding == null) return;
        binding.taskFilterScroll.post(() -> {
            if (binding == null) return;
            if (taskDateDropdown != null) resizeFilterControlToText(taskDateDropdown, 62, 132);
            if (taskStatusDropdown != null) resizeFilterControlToText(taskStatusDropdown, 72, 126);
            if (taskAssignmentDropdown != null) resizeFilterControlToText(taskAssignmentDropdown, 74, 140);
        });
    }

    private void resizeFilterControlToText(@NonNull MaterialButton button,
                                           int minimumDp, int maximumDp) {
        CharSequence value = button.getText();
        float textWidth = button.getPaint().measureText(value == null ? "" : value.toString());
        int desired = Math.round(textWidth) + button.getPaddingLeft()
                + button.getPaddingRight() + dp(12);
        int width = Math.max(dp(minimumDp), Math.min(dp(maximumDp), desired));
        ViewGroup.LayoutParams params = button.getLayoutParams();
        params.width = width;
        params.height = dp(38);
        button.setLayoutParams(params);
    }

    private void resetTaskScroll() {
        if (binding == null) return;
        binding.taskRecyclerView.stopScroll();
        binding.taskRecyclerView.post(() -> {
            if (binding != null) binding.taskRecyclerView.scrollToPosition(0);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        boolean requested = UniversalQuickHubController.wasRequested(requireContext());
        if (requested && Settings.canDrawOverlays(requireContext())) {
            UniversalQuickHubController.setRequested(requireContext(), false);
            startFloatingStrip();
        }
        updateFloatingButton();
    }

    private void toggleFloatingStrip() {
        boolean enabled = UniversalQuickHubController.isEnabled(requireContext());
        if (enabled) {
            UniversalQuickHubController.stop(requireContext());
            binding.taskFloatingToggle.postDelayed(this::updateFloatingButton, 180);
            return;
        }
        if (!Settings.canDrawOverlays(requireContext())) {
            UniversalQuickHubController.setRequested(requireContext(), true);
            android.widget.Toast.makeText(requireContext(),
                    R.string.family_tasks_overlay_permission, android.widget.Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
            return;
        }
        startFloatingStrip();
    }

    private void startFloatingStrip() {
        UniversalQuickHubController.start(requireContext(), true);
        binding.taskFloatingToggle.postDelayed(this::updateFloatingButton, 180);
    }

    private void updateFloatingButton() {
        if (binding == null) return;
        boolean enabled = UniversalQuickHubController.isEnabled(requireContext());
        binding.taskFloatingToggle.setText(enabled
                ? R.string.family_tasks_floating_hide : R.string.family_tasks_floating_show);
    }

    @Override public void onAddRequested() { prepareEditor(null); }

    private void quickAdd() {
        String title = text(binding.taskQuickAddInput);
        if (title.isEmpty()) {
            prepareEditor(null);
            return;
        }
        FamilyTask task = new FamilyTask();
        task.title = title;
        task.dueAt = quickDateMode == 2 && quickCustomDueAt > 0L
                ? quickCustomDueAt : quickDueAt(quickDateMode == 1 ? 1 : 0);
        task.priority = quickPriorityMode == 2 ? FamilyTask.PRIORITY_URGENT
                : quickPriorityMode == 1 ? FamilyTask.PRIORITY_HIGH
                : FamilyTask.PRIORITY_NORMAL;
        task.repeatType = quickRepeatMode == 1
                ? FamilyTask.REPEAT_DAILY : FamilyTask.REPEAT_NONE;
        repository.save(task, () -> {
            if (binding != null) {
                binding.taskQuickAddInput.setText("");
                reload();
            }
        });
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
                long filterDate = completed && task.completedAt > 0L
                        ? task.completedAt : (completed ? task.updatedAt : task.dueAt);
                boolean dateMatches = activeFilter == R.id.task_filter_all
                        || (filterDate >= range[0] && filterDate < range[1]);
                if (customDateStart > 0L) {
                    dateMatches = filterDate >= customDateStart && filterDate < customDateEnd;
                }
                boolean assignmentMatches = matchesAssignment(task);
                if (overdueOnly) dateMatches = !completed && task.dueAt < startOfToday();
                if (statusMatches && dateMatches && assignmentMatches) visible.add(task);
            }
            sortVisibleTasks(visible);
            adapter.submitList(visible);
            binding.taskResultSummary.setText(getString(
                    R.string.family_tasks_result_count, visible.size(), pending));
            binding.taskEmptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
            binding.taskRecyclerView.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void sortVisibleTasks(@NonNull List<FamilyTask> tasks) {
        Comparator<FamilyTask> dueComparator = Comparator
                .comparingLong((FamilyTask task) -> task.dueAt)
                .thenComparing(task -> task.title.toLowerCase(Locale.getDefault()));
        if (activeSort == SORT_PRIORITY) {
            Collections.sort(tasks, Comparator
                    .comparingInt(this::priorityRank)
                    .thenComparingLong(task -> task.dueAt));
        } else if (activeSort == SORT_NEWEST) {
            Collections.sort(tasks, (left, right) -> Long.compare(right.updatedAt, left.updatedAt));
        } else if (activeSort == SORT_TITLE) {
            Collections.sort(tasks, Comparator.comparing(
                    task -> task.title.toLowerCase(Locale.getDefault())));
        } else {
            Collections.sort(tasks, dueComparator);
        }
    }

    private int priorityRank(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return 0;
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return 1;
        return 2;
    }

    private void pickCalendarDay() {
        Calendar selected = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
            selected.set(year, month, day, 0, 0, 0);
            selected.set(Calendar.MILLISECOND, 0);
            customDateStart = selected.getTimeInMillis();
            selected.add(Calendar.DAY_OF_YEAR, 1);
            customDateEnd = selected.getTimeInMillis();
            overdueOnly = false;
            activeFilter = R.id.task_filter_all;
            binding.taskDueCalendarButton.setText(getString(
                    R.string.family_tasks_calendar_date,
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(new Date(customDateStart))));
            syncPremiumFilterControls();
            resetTaskScroll();
            reload();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean matchesAssignment(@NonNull FamilyTask task) {
        if (activeAssignment == R.id.task_assignment_everyone) return true;
        if (activeAssignment == R.id.task_assignment_family) {
            return task.assignedMemberId.isEmpty() && task.assignedMemberName.isEmpty();
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
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.setTimeInMillis(todayStart);
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        long tomorrowStart = tomorrow.getTimeInMillis();
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
        overdueOnly = overdue;
        activeFilter = dateFilter;
        activeStatus = statusFilter;
        customDateStart = 0L;
        customDateEnd = 0L;
        binding.taskDueCalendarButton.setText(R.string.family_tasks_due_calendar);
        syncPremiumFilterControls();
        resetTaskScroll();
        reload();
    }

    private long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void prepareEditor(@Nullable FamilyTask existing) {
        memberRepository.loadMembers("", members -> {
            if (binding != null) showEditor(existing, members);
        });
    }

    private void showEditor(@Nullable FamilyTask existing, @NonNull List<FamilyMember> members) {
        DialogFamilyTaskBinding form = DialogFamilyTaskBinding.inflate(getLayoutInflater());
        FamilyTask task = existing == null ? new FamilyTask() : existing;
        long[] dueAt = {task.dueAt > 0 ? task.dueAt : defaultDueTime()};
        String[] priorities = {getString(R.string.task_priority_normal),
                getString(R.string.task_priority_high), getString(R.string.task_priority_urgent)};
        String[] priorityValues = {FamilyTask.PRIORITY_NORMAL,
                FamilyTask.PRIORITY_HIGH, FamilyTask.PRIORITY_URGENT};
        String[] repeats = {getString(R.string.task_repeat_none),
                getString(R.string.task_repeat_daily), getString(R.string.task_repeat_weekly),
                getString(R.string.task_repeat_monthly)};
        String[] repeatValues = {FamilyTask.REPEAT_NONE, FamilyTask.REPEAT_DAILY,
                FamilyTask.REPEAT_WEEKLY, FamilyTask.REPEAT_MONTHLY};
        String[] reminderLabels = {getString(R.string.task_reminder_at_time),
                getString(R.string.task_reminder_5_minutes),
                getString(R.string.task_reminder_15_minutes),
                getString(R.string.task_reminder_30_minutes),
                getString(R.string.task_reminder_1_hour),
                getString(R.string.task_reminder_1_day)};
        int[] reminderValues = {0, 5, 15, 30, 60, 1440};
        String[] groceryCategories = GroceryOptionCatalog.categoryLabels(requireContext());
        String[] groceryLists = {getString(R.string.grocery_filter_daily), "Weekly",
                "Fortnightly", getString(R.string.grocery_filter_monthly)};
        String[] groceryListValues = {GroceryItem.LIST_DAILY, GroceryItem.LIST_WEEKLY,
                GroceryItem.LIST_FORTNIGHTLY, GroceryItem.LIST_MONTHLY};
        List<String> memberNames = new ArrayList<>();
        memberNames.add(getString(R.string.family_tasks_whole_family));
        for (FamilyMember member : members) memberNames.add(member.name);

        form.taskPriorityInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, priorities));
        form.taskRepeatInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, repeats));
        form.taskMemberInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, memberNames));
        form.taskReminderLeadInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, reminderLabels));
        form.taskGroceryCategoryInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, groceryCategories));
        form.taskGroceryListInput.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, groceryLists));

        form.taskDialogTitle.setText(existing == null
                ? R.string.family_tasks_add : R.string.family_tasks_edit);
        form.saveTaskButton.setText(existing == null
                ? R.string.family_tasks_save : R.string.family_tasks_update);
        form.taskTitleInput.setText(task.title);
        form.taskTitleLayout.setEndIconOnClickListener(v -> requestVoiceCapture(form.taskTitleInput));
        form.taskNotesInput.setText(task.notes);
        form.taskPriorityInput.setText(priorities[indexOf(priorityValues, task.priority)], false);
        form.taskRepeatInput.setText(repeats[indexOf(repeatValues, task.repeatType)], false);
        form.taskMemberInput.setText(task.assignedMemberName.isEmpty()
                ? memberNames.get(0) : task.assignedMemberName, false);

        boolean alreadyLinked = !task.linkedGroceryCloudId.isEmpty()
                || task.linkedGroceryItemId > 0L;
        form.taskGrocerySwitch.setChecked(alreadyLinked);
        form.taskGrocerySwitch.setEnabled(!alreadyLinked);
        if (alreadyLinked) form.taskGrocerySwitch.setText(R.string.family_tasks_linked_to_grocery);
        form.taskGroceryOptions.setVisibility(View.GONE);
        form.taskGroceryCategoryInput.setText(groceryCategories.length == 0
                ? getString(R.string.grocery_uncategorized) : groceryCategories[0], false);
        form.taskGroceryListInput.setText(groceryLists[0], false);
        form.taskGrocerySwitch.setOnCheckedChangeListener((button, checked) ->
                form.taskGroceryOptions.setVisibility(checked && !alreadyLinked
                        ? View.VISIBLE : View.GONE));

        form.taskReminderSwitch.setChecked(task.reminderEnabled || existing == null);
        int reminderIndex = indexOf(reminderValues, task.reminderMinutesBefore);
        form.taskReminderLeadInput.setText(reminderLabels[reminderIndex], false);
        form.taskReminderLeadLayout.setVisibility(form.taskReminderSwitch.isChecked()
                ? View.VISIBLE : View.GONE);
        form.taskReminderSwitch.setOnCheckedChangeListener((button, checked) ->
                form.taskReminderLeadLayout.setVisibility(checked ? View.VISIBLE : View.GONE));

        updateDueText(form, dueAt[0]);
        form.taskDueInput.setOnClickListener(v -> pickDateTime(dueAt[0], selected -> {
            dueAt[0] = selected;
            updateDueText(form, selected);
        }));
        form.taskDueToday.setOnClickListener(v -> setSmartDue(form, dueAt, 0));
        form.taskDueTomorrow.setOnClickListener(v -> setSmartDue(form, dueAt, 1));
        form.taskDueNextWeek.setOnClickListener(v -> pickDateTime(dueAt[0], selected -> {
            dueAt[0] = selected;
            updateDueText(form, selected);
        }));

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot()).create();
        form.cancelTaskButton.setOnClickListener(v -> dialog.dismiss());
        form.saveTaskButton.setOnClickListener(v -> {
            String title = text(form.taskTitleInput);
            if (title.isEmpty()) {
                form.taskTitleLayout.setError(getString(R.string.family_tasks_required));
                return;
            }
            task.title = title;
            task.notes = text(form.taskNotesInput);
            task.dueAt = dueAt[0];
            task.priority = priorityValues[Math.max(0,
                    indexOf(priorities, text(form.taskPriorityInput)))];
            task.repeatType = repeatValues[Math.max(0,
                    indexOf(repeats, text(form.taskRepeatInput)))];
            String memberName = text(form.taskMemberInput);
            task.assignedMemberName = memberName.equals(memberNames.get(0)) ? "" : memberName;
            task.assignedMemberId = "";
            boolean validMember = task.assignedMemberName.isEmpty();
            for (FamilyMember member : members) {
                if (member.name.equals(task.assignedMemberName)) {
                    task.assignedMemberId = member.cloudUid.isEmpty()
                            ? member.cloudProfileId : member.cloudUid;
                    validMember = true;
                    break;
                }
            }
            if (!validMember) {
                task.assignedMemberName = "";
                task.assignedMemberId = "";
            }
            task.reminderEnabled = form.taskReminderSwitch.isChecked();
            task.reminderMinutesBefore = reminderValues[indexOf(
                    reminderLabels, text(form.taskReminderLeadInput))];

            Runnable persistTask = () -> repository.save(task, () -> {
                FamilyTaskScheduler.schedule(requireContext(), task);
                dialog.dismiss();
                reload();
            });
            if (form.taskGrocerySwitch.isChecked() && !alreadyLinked) {
                GroceryItem grocery = new GroceryItem();
                grocery.name = task.title;
                grocery.category = text(form.taskGroceryCategoryInput);
                grocery.quantity = "1 pcs";
                grocery.priority = task.priority;
                grocery.listType = groceryListValues[indexOf(groceryLists,
                        text(form.taskGroceryListInput))];
                grocery.isMonthlyMaster = !GroceryItem.LIST_DAILY.equals(grocery.listType);
                grocery.assignedMemberId = task.assignedMemberId;
                grocery.assignedMemberName = task.assignedMemberName;
                grocery.notes = task.notes;
                new GroceryRepository(requireContext()).save(grocery, () -> {
                    task.linkedGroceryItemId = grocery.id;
                    task.linkedGroceryCloudId = grocery.cloudId;
                    persistTask.run();
                });
            } else {
                persistTask.run();
            }
        });
        dialog.setOnDismissListener(ignored -> stopVoiceCapture());
        dialog.show();
    }

    private void confirmDelete(FamilyTask task) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.family_tasks_delete_title)
                .setMessage(R.string.family_tasks_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    FamilyTaskScheduler.cancel(requireContext(), task.id);
                    repository.delete(task, this::reload);
                }).show();
    }

    private long defaultDueTime() {
        Calendar c = Calendar.getInstance();
        if (activeFilter == R.id.task_filter_tomorrow) c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 18);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long quickDueAt(int daysAhead) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, daysAhead);
        c.set(Calendar.HOUR_OF_DAY, 18);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void setSmartDue(@NonNull DialogFamilyTaskBinding form,
                             @NonNull long[] dueAt, int daysAhead) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, daysAhead);
        c.set(Calendar.HOUR_OF_DAY, 18);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        dueAt[0] = c.getTimeInMillis();
        updateDueText(form, dueAt[0]);
    }

    private void requestVoiceCapture(@NonNull android.widget.EditText target) {
        pendingVoiceTarget = target;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startVoiceCapture(target);
    }

    private void startVoiceCapture(@NonNull android.widget.EditText target) {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            android.widget.Toast.makeText(requireContext(),
                    R.string.family_tasks_voice_unavailable, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        stopVoiceCapture();
        pendingVoiceTarget = target;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { toast(R.string.family_tasks_voice_listening); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                stopVoiceCapture();
                toast(error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? R.string.family_tasks_voice_no_match
                        : R.string.family_tasks_voice_unavailable);
            }
            @Override public void onResults(Bundle results) {
                if (applyVoiceResult(results, target)) toast(R.string.family_tasks_voice_added);
                else toast(R.string.family_tasks_voice_no_match);
                stopVoiceCapture();
            }
            @Override public void onPartialResults(Bundle results) { applyVoiceResult(results, target); }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizer.startListening(intent);
    }

    private boolean applyVoiceResult(@Nullable Bundle results,
                                     @NonNull android.widget.EditText target) {
        if (results == null) return false;
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null
                || matches.get(0).trim().isEmpty()) return false;
        target.setText(matches.get(0).trim());
        target.setSelection(target.length());
        return true;
    }

    private void toast(int message) {
        if (isAdded()) android.widget.Toast.makeText(requireContext(), message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        pendingVoiceTarget = null;
    }

    private long[] activeRange() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        boolean completed = activeStatus == R.id.task_status_completed;
        if (activeFilter == R.id.task_filter_tomorrow) {
            start.add(Calendar.DAY_OF_YEAR, completed ? -1 : 1);
            end.add(Calendar.DAY_OF_YEAR, completed ? 0 : 2);
        } else if (activeFilter == R.id.task_filter_week) {
            if (completed) {
                start.add(Calendar.DAY_OF_YEAR, -6);
                end.add(Calendar.DAY_OF_YEAR, 1);
            } else {
                end.add(Calendar.DAY_OF_YEAR, 7);
            }
        } else if (activeFilter == R.id.task_filter_15_days) {
            if (completed) {
                start.add(Calendar.DAY_OF_YEAR, -14);
                end.add(Calendar.DAY_OF_YEAR, 1);
            } else {
                end.add(Calendar.DAY_OF_YEAR, 15);
            }
        } else if (activeFilter == R.id.task_filter_month) {
            if (completed) {
                start.add(Calendar.DAY_OF_YEAR, -29);
                end.add(Calendar.DAY_OF_YEAR, 1);
            } else {
                end.add(Calendar.DAY_OF_YEAR, 30);
            }
        } else {
            end.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    private interface DateCallback { void onSelected(long value); }

    private void pickDateTime(long initial, DateCallback callback) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        new DatePickerDialog(requireContext(), (picker, y, m, d) -> {
            c.set(y, m, d);
            new TimePickerDialog(requireContext(), (time, h, minute) -> {
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, minute);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                callback.onSelected(c.getTimeInMillis());
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDueText(DialogFamilyTaskBinding form, long value) {
        form.taskDueInput.setText(DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(value)));
    }

    private static String text(android.widget.TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(target)) return i;
        return 0;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        stopVoiceCapture();
        if (repository != null) repository.stopRealtimeSync();
        taskDateDropdown = null;
        taskStatusDropdown = null;
        taskAssignmentDropdown = null;
        taskCategoryCollapseButton = null;
        binding = null;
        super.onDestroyView();
    }
}
