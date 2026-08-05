package com.tridev.familyhub.feature.grocery;

import android.os.Bundle;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import android.net.Uri;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.gms.location.LocationServices;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.databinding.DialogGroceryBinding;
import com.tridev.familyhub.databinding.FragmentGroceryBinding;
import com.tridev.familyhub.feature.main.AddActionHost;
import com.tridev.familyhub.feature.grocery.overlay.GroceryOverlayService;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline-first family Grocery and Shopping List screen. */
public class GroceryFragment extends Fragment implements AddActionHost {

    private static final String[] PRIORITIES = {
            GroceryItem.PRIORITY_NORMAL,
            GroceryItem.PRIORITY_HIGH,
            GroceryItem.PRIORITY_URGENT
    };

    private FragmentGroceryBinding binding;
    private GroceryRepository repository;
    private FamilyMemberRepository memberRepository;
    private GroceryAdapter adapter;
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    private int activeFilterId = R.id.filter_all;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final ActivityResultLauncher<Intent> voiceLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != android.app.Activity.RESULT_OK
                                || result.getData() == null) {
                            return;
                        }
                        ArrayList<String> matches = result.getData()
                                .getStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            addFromVoice(matches.get(0));
                        }
                    }
            );
    private final ActivityResultLauncher<String> overlayVoicePermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted && binding != null) {
                            continueFloatingStripSetup();
                        } else if (binding != null) {
                            Snackbar.make(binding.getRoot(),
                                    R.string.grocery_overlay_voice_permission,
                                    Snackbar.LENGTH_LONG).show();
                        }
                    }
            );

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentGroceryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        repository = new GroceryRepository(requireContext());
        memberRepository = new FamilyMemberRepository(requireContext());
        adapter = new GroceryAdapter(
                new GroceryAdapter.ItemActionListener() {
                    @Override
                    public void onPurchasedChanged(
                            @NonNull GroceryItem item,
                            boolean purchased
                    ) {
                        repository.setPurchased(
                                item,
                                purchased,
                                () -> loadItems(currentQuery())
                        );
                    }

                    @Override
                    public void onEdit(@NonNull GroceryItem item) {
                        showEditor(item);
                    }

                    @Override
                    public void onDelete(@NonNull GroceryItem item) {
                        confirmDelete(item);
                    }

                    @Override
                    public void onBuying(@NonNull GroceryItem item) {
                        repository.setBuyingStatus(item,
                                GroceryItem.STATUS_BUYING,
                                () -> loadItems(currentQuery()));
                    }
                }
        );
        binding.groceryRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.groceryRecyclerView.setAdapter(adapter);
        binding.emptyAddGroceryButton.setOnClickListener(
                clickedView -> showEditor(null)
        );
        binding.clearPurchasedButton.setOnClickListener(
                clickedView -> confirmClearPurchased()
        );
        binding.floatingGroceryButton.setOnClickListener(
                clickedView -> toggleFloatingStrip()
        );
        binding.groceryVoiceButton.setOnClickListener(v -> startVoiceAdd());
        binding.groceryVoiceButton.setOnLongClickListener(v -> {
            showRecurringSuggestions();
            return true;
        });
        binding.groceryBudgetButton.setOnClickListener(v -> showBudgetEditor());
        binding.grocerySuggestionsButton.setOnClickListener(
                v -> showRecurringSuggestions());
        binding.grocerySearchInput.addTextChangedListener(
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
                        loadItems(text == null ? "" : text.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            android.text.Editable editable
                    ) {
                        // No action required.
                    }
                }
        );
        binding.groceryFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (!checkedIds.isEmpty()) {
                        activeFilterId = checkedIds.get(0);
                        loadItems(currentQuery());
                    }
                }
        );
        memberRepository.loadMembers("", members -> {
            familyMembers.clear();
            familyMembers.addAll(members);
        });
        loadItems("");
        repository.startRealtimeSync(() -> {
            if (binding != null) {
                loadItems(currentQuery());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) {
            return;
        }
        boolean requested = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_REQUESTED, false);
        if (requested && Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(
                    GroceryOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(GroceryOverlayService.KEY_REQUESTED, false).apply();
            startFloatingStrip();
        }
        updateFloatingButton();
    }

    private void toggleFloatingStrip() {
        boolean enabled = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        if (enabled) {
            Intent stop = new Intent(requireContext(), GroceryOverlayService.class);
            stop.setAction(GroceryOverlayService.ACTION_STOP);
            requireContext().startService(stop);
            binding.floatingGroceryButton.postDelayed(
                    this::updateFloatingButton, 250L);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            overlayVoicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        continueFloatingStripSetup();
    }

    private void continueFloatingStripSetup() {
        if (!Settings.canDrawOverlays(requireContext())) {
            requireContext().getSharedPreferences(
                    GroceryOverlayService.PREFS,
                    android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(GroceryOverlayService.KEY_REQUESTED, true).apply();
            Snackbar.make(binding.getRoot(),
                    R.string.grocery_overlay_permission,
                    Snackbar.LENGTH_LONG).show();
            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())
            );
            startActivity(permission);
            return;
        }
        startFloatingStrip();
    }

    private void startFloatingStrip() {
        ContextCompat.startForegroundService(requireContext(),
                new Intent(requireContext(), GroceryOverlayService.class));
        binding.floatingGroceryButton.postDelayed(
                this::updateFloatingButton, 250L);
    }

    private void updateFloatingButton() {
        if (binding == null) {
            return;
        }
        boolean enabled = requireContext().getSharedPreferences(
                GroceryOverlayService.PREFS, android.content.Context.MODE_PRIVATE
        ).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        binding.floatingGroceryButton.setText(enabled
                ? R.string.grocery_floating_disable
                : R.string.grocery_floating_enable);
    }

    @Override
    public void onAddRequested() {
        showEditor(null);
    }

    private void showEditor(@Nullable GroceryItem existing) {
        DialogGroceryBinding form =
                DialogGroceryBinding.inflate(getLayoutInflater());
        GroceryItem item = existing == null
                ? new GroceryItem()
                : existing;
        String[] priorityLabels = getResources().getStringArray(
                R.array.grocery_priority_labels
        );
        String[] categoryLabels = getResources().getStringArray(
                R.array.grocery_category_labels
        );
        String[] listTypeLabels = getResources().getStringArray(
                R.array.grocery_list_type_labels
        );
        List<String> assigneeLabels = new ArrayList<>();
        assigneeLabels.add(getString(R.string.grocery_whole_family));
        for (FamilyMember member : familyMembers) {
            assigneeLabels.add(member.name);
        }
        form.groceryCategoryInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                categoryLabels
        ));
        form.groceryPriorityInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                priorityLabels
        ));
        form.groceryListTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                listTypeLabels
        ));
        form.groceryAssigneeInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                assigneeLabels
        ));

        if (existing == null) {
            form.groceryCategoryInput.setText(categoryLabels[0], false);
            form.groceryPriorityInput.setText(priorityLabels[0], false);
            form.groceryListTypeInput.setText(listTypeLabels[0], false);
            form.groceryAssigneeInput.setText(assigneeLabels.get(0), false);
        } else {
            form.groceryDialogTitle.setText(R.string.grocery_edit_item);
            form.groceryNameInput.setText(item.name);
            form.groceryCategoryInput.setText(item.category, false);
            form.groceryQuantityInput.setText(item.quantity);
            if (item.estimatedCost > 0) {
                form.groceryCostInput.setText(String.valueOf(
                        item.estimatedCost
                ));
            }
            form.groceryPriorityInput.setText(
                    displayPriority(item.priority),
                    false
            );
            form.groceryNotesInput.setText(item.notes);
            if (item.actualCost > 0D) {
                form.groceryActualCostInput.setText(String.valueOf(item.actualCost));
            }
            form.groceryAutoPriceSwitch.setChecked(item.autoPriceEnabled);
            form.groceryMonthlyMasterSwitch.setChecked(item.isMonthlyMaster);
            form.groceryListTypeInput.setText(
                    GroceryItem.LIST_MONTHLY.equals(item.listType)
                            ? listTypeLabels[1] : listTypeLabels[0],
                    false
            );
            form.groceryAssigneeInput.setText(
                    item.assignedMemberName.isEmpty()
                            ? assigneeLabels.get(0)
                            : item.assignedMemberName,
                    false
            );
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(form.getRoot())
                .create();
        form.cancelGroceryButton.setOnClickListener(
                clickedView -> dialog.dismiss()
        );
        form.saveGroceryButton.setOnClickListener(clickedView -> {
            String name = textOf(form.groceryNameInput);
            int priorityIndex = findPriorityIndex(
                    priorityLabels,
                    textOf(form.groceryPriorityInput)
            );
            if (name.isEmpty()) {
                form.groceryNameLayout.setError(
                        getString(R.string.grocery_name_required)
                );
                return;
            }
            form.groceryNameLayout.setError(null);
            if (priorityIndex < 0) {
                form.groceryPriorityLayout.setError(
                        getString(R.string.grocery_priority_required)
                );
                return;
            }
            form.groceryPriorityLayout.setError(null);

            item.name = name;
            item.category = textOf(form.groceryCategoryInput);
            item.quantity = textOf(form.groceryQuantityInput);
            item.estimatedCost = parseAmount(textOf(form.groceryCostInput));
            item.priority = PRIORITIES[priorityIndex];
            item.notes = textOf(form.groceryNotesInput);
            item.actualCost = parseAmount(textOf(form.groceryActualCostInput));
            item.autoPriceEnabled = form.groceryAutoPriceSwitch.isChecked();
            item.listType = listTypeLabels[1].equalsIgnoreCase(
                    textOf(form.groceryListTypeInput)
            ) ? GroceryItem.LIST_MONTHLY : GroceryItem.LIST_DAILY;
            item.isMonthlyMaster = GroceryItem.LIST_MONTHLY.equals(item.listType)
                    && form.groceryMonthlyMasterSwitch.isChecked();
            int assigneeIndex = assigneeLabels.indexOf(
                    textOf(form.groceryAssigneeInput)
            );
            if (assigneeIndex > 0 && assigneeIndex <= familyMembers.size()) {
                FamilyMember member = familyMembers.get(assigneeIndex - 1);
                item.assignedMemberId = member.cloudProfileId.isEmpty()
                        ? String.valueOf(member.id)
                        : member.cloudProfileId;
                item.assignedMemberName = member.name;
            } else {
                item.assignedMemberId = "";
                item.assignedMemberName = "";
            }

            Runnable saveComplete = () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                loadItems(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        item.duplicateMerged
                                ? R.string.grocery_duplicate_reused
                                : existing == null
                                ? R.string.grocery_item_added
                                : R.string.grocery_item_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            };
            estimateAndSave(item, saveComplete);
        });
        dialog.show();
    }

    private int findPriorityIndex(
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
    private String displayPriority(@NonNull String stored) {
        for (int index = 0; index < PRIORITIES.length; index++) {
            if (PRIORITIES[index].equals(stored)) {
                return getResources().getStringArray(
                        R.array.grocery_priority_labels
                )[index];
            }
        }
        return getString(R.string.grocery_priority_normal);
    }

    private double parseAmount(@NonNull String value) {
        try {
            return value.isEmpty() ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void confirmDelete(@NonNull GroceryItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_delete_title)
                .setMessage(getString(
                        R.string.grocery_delete_message,
                        item.name
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.delete(item, () -> {
                            if (binding != null) {
                                loadItems(currentQuery());
                            }
                        })
                )
                .show();
    }

    private void confirmClearPurchased() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_clear_title)
                .setMessage(R.string.grocery_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) ->
                        repository.clearPurchased(() -> {
                            if (binding != null) {
                                loadItems(currentQuery());
                            }
                        })
                )
                .show();
    }

    private void loadItems(@NonNull String query) {
        repository.loadItems(query, items -> {
            if (binding == null) {
                return;
            }
            List<GroceryItem> visibleItems = applyFilter(items);
            adapter.submitList(visibleItems);
            renderSummary(items);
            boolean isEmpty = visibleItems.isEmpty();
            binding.groceryRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.groceryEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    @NonNull
    private List<GroceryItem> applyFilter(@NonNull List<GroceryItem> items) {
        List<GroceryItem> filtered = new ArrayList<>();
        for (GroceryItem item : items) {
            boolean include;
            if (activeFilterId == R.id.filter_daily) {
                include = GroceryItem.LIST_DAILY.equals(item.listType);
            } else if (activeFilterId == R.id.filter_monthly) {
                include = GroceryItem.LIST_MONTHLY.equals(item.listType);
            } else if (activeFilterId == R.id.filter_pending) {
                include = !item.isPurchased;
            } else if (activeFilterId == R.id.filter_purchased) {
                include = item.isPurchased;
            } else {
                include = true;
            }
            if (include) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void renderSummary(@NonNull List<GroceryItem> items) {
        int pending = 0;
        int purchased = 0;
        double total = 0;
        for (GroceryItem item : items) {
            if (item.isPurchased) {
                purchased++;
            } else {
                pending++;
                total += item.estimatedCost;
            }
        }
        binding.groceryPendingValue.setText(String.valueOf(pending));
        binding.groceryTotalValue.setText(currencyFormat.format(total));
        double budget = repository.getMonthlyBudget();
        binding.groceryBudgetValue.setText(budget <= 0D
                ? getString(R.string.grocery_set_budget)
                : currencyFormat.format(Math.max(0D, budget - total)));
        binding.clearPurchasedButton.setEnabled(purchased > 0);
    }

    private void showBudgetEditor() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(R.string.grocery_budget_hint);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grocery_budget)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.grocery_save_item, (dialog, which) -> {
                    repository.setMonthlyBudget(parseAmount(textOf(input)));
                    loadItems(currentQuery());
                }).show();
    }

    private void startVoiceAdd() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.grocery_voice_add));
        voiceLauncher.launch(intent);
    }

    private void addFromVoice(@NonNull String spoken) {
        String normalized = spoken.trim();
        GroceryItem item = new GroceryItem();
        item.listType = normalized.toLowerCase(Locale.ROOT).contains("monthly")
                || normalized.contains("मंथली")
                || normalized.contains("मासिक")
                ? GroceryItem.LIST_MONTHLY : GroceryItem.LIST_DAILY;
        item.isMonthlyMaster = GroceryItem.LIST_MONTHLY.equals(item.listType);
        normalized = normalized.replaceAll(
                "(?i)monthly|daily|list|add|item|मंथली|मासिक|डेली|लिस्ट|जोड़ो|ऐड",
                " ").replaceAll("\\s+", " ").trim();
        item.name = normalized.isEmpty() ? spoken.trim() : normalized;
        estimateAndSave(item, () -> {
            if (binding != null) {
                loadItems(currentQuery());
                Snackbar.make(binding.getRoot(),
                        R.string.grocery_item_added,
                        Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    private void showRecurringSuggestions() {
        repository.loadSuggestions(items -> {
            if (items.isEmpty() || binding == null) {
                Snackbar.make(binding.getRoot(),
                        R.string.grocery_no_suggestions,
                        Snackbar.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                labels[i] = items.get(i).name;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.grocery_suggestions)
                    .setItems(labels, (dialog, which) -> {
                        GroceryItem source = items.get(which);
                        GroceryItem suggested = new GroceryItem();
                        suggested.name = source.name;
                        suggested.category = source.category;
                        suggested.quantity = source.quantity;
                        suggested.estimatedCost = source.actualCost > 0D
                                ? source.actualCost : source.estimatedCost;
                        suggested.listType = source.listType;
                        repository.save(suggested,
                                () -> loadItems(currentQuery()));
                    }).show();
        });
    }

    private void estimateAndSave(
            @NonNull GroceryItem item,
            @NonNull Runnable complete
    ) {
        if (item.autoPriceEnabled && item.actualCost > 0D
                && item.priceLocationKey.isEmpty()
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(requireContext())
                    .getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            item.priceLocationKey = String.format(Locale.US,
                                    "%.2f,%.2f", location.getLatitude(),
                                    location.getLongitude());
                            item.priceConfidence = 100;
                        }
                        repository.save(item, complete::run);
                    })
                    .addOnFailureListener(error ->
                            repository.save(item, complete::run));
            return;
        }
        if (!item.autoPriceEnabled || item.estimatedCost > 0D
                || item.name.trim().isEmpty()) {
            repository.save(item, complete::run);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            estimateWithKey(item, "", complete);
            return;
        }
        LocationServices.getFusedLocationProviderClient(requireContext())
                .getLastLocation()
                .addOnSuccessListener(location -> {
                    String key = location == null ? "" : String.format(
                            Locale.US, "%.2f,%.2f",
                            location.getLatitude(), location.getLongitude());
                    estimateWithKey(item, key, complete);
                })
                .addOnFailureListener(error ->
                        estimateWithKey(item, "", complete));
    }

    private void estimateWithKey(
            @NonNull GroceryItem item,
            @NonNull String key,
            @NonNull Runnable complete
    ) {
        repository.estimatePrice(item.name, key, (amount, confidence) -> {
            if (amount > 0D) {
                item.estimatedCost = amount;
                item.priceLocationKey = key;
                item.priceConfidence = confidence;
            }
            repository.save(item, complete::run);
        });
    }

    @NonNull
    private String currentQuery() {
        return textOf(binding.grocerySearchInput);
    }

    @NonNull
    private String textOf(@NonNull android.widget.EditText input) {
        return input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        repository.stopRealtimeSync();
        binding.groceryRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
