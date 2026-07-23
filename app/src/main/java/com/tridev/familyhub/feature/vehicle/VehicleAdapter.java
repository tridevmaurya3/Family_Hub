package com.tridev.familyhub.feature.vehicle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.VehicleWithOwner;
import com.tridev.familyhub.databinding.ItemVehicleBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Fluent vehicle list adapter with due-date awareness. */
public class VehicleAdapter
        extends RecyclerView.Adapter<VehicleAdapter.VehicleViewHolder> {

    public interface VehicleActionListener {
        void onEdit(@NonNull VehicleWithOwner item);

        void onDelete(@NonNull VehicleWithOwner item);
    }

    private final List<VehicleWithOwner> vehicles = new ArrayList<>();
    private final VehicleActionListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public VehicleAdapter(@NonNull VehicleActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<VehicleWithOwner> updated) {
        vehicles.clear();
        vehicles.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VehicleViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new VehicleViewHolder(ItemVehicleBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull VehicleViewHolder holder,
            int position
    ) {
        holder.bind(vehicles.get(position));
    }

    @Override
    public int getItemCount() {
        return vehicles.size();
    }

    class VehicleViewHolder extends RecyclerView.ViewHolder {

        private final ItemVehicleBinding binding;

        VehicleViewHolder(@NonNull ItemVehicleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull VehicleWithOwner item) {
            Vehicle vehicle = item.vehicle;
            binding.vehicleName.setText(vehicle.displayName);
            binding.vehicleOwner.setText(item.ownerName);
            binding.vehicleRegistration.setText(vehicle.registrationNumber);
            binding.vehicleType.setText(displayType(vehicle.vehicleType));

            String makeAndModel = (
                    vehicle.manufacturer + " " + vehicle.model
            ).trim();
            binding.vehicleModel.setText(
                    makeAndModel.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.vehicle_details_not_added
                            )
                            : makeAndModel
            );

            long nextDueDate = earliestFutureDueDate(vehicle);
            if (nextDueDate > 0L) {
                binding.vehicleDueDate.setVisibility(View.VISIBLE);
                binding.vehicleDueDate.setText(
                        binding.getRoot().getContext().getString(
                                R.string.vehicle_next_due,
                                dateFormat.format(new Date(nextDueDate))
                        )
                );
            } else {
                binding.vehicleDueDate.setVisibility(View.GONE);
            }

            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.editVehicleButton.setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.deleteVehicleButton.setOnClickListener(
                    view -> listener.onDelete(item)
            );
        }

        private long earliestFutureDueDate(@NonNull Vehicle vehicle) {
            long nearest = Long.MAX_VALUE;
            long[] dates = {
                    vehicle.insuranceExpiryAt,
                    vehicle.pollutionExpiryAt,
                    vehicle.serviceDueAt
            };
            for (long date : dates) {
                if (date > 0L && date < nearest) {
                    nearest = date;
                }
            }
            return nearest == Long.MAX_VALUE ? 0L : nearest;
        }

        @NonNull
        private String displayType(@NonNull String type) {
            int label;
            switch (type) {
                case Vehicle.TYPE_MOTORCYCLE:
                    label = R.string.vehicle_type_motorcycle;
                    break;
                case Vehicle.TYPE_SCOOTER:
                    label = R.string.vehicle_type_scooter;
                    break;
                case Vehicle.TYPE_BICYCLE:
                    label = R.string.vehicle_type_bicycle;
                    break;
                case Vehicle.TYPE_COMMERCIAL:
                    label = R.string.vehicle_type_commercial;
                    break;
                case Vehicle.TYPE_OTHER:
                    label = R.string.vehicle_type_other;
                    break;
                default:
                    label = R.string.vehicle_type_car;
            }
            return binding.getRoot().getContext().getString(label);
        }
    }
}
