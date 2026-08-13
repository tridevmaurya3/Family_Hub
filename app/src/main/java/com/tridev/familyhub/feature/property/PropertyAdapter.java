package com.tridev.familyhub.feature.property;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import androidx.core.content.ContextCompat;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;
import com.tridev.familyhub.databinding.ItemPropertyBinding;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fluent property list adapter. */
public class PropertyAdapter
        extends RecyclerView.Adapter<PropertyAdapter.PropertyViewHolder> {

    public interface PropertyActionListener {
        void onEdit(@NonNull PropertyWithOwner item);

        void onDelete(@NonNull PropertyWithOwner item);
    }

    private final List<PropertyWithOwner> properties = new ArrayList<>();
    private final PropertyActionListener listener;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    public PropertyAdapter(@NonNull PropertyActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<PropertyWithOwner> updated) {
        properties.clear();
        properties.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PropertyViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new PropertyViewHolder(ItemPropertyBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull PropertyViewHolder holder,
            int position
    ) {
        holder.bind(properties.get(position));
    }

    @Override
    public int getItemCount() {
        return properties.size();
    }

    class PropertyViewHolder extends RecyclerView.ViewHolder {

        private final ItemPropertyBinding binding;

        PropertyViewHolder(@NonNull ItemPropertyBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull PropertyWithOwner item) {
            PropertyEntry property = item.property;
            binding.propertyTitle.setText(property.title);
            binding.propertyOwner.setText(item.ownerName);
            binding.propertyType.setText(displayType(property.propertyType));

            String location = property.city.trim();
            if (location.isEmpty()) {
                location = property.address.trim();
            }
            binding.propertyLocation.setText(
                    location.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.property_location_not_added
                            )
                            : location
            );
            binding.propertyArea.setText(
                    property.area.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.property_area_not_added
                            )
                            : property.area
            );
            binding.propertyValue.setText(
                    property.estimatedValue > 0
                            ? currencyFormat.format(property.estimatedValue)
                            : binding.getRoot().getContext().getString(
                                    R.string.property_value_not_added
                            )
            );
            double change = property.estimatedValue - property.purchaseValue;
            boolean hasComparableValues = property.purchaseValue > 0
                    && property.estimatedValue > 0;
            binding.propertyValueChange.setVisibility(
                    hasComparableValues ? View.VISIBLE : View.GONE);
            if (hasComparableValues) {
                binding.propertyValueChange.setText(binding.getRoot().getContext()
                        .getString(change >= 0
                                ? R.string.property_value_growth
                                : R.string.property_value_loss,
                                currencyFormat.format(Math.abs(change))));
                binding.propertyValueChange.setTextColor(ContextCompat.getColor(
                        binding.getRoot().getContext(), change >= 0
                                ? R.color.fh_success : R.color.fh_error));
            }
            boolean hasDocument = !property.linkedDocumentTitle.isEmpty();
            binding.propertyDocument.setVisibility(
                    hasDocument ? View.VISIBLE : View.GONE);
            if (hasDocument) binding.propertyDocument.setText(
                    property.linkedDocumentTitle);
            binding.propertySharing.setText(property.isShared
                    ? R.string.property_shared_status
                    : R.string.property_private_status);

            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.editPropertyButton.setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.deletePropertyButton.setOnClickListener(
                    view -> listener.onDelete(item)
            );
        }

        @NonNull
        private String displayType(@NonNull String type) {
            int label;
            switch (type) {
                case PropertyEntry.TYPE_FLAT:
                    label = R.string.property_type_flat;
                    break;
                case PropertyEntry.TYPE_LAND:
                    label = R.string.property_type_land;
                    break;
                case PropertyEntry.TYPE_SHOP:
                    label = R.string.property_type_shop;
                    break;
                case PropertyEntry.TYPE_OFFICE:
                    label = R.string.property_type_office;
                    break;
                case PropertyEntry.TYPE_AGRICULTURAL:
                    label = R.string.property_type_agricultural;
                    break;
                case PropertyEntry.TYPE_OTHER:
                    label = R.string.property_type_other;
                    break;
                default:
                    label = R.string.property_type_house;
            }
            return binding.getRoot().getContext().getString(label);
        }
    }
}
