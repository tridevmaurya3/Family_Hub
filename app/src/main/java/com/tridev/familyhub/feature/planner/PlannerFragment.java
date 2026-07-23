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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Offline-first Office-style family calendar and task planner. */
public class PlannerFragment extends Fragment implements AddActionHost {

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

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentPlannerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
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
        ensureNotificationPermission();
        reload();
    }

    @Override
    public void onAddRequested() {
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
        PlannerItem item = existing == null ? new PlannerItem() : existing;
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
                android.R.layout.simple_dropdown_item_1line,
                memberNames
        ));

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
        } else {
            form.plannerDialogTitle.setText(R.string.planner_edit);
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
            repository.save(item, () -> {
                if (binding != null) {
                    if (item.isReminderEnabled) {
                        PlannerScheduler.schedule(requireContext(), item);
                    } else {
                        PlannerScheduler.cancel(requireContext(), item.id);
                    }
                    dialog.dismiss();
                    reload();
                    Snackbar.make(
                            binding.getRoot(),
                            existing == null
                                    ? R.string.planner_added
                                    : R.string.planner_updated,
                            Snackbar.LENGTH_SHORT
                    ).show();
                }
            });
        });
        dialog.show();
    }

    private ArrayAdapter<String> dropdown(@NonNull String[] labels) {
        return new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                labels
        );
    }

    private interface DateTimeCallback {
        void onSelected(long value);
    }

    private void pickDateTime(long initial, @NonNull DateTimeCallback callback) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(initial);
        new DatePickerDialog(
                requireContext(),
                (picker, year, month, day) -> {
                    calendar.set(year, month, day);
                    new TimePickerDialog(
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
                    ).show();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
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
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.planner_delete_title)
                .setMessage(getString(
                        R.string.planner_delete_message, item.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    PlannerScheduler.cancel(requireContext(), item.id);
                    repository.delete(item, this::reload);
                }).show();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void reload() {
        if (binding == null) {
            return;
        }
        repository.loadAll(textOf(binding.plannerSearchInput), items -> {
            if (binding == null) {
                return;
            }
            adapter.submitList(items);
            int open = 0;
            for (PlannerItem item : items) {
                if (!item.isCompleted) {
                    open++;
                }
            }
            binding.plannerOpenValue.setText(String.valueOf(open));
            boolean empty = items.isEmpty();
            binding.plannerRecyclerView.setVisibility(
                    empty ? View.GONE : View.VISIBLE
            );
            binding.plannerEmptyState.setVisibility(
                    empty ? View.VISIBLE : View.GONE
            );
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

    @Override
    public void onDestroyView() {
        binding.plannerRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
