package com.tridev.familyhub.feature.grocery;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.databinding.ItemGroceryBinding;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fluent shopping-list adapter with purchase toggles. */
public class GroceryAdapter
        extends RecyclerView.Adapter<GroceryAdapter.ItemViewHolder> {

    public interface ItemActionListener {
        void onPurchasedChanged(@NonNull GroceryItem item, boolean purchased);

        void onEdit(@NonNull GroceryItem item);

        void onDelete(@NonNull GroceryItem item);
    }

    private final List<GroceryItem> items = new ArrayList<>();
    private final ItemActionListener listener;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    public GroceryAdapter(@NonNull ItemActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<GroceryItem> updated) {
        items.clear();
        items.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ItemViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new ItemViewHolder(ItemGroceryBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull ItemViewHolder holder,
            int position
    ) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
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
                    item.estimatedCost > 0
                            ? currencyFormat.format(item.estimatedCost)
                            : binding.getRoot().getContext().getString(
                                    R.string.grocery_cost_not_added
                            )
            );
            binding.groceryPriority.setText(displayPriority(item.priority));

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
