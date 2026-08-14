package com.tridev.familyhub.feature.vehicle;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.VehicleWithOwner;
import com.tridev.familyhub.data.repository.VehicleRepository;
import com.tridev.familyhub.databinding.DialogVehicleBinding;
import com.tridev.familyhub.databinding.FragmentVehicleBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Offline-first family Vehicle Management screen. */
public class VehicleFragment extends Fragment implements AddActionHost {

    private static final String[] VEHICLE_TYPES = {
            Vehicle.TYPE_CAR,
            Vehicle.TYPE_MOTORCYCLE,
            Vehicle.TYPE_SCOOTER,
            Vehicle.TYPE_BICYCLE,
            Vehicle.TYPE_COMMERCIAL,
            Vehicle.TYPE_OTHER
    };

    private FragmentVehicleBinding binding;
    private VehicleRepository repository;
    private VehicleAdapter adapter;
    private final List<VehicleWithOwner> loadedVehicles =
            new ArrayList<>();
    @NonNull
    private String selectedFilter = "ALL";
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentVehicleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        repository = new VehicleRepository(requireContext());
        adapter = new VehicleAdapter(
                new VehicleAdapter.VehicleActionListener() {
                    @Override
                    public void onEdit(@NonNull VehicleWithOwner item) {
                        prepareEditor(item);
                    }

                    @Override
                    public void onDelete(@NonNull VehicleWithOwner item) {
                        confirmDelete(item);
                    }
                }
        );

