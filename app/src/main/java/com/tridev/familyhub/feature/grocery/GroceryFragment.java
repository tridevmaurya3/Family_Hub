package com.tridev.familyhub.feature.grocery;

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
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.databinding.DialogGroceryBinding;
import com.tridev.familyhub.databinding.FragmentGroceryBinding;
import com.tridev.familyhub.feature.main.AddActionHost;

import java.text.NumberFormat;
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
    private GroceryAdapter adapter;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

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
        loadItems("");
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

        if (existing == null) {
            form.groceryCategoryInput.setText(categoryLabels[0], false);
            form.groceryPriorityInput.setText(priorityLabels[0], false);
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

            repository.save(item, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                loadItems(currentQuery());
                Snackbar.make(
                        binding.getRoot(),
                        existing == null
                                ? R.string.grocery_item_added
                                : R.string.grocery_item_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
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
            adapter.submitList(items);
            renderSummary(items);
            boolean isEmpty = items.isEmpty();
            binding.groceryRecyclerView.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.groceryEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
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
        binding.clearPurchasedButton.setEnabled(purchased > 0);
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
        binding.groceryRecyclerView.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
