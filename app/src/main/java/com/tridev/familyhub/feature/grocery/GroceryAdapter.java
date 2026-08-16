package com.tridev.familyhub.feature.grocery;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.databinding.ItemGroceryBinding;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fluent shopping-list adapter with purchase toggles and collapsible categories. */
public class GroceryAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private static final class Row {
        final String header;
        final GroceryItem item;
        final String categoryKey;

        Row(String header, GroceryItem item, String categoryKey) {
            this.header = header;
            this.item = item;
            this.categoryKey = categoryKey;
        }
    }

    public interface ItemActionListener {
        void onPurchasedChanged(@NonNull GroceryItem item, boolean purchased);

        void onEdit(@NonNull GroceryItem item);

        void onDelete(@NonNull GroceryItem item);

        void onBuying(@NonNull GroceryItem item);

        default void onGroupingChanged(boolean allCollapsed) { }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<GroceryItem> submittedItems = new ArrayList<>();
    private final Map<String, Double> submittedSpent = new LinkedHashMap<>();
    private final Map<String, Double> submittedBudgets = new LinkedHashMap<>();
    private final Set<String> collapsedCategories = new HashSet<>();
    private final Set<String> currentCategoryKeys = new LinkedHashSet<>();
    private final ItemActionListener listener;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final SimpleDateFormat purchaseDateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public GroceryAdapter(@NonNull ItemActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<GroceryItem> updated,
                           @NonNull Map<String, Double> spent,
                           @NonNull Map<String, Double> budgets) {
        submittedItems.clear();
        submittedItems.addAll(updated);
        submittedSpent.clear();
        submittedSpent.putAll(spent);
        submittedBudgets.clear();
        submittedBudgets.putAll(budgets);
        rebuildRows();
    }

    /** Collapse every currently visible category, or expand all when already collapsed. */
    public boolean toggleAllCategories() {
        boolean allCollapsed = areAllCurrentCategoriesCollapsed();
        if (allCollapsed) {
            collapsedCategories.removeAll(currentCategoryKeys);
        } else {
            collapsedCategories.addAll(currentCategoryKeys);
        }
        rebuildRows();
        boolean nowCollapsed = areAllCurrentCategoriesCollapsed();
        listener.onGroupingChanged(nowCollapsed);
        return nowCollapsed;
    }

    public boolean areAllCurrentCategoriesCollapsed() {
        return !currentCategoryKeys.isEmpty()
                && collapsedCategories.containsAll(currentCategoryKeys);
    }

    private void rebuildRows() {
        rows.clear();
        currentCategoryKeys.clear();

        List<GroceryItem> ordered = new ArrayList<>(submittedItems);
        ordered.sort((left, right) -> Integer.compare(
                priorityRank(left.priority), priorityRank(right.priority)));

        Map<String, List<GroceryItem>> grouped = new LinkedHashMap<>();
        for (GroceryItem item : ordered) {
            String category = item.category.isEmpty() ? "Uncategorized" : item.category;
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<String, List<GroceryItem>> group : grouped.entrySet()) {
            String key = normalizeCategory(group.getKey());
            currentCategoryKeys.add(key);
            double used = submittedSpent.containsKey(key) ? submittedSpent.get(key) : 0D;
            double budget = submittedBudgets.containsKey(key) ? submittedBudgets.get(key) : 0D;
            boolean collapsed = collapsedCategories.contains(key);

            StringBuilder header = new StringBuilder(collapsed ? "▶  " : "▼  ")
                    .append(group.getKey())
                    .append("  (")
                    .append(group.getValue().size())
                    .append(")");
            if (budget > 0D) {
                int percent = (int) Math.round(used / budget * 100D);
                header.append("  •  ")
                        .append(currencyFormat.format(used))
                        .append(" / ")
                        .append(currencyFormat.format(budget))
                        .append("  •  ")
                        .append(percent)
                        .append('%');
            } else if (used > 0D) {
                header.append("  •  ").append(currencyFormat.format(used));
            }

            rows.add(new Row(header.toString(), null, key));
            if (!collapsed) {
                for (GroceryItem item : group.getValue()) {
                    rows.add(new Row(null, item, key));
                }
            }
        }
        notifyDataSetChanged();
    }

    private void toggleCategory(@NonNull String categoryKey) {
        if (collapsedCategories.contains(categoryKey)) {
            collapsedCategories.remove(categoryKey);
        } else {
            collapsedCategories.add(categoryKey);
        }
        rebuildRows();
        listener.onGroupingChanged(areAllCurrentCategoriesCollapsed());
    }

    @NonNull
    private static String normalizeCategory(@NonNull String category) {
        return category.trim().toLowerCase(Locale.ENGLISH);
    }

    private static int priorityRank(String priority) {
        if (GroceryItem.PRIORITY_URGENT.equals(priority)) return 0;
        if (GroceryItem.PRIORITY_HIGH.equals(priority)) return 1;
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
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
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            headerHolder.title.setText(row.header);
            headerHolder.itemView.setOnClickListener(v -> {
                if (row.categoryKey != null && !row.categoryKey.isEmpty()) {
                    toggleCategory(row.categoryKey);
                }
            });
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
            int priorityColor = GroceryItem.PRIORITY_URGENT.equals(item.priority)
                    ? R.color.fh_error
                    : GroceryItem.PRIORITY_HIGH.equals(item.priority)
                    ? R.color.fh_warning
                    : R.color.fh_primary;
            binding.groceryPriority.setTextColor(ContextCompat.getColor(
                    binding.getRoot().getContext(), priorityColor));

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

            StringBuilder assignmentLine = new StringBuilder(listLabel)
                    .append(" • ")
                    .append(assignment);
            if (item.isPurchased && item.purchasedAt > 0L) {
                assignmentLine.append(" • ").append(
                        binding.getRoot().getContext().getString(
                                R.string.grocery_purchased_on,
                                purchaseDateFormat.format(new Date(item.purchasedAt))
                        )
                );
            }
            binding.groceryAssignment.setText(assignmentLine.toString());

            int flags = binding.groceryName.getPaintFlags();
            if (item.isPurchased) {
                binding.groceryName.setTextColor(ContextCompat.getColor(
                        binding.getRoot().getContext(), R.color.fh_error));
                binding.groceryName.setPaintFlags(
                        flags | Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(0.65f);
            } else {
                binding.groceryName.setTextColor(ContextCompat.getColor(
                        binding.getRoot().getContext(), R.color.fh_on_surface));
                binding.groceryName.setPaintFlags(
                        flags & ~Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(1f);
            }

            binding.groceryPurchased.setOnCheckedChangeListener(
                    (button, checked) -> listener.onPurchasedChanged(item, checked)
            );
            binding.getRoot().setOnClickListener(view -> listener.onEdit(item));
            binding.editGroceryButton.setOnClickListener(view -> listener.onEdit(item));
            binding.deleteGroceryButton.setOnClickListener(view -> listener.onDelete(item));
            binding.buyingGroceryButton.setEnabled(!item.isPurchased);
            binding.buyingGroceryButton.setOnClickListener(view -> listener.onBuying(item));
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
