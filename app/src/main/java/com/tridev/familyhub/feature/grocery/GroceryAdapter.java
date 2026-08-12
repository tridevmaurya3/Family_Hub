package com.tridev.familyhub.feature.grocery;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.databinding.ItemGroceryBinding;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Fluent shopping-list adapter with purchase toggles. */
public class GroceryAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final class Row {
        final String header;
        final GroceryItem item;
        Row(String header, GroceryItem item) { this.header = header; this.item = item; }
    }

    public interface ItemActionListener {
        void onPurchasedChanged(@NonNull GroceryItem item, boolean purchased);

        void onEdit(@NonNull GroceryItem item);

        void onDelete(@NonNull GroceryItem item);

        void onBuying(@NonNull GroceryItem item);
    }

    private final List<Row> rows = new ArrayList<>();
    private final ItemActionListener listener;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    public GroceryAdapter(@NonNull ItemActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<GroceryItem> updated,
                           @NonNull Map<String, Double> spent,
                           @NonNull Map<String, Double> budgets) {
        rows.clear();
        Map<String, List<GroceryItem>> grouped = new LinkedHashMap<>();
        for (GroceryItem item : updated) {
            String category = item.category.isEmpty() ? "Uncategorized" : item.category;
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<GroceryItem>> group : grouped.entrySet()) {
            String key = group.getKey().toLowerCase(Locale.ENGLISH);
            double used = spent.containsKey(key) ? spent.get(key) : 0D;
            double budget = budgets.containsKey(key) ? budgets.get(key) : 0D;
            String header = group.getKey() + "  (" + group.getValue().size() + ")";
            if (budget > 0D) {
                int percent = (int) Math.round(used / budget * 100D);
                header += "  •  " + currencyFormat.format(used) + " / "
                        + currencyFormat.format(budget) + "  •  " + percent + "%";
            } else if (used > 0D) {
                header += "  •  " + currencyFormat.format(used);
            }
            rows.add(new Row(header, null));
            for (GroceryItem item : group.getValue()) rows.add(new Row(null, item));
        }
        notifyDataSetChanged();
    }

    @Override public int getItemViewType(int position) {
        return rows.get(position).item == null ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_grocery_category_header, parent, false);
            return new HeaderViewHolder(view);
        }
        return new ItemViewHolder(ItemGroceryBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            int position
    ) {
        Row row = rows.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).title.setText(row.header);
        } else {
            ((ItemViewHolder) holder).bind(row.item);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderViewHolder(View view) {
            super(view);
            title = view.findViewById(R.id.grocery_category_header);
        }
    }

    class ItemViewHolder extends RecyclerView.ViewHolder {

        private final ItemGroceryBinding binding;

        ItemViewHolder(@NonNull ItemGroceryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull GroceryItem item) {
            binding.groceryPurchased.setOnCheckedChangeListener(null);
            binding.groceryPurchased.setChecked(item.isPurchased);
            binding.groceryName.setText(item.name);
            binding.groceryCategory.setText(
                    item.category.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.grocery_uncategorized
                            )
                            : item.category
            );
            binding.groceryQuantity.setText(
                    item.quantity.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.grocery_quantity_not_added
                            )
                            : item.quantity
            );
            binding.groceryCost.setText(
                    (item.actualCost > 0 || item.estimatedCost > 0)
                            ? currencyFormat.format(item.actualCost > 0
                                    ? item.actualCost : item.estimatedCost)
                            : binding.getRoot().getContext().getString(
                                    R.string.grocery_cost_not_added
                            )
            );
            binding.groceryPriority.setText(displayPriority(item.priority));
            String listLabel = binding.getRoot().getContext().getString(
                    GroceryItem.LIST_MONTHLY.equals(item.listType)
                            ? R.string.grocery_list_monthly
                            : R.string.grocery_list_daily
            );
            String assignment;
            if (item.isPurchased && !item.purchasedByName.isEmpty()) {
                assignment = binding.getRoot().getContext().getString(
                        R.string.grocery_purchased_by,
                        item.purchasedByName
                );
            } else if (GroceryItem.STATUS_BUYING.equals(item.buyingStatus)) {
                assignment = binding.getRoot().getContext().getString(
                        R.string.grocery_status_buying,
                        item.updatedByName
                );
            } else if (!item.assignedMemberName.isEmpty()) {
                assignment = binding.getRoot().getContext().getString(
                        R.string.grocery_assigned_to,
                        item.assignedMemberName
                );
            } else {
                assignment = binding.getRoot().getContext().getString(
                        R.string.grocery_shared_family
                );
            }
            binding.groceryAssignment.setText(listLabel + " • " + assignment);

            int flags = binding.groceryName.getPaintFlags();
            if (item.isPurchased) {
                binding.groceryName.setPaintFlags(
                        flags | Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(0.65f);
            } else {
                binding.groceryName.setPaintFlags(
                        flags & ~Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(1f);
            }

            binding.groceryPurchased.setOnCheckedChangeListener(
                    (button, checked) ->
                            listener.onPurchasedChanged(item, checked)
            );
            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.editGroceryButton.setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.deleteGroceryButton.setOnClickListener(
                    view -> listener.onDelete(item)
            );
            binding.buyingGroceryButton.setEnabled(!item.isPurchased);
            binding.buyingGroceryButton.setOnClickListener(
                    view -> listener.onBuying(item)
            );
        }

        @NonNull
        private String displayPriority(@NonNull String priority) {
            int label;
            if (GroceryItem.PRIORITY_URGENT.equals(priority)) {
                label = R.string.grocery_priority_urgent;
            } else if (GroceryItem.PRIORITY_HIGH.equals(priority)) {
                label = R.string.grocery_priority_high;
            } else {
                label = R.string.grocery_priority_normal;
            }
            return binding.getRoot().getContext().getString(label);
        }
    }
}