        binding.vehicleRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.vehicleRecyclerView.setAdapter(adapter);
        binding.vehicleBackButton.setOnClickListener(clickedView -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).showFeatureMenu();
            }
        });
        binding.vehicleNotificationButton.setOnClickListener(clickedView ->
                ((MainActivity) requireActivity()).openTab(R.id.nav_reminders));
        binding.vehicleProfileButton.setOnClickListener(clickedView ->
                ((MainActivity) requireActivity()).openProfile());
        binding.emptyAddVehicleButton.setOnClickListener(
                clickedView -> prepareEditor(null)
        );
        binding.vehicleFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    int checkedId = checkedIds.isEmpty()
                            ? R.id.vehicle_filter_all
                            : checkedIds.get(0);
                    if (checkedId == R.id.vehicle_filter_cars) {
                        selectedFilter = "CARS";
                    } else if (checkedId
                            == R.id.vehicle_filter_two_wheelers) {
                        selectedFilter = "TWO_WHEELERS";
                    } else if (checkedId == R.id.vehicle_filter_due) {
                        selectedFilter = "DUE";
                    } else {
                        selectedFilter = "ALL";
                    }
                    applyVehicleFilter();
                }
        );

        binding.vehicleSearchInput.addTextChangedListener(
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
                        loadVehicles(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
        loadVehicles("");
        repository.startRealtimeSync(() -> {
            if (binding != null) loadVehicles(currentQuery());
        });
    }

    @Override
    public void onAddRequested() {
        prepareEditor(null);
    }

    private void prepareEditor(@Nullable VehicleWithOwner existing) {
        repository.loadMembers(members -> {
            if (binding == null) {
                return;
            }
            if (members.isEmpty()) {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.vehicle_add_member_first,
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
            @Nullable VehicleWithOwner existing
    ) {
        DialogVehicleBinding form =
                DialogVehicleBinding.inflate(getLayoutInflater());
        Vehicle vehicle = existing == null
                ? new Vehicle()
                : existing.vehicle;

        List<String> memberNames = new ArrayList<>();
        for (FamilyMember member : members) {
            memberNames.add(member.name);
        }
        form.vehicleOwnerInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                memberNames
        ));

        String[] typeLabels = getResources().getStringArray(
                R.array.vehicle_type_labels
        );
        form.vehicleTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                typeLabels
        ));
        String[] fuelLabels = getResources().getStringArray(
                R.array.vehicle_fuel_labels
        );
        form.vehicleFuelInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                fuelLabels
        ));
        List<String> documentTitles = new ArrayList<>();
        documentTitles.add(getString(R.string.vehicle_no_linked_document));
        for (DocumentEntry document : documents) documentTitles.add(document.title);
        form.vehicleLinkedDocumentInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                documentTitles
        ));

        long[] selectedDates = {
                vehicle.insuranceExpiryAt,
                vehicle.pollutionExpiryAt,
                vehicle.serviceDueAt
        };

        if (existing == null) {
            form.vehicleOwnerInput.setText(members.get(0).name, false);
            form.vehicleTypeInput.setText(typeLabels[0], false);
            form.vehicleFuelInput.setText(fuelLabels[0], false);
            form.vehicleLinkedDocumentInput.setText(
                    getString(R.string.vehicle_no_linked_document), false);
        } else {
            form.vehicleDialogTitle.setText(R.string.vehicle_edit);
            form.vehicleOwnerInput.setText(existing.ownerName, false);
            form.vehicleTypeInput.setText(
                    displayType(vehicle.vehicleType),
                    false
            );
            form.vehicleNameInput.setText(vehicle.displayName);
            form.vehicleRegistrationInput.setText(vehicle.registrationNumber);
            form.vehicleManufacturerInput.setText(vehicle.manufacturer);
            form.vehicleModelInput.setText(vehicle.model);
            form.vehicleFuelInput.setText(vehicle.fuelType, false);
            if (vehicle.manufactureYear > 0) {
                form.vehicleYearInput.setText(String.valueOf(
                        vehicle.manufactureYear
                ));
            }
            form.vehicleNotesInput.setText(vehicle.notes);
            form.vehicleLinkedDocumentInput.setText(
                    vehicle.linkedDocumentTitle.isEmpty()
                            ? getString(R.string.vehicle_no_linked_document)
                            : vehicle.linkedDocumentTitle,
                    false
            );
            form.vehicleTimelineNoteInput.setText(vehicle.timelineNote);
            form.vehicleSharedSwitch.setChecked(vehicle.isShared);
        }

        bindDateField(
                form.vehicleInsuranceInput,
                selectedDates,
                0
        );
        bindDateField(
                form.vehiclePollutionInput,
                selectedDates,
                1
        );
        bindDateField(
                form.vehicleServiceInput,
                selectedDates,
                2
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot())
                .create();
        form.cancelVehicleButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        form.saveVehicleButton.setOnClickListener(clickedView -> {
            FamilyMember owner = findMember(
                    members,
                    textOf(form.vehicleOwnerInput)
            );
            int typeIndex = findTypeIndex(
                    typeLabels,
                    textOf(form.vehicleTypeInput)
            );
            String displayName = textOf(form.vehicleNameInput);
            String registration = textOf(
                    form.vehicleRegistrationInput
            ).toUpperCase(Locale.ROOT).replace(" ", "");

            if (owner == null) {
                form.vehicleOwnerLayout.setError(
                        getString(R.string.vehicle_owner_required)
                );
                return;
            }
            form.vehicleOwnerLayout.setError(null);

            if (typeIndex < 0) {
                form.vehicleTypeLayout.setError(
                        getString(R.string.vehicle_type_required)
                );
                return;
            }
            form.vehicleTypeLayout.setError(null);

            if (displayName.isEmpty()) {
                form.vehicleNameLayout.setError(
                        getString(R.string.vehicle_name_required)
                );
                return;
            }
            form.vehicleNameLayout.setError(null);

            if (registration.isEmpty()) {
                form.vehicleRegistrationLayout.setError(
                        getString(R.string.vehicle_registration_required)
                );
                return;
            }
            form.vehicleRegistrationLayout.setError(null);

            vehicle.ownerMemberId = owner.id;
            vehicle.assignedOwnerName = owner.name;
            vehicle.vehicleType = VEHICLE_TYPES[typeIndex];
            vehicle.displayName = displayName;
            vehicle.registrationNumber = registration;
            vehicle.manufacturer = textOf(form.vehicleManufacturerInput);
            vehicle.model = textOf(form.vehicleModelInput);
            vehicle.fuelType = textOf(form.vehicleFuelInput);
            vehicle.manufactureYear = parseYear(
                    textOf(form.vehicleYearInput)
            );
            vehicle.insuranceExpiryAt = selectedDates[0];
            vehicle.pollutionExpiryAt = selectedDates[1];
            vehicle.serviceDueAt = selectedDates[2];
            vehicle.notes = textOf(form.vehicleNotesInput);
            DocumentEntry document = findDocument(
                    documents, textOf(form.vehicleLinkedDocumentInput));
            vehicle.linkedDocumentId = document == null ? 0L : document.id;
            vehicle.linkedDocumentTitle = document == null ? "" : document.title;
            vehicle.timelineNote = textOf(form.vehicleTimelineNoteInput);
            vehicle.isShared = form.vehicleSharedSwitch.isChecked();
            vehicle.updatedAt = System.currentTimeMillis();

            repository.save(vehicle, successful -> {
                if (binding == null) {
                    return;
                }
                if (!successful) {
                    form.vehicleRegistrationLayout.setError(
                            getString(R.string.vehicle_duplicate_registration)
                    );
                    return;
                }
                dialog.dismiss();
                loadVehicles(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.vehicle_added
                                : R.string.vehicle_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    @Nullable
    private DocumentEntry findDocument(@NonNull List<DocumentEntry> documents,
                                       @NonNull String title) {
        for (DocumentEntry document : documents) {
            if (document.title.equalsIgnoreCase(title)) return document;
        }
        return null;
    }

    private void bindDateField(
            @NonNull android.widget.EditText input,
            @NonNull long[] dates,
            int index
    ) {
        if (dates[index] > 0L) {
            input.setText(dateFormat.format(dates[index]));
        }
        input.setOnClickListener(clickedView -> {
            Calendar calendar = Calendar.getInstance();
            if (dates[index] > 0L) {
                calendar.setTimeInMillis(dates[index]);
            }
            new DatePickerDialog(
                    requireContext(),
                    (picker, year, month, day) -> {
                        calendar.set(Calendar.YEAR, year);
                        calendar.set(Calendar.MONTH, month);
                        calendar.set(Calendar.DAY_OF_MONTH, day);
                        dates[index] = calendar.getTimeInMillis();
                        input.setText(dateFormat.format(calendar.getTime()));
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
            ).show();
        });
    }

    private int parseYear(@NonNull String value) {
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
        for (int index = 0; index < VEHICLE_TYPES.length; index++) {
            if (VEHICLE_TYPES[index].equals(storedType)) {
                return getResources().getStringArray(
                        R.array.vehicle_type_labels
                )[index];
            }
        }
        return getString(R.string.vehicle_type_other);
    }

    private void confirmDelete(@NonNull VehicleWithOwner item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vehicle_delete_title)
                .setMessage(getString(
                        R.string.vehicle_delete_message,
                        item.vehicle.displayName
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(item.vehicle, successful -> {
                            if (binding == null) {
                                return;
                            }
                            if (successful) {
                                loadVehicles(currentQuery());
                                Snackbar.make(
                                        binding.getRoot(),
                                        R.string.vehicle_removed,
                                        Snackbar.LENGTH_SHORT
                                ).show();
                            }
                        })
                )
                .show();
    }

    private void loadVehicles(@NonNull String query) {
        repository.loadVehicles(query, vehicles -> {
            if (binding == null) {
                return;
            }
            loadedVehicles.clear();
            loadedVehicles.addAll(vehicles);
            applyVehicleFilter();
        });
    }

    private void applyVehicleFilter() {
        if (binding == null) {
            return;
        }

        List<VehicleWithOwner> visible = new ArrayList<>();
        int overdueCount = 0;
        int dueSoonCount = 0;
        int serviceCount = 0;
        long now = System.currentTimeMillis();
        long dueLimit = now + 30L * 24L * 60L * 60L * 1000L;

        for (VehicleWithOwner item : loadedVehicles) {
            Vehicle vehicle = item.vehicle;
            boolean overdue = hasDateBefore(vehicle, now);
            boolean dueSoon = hasDateBetween(vehicle, now, dueLimit);
            boolean serviceDue = vehicle.serviceDueAt > 0L
                    && vehicle.serviceDueAt <= dueLimit;

            if (overdue) {
                overdueCount++;
            }
            if (dueSoon) {
                dueSoonCount++;
            }
            if (serviceDue) {
                serviceCount++;
            }

            boolean matches;
            if ("CARS".equals(selectedFilter)) {
                matches = Vehicle.TYPE_CAR.equals(vehicle.vehicleType);
            } else if ("TWO_WHEELERS".equals(selectedFilter)) {
                matches = Vehicle.TYPE_MOTORCYCLE.equals(
                        vehicle.vehicleType
                ) || Vehicle.TYPE_SCOOTER.equals(vehicle.vehicleType)
                        || Vehicle.TYPE_BICYCLE.equals(
                        vehicle.vehicleType
                );
            } else if ("DUE".equals(selectedFilter)) {
                matches = overdue || dueSoon;
            } else {
                matches = true;
            }
            if (matches) {
                visible.add(item);
            }
        }

        binding.vehicleTotalValue.setText(
                String.valueOf(loadedVehicles.size())
        );
        binding.vehicleDueSoonValue.setText(
                String.valueOf(dueSoonCount)
        );
        binding.vehicleOverdueValue.setText(
                String.valueOf(overdueCount)
        );
        binding.vehicleServiceValue.setText(
                String.valueOf(serviceCount)
        );

        adapter.submitList(visible);
        boolean isEmpty = visible.isEmpty();
        binding.vehicleRecyclerView.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.vehicleEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );
    }

    private boolean hasDateBefore(
            @NonNull Vehicle vehicle,
            long time
    ) {
        long[] dates = {
                vehicle.insuranceExpiryAt,
                vehicle.pollutionExpiryAt,
                vehicle.serviceDueAt
        };
        for (long date : dates) {
            if (date > 0L && date < time) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDateBetween(
            @NonNull Vehicle vehicle,
            long start,
            long end
    ) {
        long[] dates = {
                vehicle.insuranceExpiryAt,
                vehicle.pollutionExpiryAt,
                vehicle.serviceDueAt
        };
        for (long date : dates) {
            if (date >= start && date <= end) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.vehicleSearchInput);
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
        binding.vehicleRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
