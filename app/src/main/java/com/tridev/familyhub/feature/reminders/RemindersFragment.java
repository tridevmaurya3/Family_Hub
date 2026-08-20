package com.tridev.familyhub.feature.reminders;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
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

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Create, manage, and schedule private local reminders. */
public class RemindersFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 501;

    private FragmentRemindersBinding binding;
    private ReminderAdapter reminderAdapter;
    private ReminderRepository repository;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRemindersBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.remindersOverview.setNavigationAction(R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title,
                clickedView -> ((MainActivity) requireActivity()).showFeatureMenu());
        repository = new ReminderRepository(requireContext());
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
        loadReminders("");
    }

    @Override
    public void onAddRequested() {
        showReminderEditor(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            loadReminders(binding.reminderSearchInput.getText().toString());
        }
    }

    private void loadReminders(String query) {
        repository.loadReminders(query, reminders -> {
            if (binding == null) {
                return;
            }
            reminderAdapter.submitList(reminders);
            binding.reminderDashboardSummary.setReminders(reminders);
            boolean isEmpty = reminders.isEmpty();
            binding.reminderRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            binding.remindersEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        });
    }

    private void showReminderEditor(@Nullable Reminder existingReminder) {
        DialogReminderBinding dialogBinding = DialogReminderBinding.inflate(getLayoutInflater());
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
        boolean isNewReminder = existingReminder == null;
        Calendar selectedTime = Calendar.getInstance();
        selectedTime.add(Calendar.HOUR_OF_DAY, 1);
        selectedTime.set(Calendar.SECOND, 0);
        selectedTime.set(Calendar.MILLISECOND, 0);

        dialogBinding.reminderEditorTitle.setText(isNewReminder
                ? R.string.add_reminder_title
                : R.string.edit_reminder_title);
        if (!isNewReminder) {
            selectedTime.setTimeInMillis(existingReminder.reminderAt);
            dialogBinding.reminderTitleInput.setText(existingReminder.title);
            dialogBinding.reminderNoteInput.setText(existingReminder.note);
            dialogBinding.reminderPriorityInput.setText(existingReminder.priority, false);
            dialogBinding.reminderCategoryInput.setText(existingReminder.category, false);
            dialogBinding.reminderAssigneeInput.setText(
                    existingReminder.assignedMemberName.isEmpty()
                            ? "Unassigned" : existingReminder.assignedMemberName, false);
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

            Reminder reminder = isNewReminder ? new Reminder() : existingReminder;
            reminder.title = title;
            reminder.note = dialogBinding.reminderNoteInput.getText().toString().trim();
            reminder.reminderAt = selectedTime.getTimeInMillis();
            reminder.repeatType = repeatType;
            reminder.priority = dialogBinding.reminderPriorityInput.getText().toString().trim();
            reminder.category = dialogBinding.reminderCategoryInput.getText().toString().trim();
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

            repository.save(reminder, savedReminder -> {
                if (binding == null) {
                    return;
                }
                if (savedReminder.isEnabled) {
                    requestNotificationPermissionIfNeeded();
                    ReminderScheduler.schedule(requireContext(), savedReminder);
                } else {
                    ReminderScheduler.cancel(requireContext(), savedReminder.id);
                }
                dialog.dismiss();
                loadReminders(binding.reminderSearchInput.getText().toString());
                Snackbar.make(
                        binding.getRoot(),
                        isNewReminder ? R.string.reminder_added : R.string.reminder_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
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
        new DatePickerDialog(
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
        ).show();
    }

    private void showTimePicker(DialogReminderBinding dialogBinding, Calendar selectedTime) {
        new TimePickerDialog(
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
        ).show();
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

    private void confirmDelete(Reminder reminder) {
        new MaterialAlertDialogBuilder(requireContext())
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
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
            Snackbar.make(binding.getRoot(), R.string.notification_permission_explanation,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroyView() {
        if (repository != null) repository.stopRealtimeSync();
        binding = null;
        super.onDestroyView();
    }
}
