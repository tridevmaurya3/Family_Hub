package com.tridev.familyhub.feature.property;

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
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;
import com.tridev.familyhub.data.repository.PropertyRepository;
import com.tridev.familyhub.databinding.DialogPropertyBinding;
import com.tridev.familyhub.databinding.FragmentPropertyBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Offline-first Property Management screen. */
public class PropertyFragment extends Fragment implements AddActionHost {

    private static final String[] PROPERTY_TYPES = {
            PropertyEntry.TYPE_HOUSE,
            PropertyEntry.TYPE_FLAT,
            PropertyEntry.TYPE_LAND,
            PropertyEntry.TYPE_SHOP,
            PropertyEntry.TYPE_OFFICE,
            PropertyEntry.TYPE_AGRICULTURAL,
            PropertyEntry.TYPE_OTHER
    };

    private FragmentPropertyBinding binding;
    private PropertyRepository repository;
    private PropertyAdapter adapter;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentPropertyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        repository = new PropertyRepository(requireContext());
        adapter = new PropertyAdapter(
                new PropertyAdapter.PropertyActionListener() {
                    @Override
                    public void onEdit(@NonNull PropertyWithOwner item) {
                        prepareEditor(item);
                    }

                    @Override
                    public void onDelete(@NonNull PropertyWithOwner item) {
                        confirmDelete(item);
                    }
                }
        );
        binding.propertyRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.propertyRecyclerView.setAdapter(adapter);
        binding.emptyAddPropertyButton.setOnClickListener(
                clickedView -> prepareEditor(null)
        );
        binding.propertySearchInput.addTextChangedListener(
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
                        loadProperties(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
        loadProperties("");
    }

    @Override
    public void onAddRequested() {
        prepareEditor(null);
    }

    private void prepareEditor(@Nullable PropertyWithOwner existing) {
        repository.loadMembers(members -> {
            if (binding == null) {
                return;
            }
            if (members.isEmpty()) {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.property_add_member_first,
                        Snackbar.LENGTH_LONG
                ).show();
                return;
            }
            showEditor(members, existing);
        });
    }

