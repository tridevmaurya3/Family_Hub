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
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;
import com.tridev.familyhub.data.repository.PropertyRepository;
import com.tridev.familyhub.databinding.DialogPropertyBinding;
import com.tridev.familyhub.databinding.FragmentPropertyBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.SimpleDateFormat;
import java.text.NumberFormat;
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
    private final List<PropertyWithOwner> loadedProperties = new ArrayList<>();
    @NonNull
    private String selectedFilter = "ALL";
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

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
        binding.propertyBackButton.setOnClickListener(clickedView -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).showFeatureMenu();
            }
        });
        binding.propertyNotificationButton.setOnClickListener(clickedView ->
                ((MainActivity) requireActivity()).openTab(R.id.nav_reminders));
        binding.propertyProfileButton.setOnClickListener(clickedView ->
                ((MainActivity) requireActivity()).openProfile());
        binding.emptyAddPropertyButton.setOnClickListener(
                clickedView -> prepareEditor(null)
        );
        binding.propertyFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    int checkedId = checkedIds.isEmpty()
                            ? R.id.property_filter_all
                            : checkedIds.get(0);
                    if (checkedId == R.id.property_filter_homes) {
                        selectedFilter = "HOMES";
                    } else if (checkedId == R.id.property_filter_land) {
                        selectedFilter = "LAND";
                    } else if (checkedId
                            == R.id.property_filter_commercial) {
                        selectedFilter = "COMMERCIAL";
                    } else {
                        selectedFilter = "ALL";
                    }
                    applyPropertyFilter();
                }
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
        repository.startRealtimeSync(() -> {
            if (binding != null) loadProperties(currentQuery());
        });
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
            repository.loadDocuments(documents -> {
                if (binding != null) showEditor(members, documents, existing);
            });
        });
    }

    private void showEditor(
            @NonNull List<FamilyMember> members,
            @NonNull List<DocumentEntry> documents,
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
                R.layout.item_form_dropdown,
                memberNames
        ));
        String[] typeLabels = getResources().getStringArray(
                R.array.property_type_labels
        );
        form.propertyTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                typeLabels
        ));
        String[] stateLabels = getResources().getStringArray(
                R.array.property_state_labels
        );
        form.propertyStateInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                stateLabels
        ));
        List<String> documentTitles = new ArrayList<>();
        documentTitles.add(getString(R.string.property_no_linked_document));
        for (DocumentEntry document : documents) documentTitles.add(document.title);
        form.propertyLinkedDocumentInput.setAdapter(new ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown,
                documentTitles));

        long[] purchaseDate = {property.purchaseDate};
        if (existing == null) {
            form.propertyOwnerInput.setText(members.get(0).name, false);
            form.propertyTypeInput.setText(typeLabels[0], false);
            form.propertyStateInput.setText(stateLabels[0], false);
            form.propertyLinkedDocumentInput.setText(
                    getString(R.string.property_no_linked_document), false);
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
            form.propertyStateInput.setText(property.state, false);
            form.propertyPostalInput.setText(property.postalCode);
            form.propertyAreaInput.setText(property.area);
            setNumber(form.propertyPurchaseValueInput, property.purchaseValue);
            setNumber(form.propertyEstimatedValueInput, property.estimatedValue);
            form.propertyRegistrationInput.setText(
                    property.registrationReference
            );
            form.propertyNotesInput.setText(property.notes);
            form.propertyLinkedDocumentInput.setText(
                    property.linkedDocumentTitle.isEmpty()
                            ? getString(R.string.property_no_linked_document)
                            : property.linkedDocumentTitle,
                    false);
            form.propertyTimelineNoteInput.setText(property.timelineNote);
            form.propertySharedSwitch.setChecked(property.isShared);
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
            property.assignedOwnerName = owner.name;
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
            DocumentEntry document = findDocument(
                    documents, textOf(form.propertyLinkedDocumentInput));
            property.linkedDocumentId = document == null ? 0L : document.id;
            property.linkedDocumentTitle = document == null ? "" : document.title;
            property.timelineNote = textOf(form.propertyTimelineNoteInput);
            property.isShared = form.propertySharedSwitch.isChecked();
            property.updatedAt = System.currentTimeMillis();

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

    @Nullable
    private DocumentEntry findDocument(@NonNull List<DocumentEntry> documents,
                                       @NonNull String title) {
        for (DocumentEntry document : documents) {
            if (document.title.equalsIgnoreCase(title)) return document;
        }
        return null;
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
            loadedProperties.clear();
            loadedProperties.addAll(properties);
            updateSummary(properties);
            applyPropertyFilter();
        });
    }

    private void updateSummary(@NonNull List<PropertyWithOwner> properties) {
        int residentialCount = 0;
        int landCount = 0;
        double estimatedValue = 0;
        for (PropertyWithOwner item : properties) {
            String type = item.property.propertyType;
            if (PropertyEntry.TYPE_HOUSE.equals(type)
                    || PropertyEntry.TYPE_FLAT.equals(type)) {
                residentialCount++;
            }
            if (PropertyEntry.TYPE_LAND.equals(type)
                    || PropertyEntry.TYPE_AGRICULTURAL.equals(type)) {
                landCount++;
            }
            estimatedValue += Math.max(0, item.property.estimatedValue);
        }
        binding.propertyTotalValue.setText(String.valueOf(properties.size()));
        binding.propertyResidentialValue.setText(
                String.valueOf(residentialCount)
        );
        binding.propertyLandValue.setText(String.valueOf(landCount));
        binding.propertyTotalEstimateValue.setText(
                formatCompactCurrency(estimatedValue)
        );
    }

    private void applyPropertyFilter() {
        if (binding == null) {
            return;
        }
        List<PropertyWithOwner> filtered = new ArrayList<>();
        for (PropertyWithOwner item : loadedProperties) {
            String type = item.property.propertyType;
            boolean include;
            switch (selectedFilter) {
                case "HOMES":
                    include = PropertyEntry.TYPE_HOUSE.equals(type)
                            || PropertyEntry.TYPE_FLAT.equals(type);
                    break;
                case "LAND":
                    include = PropertyEntry.TYPE_LAND.equals(type)
                            || PropertyEntry.TYPE_AGRICULTURAL.equals(type);
                    break;
                case "COMMERCIAL":
                    include = PropertyEntry.TYPE_SHOP.equals(type)
                            || PropertyEntry.TYPE_OFFICE.equals(type);
                    break;
                default:
                    include = true;
            }
            if (include) {
                filtered.add(item);
            }
        }
        adapter.submitList(filtered);
        boolean isEmpty = filtered.isEmpty();
        binding.propertyRecyclerView.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.propertyEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );
    }

    @NonNull
    private String formatCompactCurrency(double value) {
        if (value >= 10_000_000) {
            return String.format(
                    Locale.getDefault(),
                    "₹%.1fCr",
                    value / 10_000_000
            );
        }
        if (value >= 100_000) {
            return String.format(
                    Locale.getDefault(),
                    "₹%.1fL",
                    value / 100_000
            );
        }
        if (value >= 1_000) {
            return String.format(
                    Locale.getDefault(),
                    "₹%.1fK",
                    value / 1_000
            );
        }
        return currencyFormat.format(value);
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
        if (repository != null) repository.stopRealtimeSync();
        binding.propertyRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
