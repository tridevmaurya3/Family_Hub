package com.tridev.familyhub.feature.health;

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
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.HealthRecordWithMember;
import com.tridev.familyhub.data.repository.HealthRepository;
import com.tridev.familyhub.databinding.DialogHealthRecordBinding;
import com.tridev.familyhub.databinding.FragmentHealthBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Offline-first Health Records screen for the Family Hub. */
public class HealthFragment extends Fragment implements AddActionHost {

    private static final String[] RECORD_TYPES = {
            HealthRecord.TYPE_MEDICINE,
            HealthRecord.TYPE_CONDITION,
            HealthRecord.TYPE_ALLERGY,
            HealthRecord.TYPE_MEASUREMENT,
            HealthRecord.TYPE_APPOINTMENT,
            HealthRecord.TYPE_VACCINATION,
            HealthRecord.TYPE_OTHER
    };
    private static final int[] REMINDER_MINUTES = {30, 60, 360, 1440, 2880};

    private FragmentHealthBinding binding;
    private HealthRepository repository;
    private HealthRecordAdapter adapter;
    private final List<HealthRecordWithMember> loadedRecords =
            new ArrayList<>();
    @NonNull
    private String selectedRecordType = "";
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentHealthBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        repository = new HealthRepository(requireContext());
        adapter = new HealthRecordAdapter(
                new HealthRecordAdapter.RecordActionListener() {
                    @Override
                    public void onEdit(@NonNull HealthRecordWithMember item) {
                        prepareEditor(item);
                    }

                    @Override
                    public void onDelete(@NonNull HealthRecordWithMember item) {
                        confirmDelete(item);
                    }
                }
        );