    private void showEditor(
            @NonNull List<FamilyMember> members,
            @Nullable PropertyWithOwner existing
    ) {
        DialogPropertyBinding form =
                DialogPropertyBinding.inflate(getLayoutInflater());
        PropertyEntry property = existing == null
                ? new PropertyEntry()
                : existing.property;

        List<String> memberNames = new ArrayList<>();
        for (FamilyMember member : members) {
            memberNames.add(member.name);
        }
        form.propertyOwnerInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                memberNames
        ));
        String[] typeLabels = getResources().getStringArray(
                R.array.property_type_labels
        );
        form.propertyTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                typeLabels
        ));

        long[] purchaseDate = {property.purchaseDate};
        if (existing == null) {
            form.propertyOwnerInput.setText(members.get(0).name, false);
            form.propertyTypeInput.setText(typeLabels[0], false);
        } else {
            form.propertyDialogTitle.setText(R.string.property_edit);
            form.propertyOwnerInput.setText(existing.ownerName, false);
            form.propertyTypeInput.setText(
                    displayType(property.propertyType),
                    false
            );
            form.propertyTitleInput.setText(property.title);
            form.propertyAddressInput.setText(property.address);
            form.propertyCityInput.setText(property.city);
            form.propertyStateInput.setText(property.state);
            form.propertyPostalInput.setText(property.postalCode);
            form.propertyAreaInput.setText(property.area);
            setNumber(form.propertyPurchaseValueInput, property.purchaseValue);
            setNumber(form.propertyEstimatedValueInput, property.estimatedValue);
            form.propertyRegistrationInput.setText(
                    property.registrationReference
            );
            form.propertyNotesInput.setText(property.notes);
        }
        if (purchaseDate[0] > 0L) {
            form.propertyPurchaseDateInput.setText(
                    dateFormat.format(purchaseDate[0])
            );
        }
        form.propertyPurchaseDateInput.setOnClickListener(clickedView -> {
            Calendar calendar = Calendar.getInstance();
            if (purchaseDate[0] > 0L) {
                calendar.setTimeInMillis(purchaseDate[0]);
            }
            new DatePickerDialog(
                    requireContext(),
                    (picker, year, month, day) -> {
                        calendar.set(Calendar.YEAR, year);
                        calendar.set(Calendar.MONTH, month);
                        calendar.set(Calendar.DAY_OF_MONTH, day);
                        purchaseDate[0] = calendar.getTimeInMillis();
                        form.propertyPurchaseDateInput.setText(
                                dateFormat.format(calendar.getTime())
                        );
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot())
                .create();
        form.cancelPropertyButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        form.savePropertyButton.setOnClickListener(clickedView -> {
            FamilyMember owner = findMember(
                    members,
                    textOf(form.propertyOwnerInput)
            );
            int typeIndex = findTypeIndex(
                    typeLabels,
                    textOf(form.propertyTypeInput)
            );
            String title = textOf(form.propertyTitleInput);

            if (owner == null) {
                form.propertyOwnerLayout.setError(
                        getString(R.string.property_owner_required)
                );
                return;
            }
            form.propertyOwnerLayout.setError(null);
            if (typeIndex < 0) {
                form.propertyTypeLayout.setError(
                        getString(R.string.property_type_required)
                );
                return;
            }
            form.propertyTypeLayout.setError(null);
            if (title.isEmpty()) {
                form.propertyTitleLayout.setError(
                        getString(R.string.property_title_required)
                );
                return;
            }
            form.propertyTitleLayout.setError(null);

            property.ownerMemberId = owner.id;
            property.propertyType = PROPERTY_TYPES[typeIndex];
            property.title = title;
            property.address = textOf(form.propertyAddressInput);
            property.city = textOf(form.propertyCityInput);
            property.state = textOf(form.propertyStateInput);
            property.postalCode = textOf(form.propertyPostalInput);
            property.area = textOf(form.propertyAreaInput);
            property.purchaseValue = parseAmount(
                    textOf(form.propertyPurchaseValueInput)
            );
            property.estimatedValue = parseAmount(
                    textOf(form.propertyEstimatedValueInput)
            );
            property.purchaseDate = purchaseDate[0];
            property.registrationReference = textOf(
                    form.propertyRegistrationInput
            );
            property.notes = textOf(form.propertyNotesInput);

            repository.save(property, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                loadProperties(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.property_added
                                : R.string.property_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    private void setNumber(
            @NonNull android.widget.EditText input,
            double value
    ) {
        if (value > 0) {
            input.setText(String.valueOf(value));
        }
    }

    private double parseAmount(@NonNull String value) {
        try {
            return value.isEmpty() ? 0 : Double.parseDouble(value);
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
        for (int index = 0; index < PROPERTY_TYPES.length; index++) {
            if (PROPERTY_TYPES[index].equals(storedType)) {
                return getResources().getStringArray(
                        R.array.property_type_labels
                )[index];
            }
        }
        return getString(R.string.property_type_other);
    }

    private void confirmDelete(@NonNull PropertyWithOwner item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.property_delete_title)
                .setMessage(getString(
                        R.string.property_delete_message,
                        item.property.title
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(item.property, () -> {
                            if (binding == null) {
                                return;
                            }
                            loadProperties(currentQuery());
                            Snackbar.make(
                                    binding.getRoot(),
                                    R.string.property_removed,
                                    Snackbar.LENGTH_SHORT
                            ).show();
                        })
                )
                .show();
    }

    private void loadProperties(@NonNull String query) {
        repository.loadProperties(query, properties -> {
            if (binding == null) {
                return;
            }
            adapter.submitList(properties);
            boolean isEmpty = properties.isEmpty();
            binding.propertyRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.propertyEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.propertySearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        binding.propertyRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