        binding.healthRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.healthRecyclerView.setAdapter(adapter);
        binding.emptyAddHealthButton.setOnClickListener(
                clickedView -> prepareEditor(null)
        );
        binding.healthFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    int checkedId = checkedIds.isEmpty()
                            ? R.id.health_filter_all
                            : checkedIds.get(0);
                    if (checkedId == R.id.health_filter_medicine) {
                        selectedRecordType = HealthRecord.TYPE_MEDICINE;
                    } else if (checkedId
                            == R.id.health_filter_appointments) {
                        selectedRecordType = HealthRecord.TYPE_APPOINTMENT;
                    } else if (checkedId == R.id.health_filter_vitals) {
                        selectedRecordType = HealthRecord.TYPE_MEASUREMENT;
                    } else if (checkedId == R.id.health_filter_history) {
                        selectedRecordType = "HISTORY";
                    } else {
                        selectedRecordType = "";
                    }
                    applyRecordFilter();
                }
        );

        binding.healthSearchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after
                    ) {
                        // No action required.
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count
                    ) {
                        loadRecords(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );

        loadRecords("");
        repository.startRealtimeSync(new HealthRepository.RealtimeCallback() {
            @Override public void onChanged(@NonNull HealthRecord record) {
                if (binding != null) loadRecords(currentQuery());
            }

            @Override public void onRemoved(long localId) {
                if (binding != null) loadRecords(currentQuery());
            }
        });
    }

    @Override
    public void onAddRequested() {
        prepareEditor(null);
    }

    private void prepareEditor(@Nullable HealthRecordWithMember existing) {
        repository.loadMembers(members -> {
            if (binding == null) {
                return;
            }
            if (members.isEmpty()) {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.health_add_member_first,
                        Snackbar.LENGTH_LONG
                ).show();
                return;
            }
            repository.loadDocuments(documents -> {
                if (binding != null) showEditor(members, documents, existing);
            });
        });
    }

    private void showEditor(
            @NonNull List<FamilyMember> members,
            @NonNull List<DocumentEntry> documents,
            @Nullable HealthRecordWithMember existing
    ) {
        DialogHealthRecordBinding dialogBinding =
                DialogHealthRecordBinding.inflate(getLayoutInflater());
        HealthRecord record = existing == null
                ? new HealthRecord()
                : existing.record;

        List<String> memberNames = new ArrayList<>();
        for (FamilyMember member : members) {
            memberNames.add(member.name);
        }
        dialogBinding.healthMemberInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                memberNames
        ));

        String[] typeLabels = getResources().getStringArray(
                R.array.health_record_type_labels
        );
        dialogBinding.healthTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                typeLabels
        ));

        List<String> documentTitles = new ArrayList<>();
        documentTitles.add(getString(R.string.health_no_linked_document));
        for (DocumentEntry document : documents) {
            documentTitles.add(document.title);
        }
        dialogBinding.healthLinkedDocumentInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                documentTitles
        ));
        String[] reminderLabels = getResources().getStringArray(
                R.array.health_reminder_labels
        );
        dialogBinding.healthReminderInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                reminderLabels
        ));

        Calendar selectedDate = Calendar.getInstance();
        if (existing != null) {
            dialogBinding.healthDialogTitle.setText(
                    R.string.health_edit_record
            );
            dialogBinding.healthMemberInput.setText(
                    existing.memberName,
                    false
            );
            dialogBinding.healthTypeInput.setText(
                    displayType(record.recordType),
                    false
            );
            dialogBinding.healthTitleInput.setText(record.title);
            dialogBinding.healthValueInput.setText(record.value);
            dialogBinding.healthNotesInput.setText(record.notes);
            dialogBinding.healthLinkedDocumentInput.setText(
                    record.linkedDocumentTitle.isEmpty()
                            ? getString(R.string.health_no_linked_document)
                            : record.linkedDocumentTitle,
                    false
            );
            dialogBinding.healthTimelineNoteInput.setText(record.timelineNote);
            dialogBinding.healthSharedSwitch.setChecked(record.isShared);
            dialogBinding.healthReminderInput.setText(
                    reminderLabels[reminderIndex(record.reminderMinutesBefore)],
                    false
            );
            selectedDate.setTimeInMillis(record.recordedAt);
        } else {
            dialogBinding.healthMemberInput.setText(
                    members.get(0).name,
                    false
            );
            dialogBinding.healthTypeInput.setText(typeLabels[0], false);
            dialogBinding.healthLinkedDocumentInput.setText(
                    getString(R.string.health_no_linked_document),
                    false
            );
            dialogBinding.healthReminderInput.setText(reminderLabels[3], false);
        }
        dialogBinding.healthDateInput.setText(
                dateFormat.format(selectedDate.getTime())
        );
        dialogBinding.healthTimeInput.setText(
                timeFormat.format(selectedDate.getTime())
        );

        dialogBinding.healthDateInput.setOnClickListener(clickedView ->
                new DatePickerDialog(
                        requireContext(),
                        (picker, year, month, day) -> {
                            selectedDate.set(Calendar.YEAR, year);
                            selectedDate.set(Calendar.MONTH, month);
                            selectedDate.set(Calendar.DAY_OF_MONTH, day);
                            dialogBinding.healthDateInput.setText(
                                    dateFormat.format(selectedDate.getTime())
                            );
                        },
                        selectedDate.get(Calendar.YEAR),
                        selectedDate.get(Calendar.MONTH),
                        selectedDate.get(Calendar.DAY_OF_MONTH)
                ).show()
        );
        dialogBinding.healthTimeInput.setOnClickListener(clickedView ->
                new TimePickerDialog(
                        requireContext(),
                        (picker, hour, minute) -> {
                            selectedDate.set(Calendar.HOUR_OF_DAY, hour);
                            selectedDate.set(Calendar.MINUTE, minute);
                            selectedDate.set(Calendar.SECOND, 0);
                            selectedDate.set(Calendar.MILLISECOND, 0);
                            dialogBinding.healthTimeInput.setText(
                                    timeFormat.format(selectedDate.getTime())
                            );
                        },
                        selectedDate.get(Calendar.HOUR_OF_DAY),
                        selectedDate.get(Calendar.MINUTE),
                        false
                ).show()
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();
        dialogBinding.cancelHealthButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        dialogBinding.saveHealthButton.setOnClickListener(clickedView -> {
            FamilyMember selectedMember = findMember(
                    members,
                    textOf(dialogBinding.healthMemberInput)
            );
            int selectedType = findTypeIndex(
                    typeLabels,
                    textOf(dialogBinding.healthTypeInput)
            );
            String title = textOf(dialogBinding.healthTitleInput);

            if (selectedMember == null) {
                dialogBinding.healthMemberLayout.setError(
                        getString(R.string.health_member_required)
                );
                return;
            }
            dialogBinding.healthMemberLayout.setError(null);

            if (selectedType < 0) {
                dialogBinding.healthTypeLayout.setError(
                        getString(R.string.health_type_required)
                );
                return;
            }
            dialogBinding.healthTypeLayout.setError(null);

            if (title.isEmpty()) {
                dialogBinding.healthTitleLayout.setError(
                        getString(R.string.health_title_required)
                );
                return;
            }
            dialogBinding.healthTitleLayout.setError(null);

            record.familyMemberId = selectedMember.id;
            record.assignedMemberName = selectedMember.name;
            record.recordType = RECORD_TYPES[selectedType];
            record.title = title;
            record.value = textOf(dialogBinding.healthValueInput);
            record.notes = textOf(dialogBinding.healthNotesInput);
            record.recordedAt = selectedDate.getTimeInMillis();
            DocumentEntry linkedDocument = findDocument(
                    documents,
                    textOf(dialogBinding.healthLinkedDocumentInput)
            );
            record.linkedDocumentId = linkedDocument == null ? 0L : linkedDocument.id;
            record.linkedDocumentTitle = linkedDocument == null
                    ? ""
                    : linkedDocument.title;
            record.timelineNote = textOf(dialogBinding.healthTimelineNoteInput);
            record.isShared = dialogBinding.healthSharedSwitch.isChecked();
            int reminderIndex = findLabelIndex(
                    reminderLabels,
                    textOf(dialogBinding.healthReminderInput)
            );
            record.reminderMinutesBefore = REMINDER_MINUTES[
                    reminderIndex < 0 ? 3 : reminderIndex
            ];
            record.updatedAt = System.currentTimeMillis();

            repository.save(record, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                requestNotificationPermissionIfNeeded();
                loadRecords(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.health_record_added
                                : R.string.health_record_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    private int reminderIndex(int minutes) {
        for (int index = 0; index < REMINDER_MINUTES.length; index++) {
            if (REMINDER_MINUTES[index] == minutes) return index;
        }
        return 3;
    }

    private int findLabelIndex(@NonNull String[] labels,
                               @NonNull String selected) {
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(selected)) return index;
        }
        return -1;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5201);
        }
    }

    @Nullable
    private DocumentEntry findDocument(
            @NonNull List<DocumentEntry> documents,
            @NonNull String title
    ) {
        for (DocumentEntry document : documents) {
            if (document.title.equalsIgnoreCase(title)) return document;
        }
        return null;
    }

    @Nullable
    private FamilyMember findMember(
            @NonNull List<FamilyMember> members,
            @NonNull String name
    ) {
        for (FamilyMember member : members) {
            if (member.name.equalsIgnoreCase(name)) {
                return member;
            }
        }
        return null;
    }

    private int findTypeIndex(
            @NonNull String[] labels,
            @NonNull String selected
    ) {
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(selected)) {
                return index;
            }
        }
        return -1;
    }

    @NonNull
    private String displayType(@NonNull String storedType) {
        for (int index = 0; index < RECORD_TYPES.length; index++) {
            if (RECORD_TYPES[index].equals(storedType)) {
                return getResources().getStringArray(
                        R.array.health_record_type_labels
                )[index];
            }
        }
        return getString(R.string.health_type_other);
    }

    private void confirmDelete(@NonNull HealthRecordWithMember item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.health_delete_title)
                .setMessage(getString(
                        R.string.health_delete_message,
                        item.record.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(item.record, () -> {
                            if (binding == null) {
                                return;
                            }
                            loadRecords(currentQuery());
                            Snackbar.make(
                                    binding.getRoot(),
                                    R.string.health_record_removed,
                                    Snackbar.LENGTH_SHORT
                            ).show();
                        })
                )
                .show();
    }

    private void loadRecords(@NonNull String query) {
        repository.loadRecords(query, records -> {
            if (binding == null) {
                return;
            }
            loadedRecords.clear();
            loadedRecords.addAll(records);
            applyRecordFilter();
        });
    }

    private void applyRecordFilter() {
        if (binding == null) {
            return;
        }

        List<HealthRecordWithMember> visible = new ArrayList<>();
        int medicineCount = 0;
        int vitalsCount = 0;
        int upcomingCount = 0;
        long now = System.currentTimeMillis();

        for (HealthRecordWithMember item : loadedRecords) {
            String type = item.record.recordType;
            if (HealthRecord.TYPE_MEDICINE.equals(type)) {
                medicineCount++;
            }
            if (HealthRecord.TYPE_MEASUREMENT.equals(type)) {
                vitalsCount++;
            }
            if (HealthRecord.TYPE_APPOINTMENT.equals(type)
                    && item.record.recordedAt >= now) {
                upcomingCount++;
            }

            boolean matches;
            if (selectedRecordType.isEmpty()) {
                matches = true;
            } else if ("HISTORY".equals(selectedRecordType)) {
                matches = !HealthRecord.TYPE_MEDICINE.equals(type)
                        && !HealthRecord.TYPE_MEASUREMENT.equals(type)
                        && !HealthRecord.TYPE_APPOINTMENT.equals(type);
            } else {
                matches = selectedRecordType.equals(type);
            }
            if (matches) {
                visible.add(item);
            }
        }

        binding.healthTotalValue.setText(
                String.valueOf(loadedRecords.size())
        );
        binding.healthMedicineValue.setText(
                String.valueOf(medicineCount)
        );
        binding.healthUpcomingValue.setText(
                String.valueOf(upcomingCount)
        );
        binding.healthVitalsValue.setText(
                String.valueOf(vitalsCount)
        );

        adapter.submitList(visible);
        boolean isEmpty = visible.isEmpty();
        binding.healthRecyclerView.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.healthEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.healthSearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        if (repository != null) repository.stopRealtimeSync();
        binding.healthRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
