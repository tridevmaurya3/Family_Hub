package com.tridev.familyhub.feature.health;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.HealthRecordWithMember;
import com.tridev.familyhub.databinding.ItemHealthRecordBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Fluent list adapter for member-linked health records. */
public class HealthRecordAdapter
        extends RecyclerView.Adapter<HealthRecordAdapter.RecordViewHolder> {

    public interface RecordActionListener {
        void onEdit(@NonNull HealthRecordWithMember item);

        void onDelete(@NonNull HealthRecordWithMember item);
    }

    private final List<HealthRecordWithMember> records = new ArrayList<>();
    private final RecordActionListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public HealthRecordAdapter(@NonNull RecordActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<HealthRecordWithMember> updated) {
        records.clear();
        records.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new RecordViewHolder(ItemHealthRecordBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecordViewHolder holder,
            int position
    ) {
        holder.bind(records.get(position));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    class RecordViewHolder extends RecyclerView.ViewHolder {

        private final ItemHealthRecordBinding binding;

        RecordViewHolder(@NonNull ItemHealthRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull HealthRecordWithMember item) {
            HealthRecord record = item.record;
            binding.healthRecordTitle.setText(record.title);
            binding.healthRecordMember.setText(item.memberName);
            binding.healthRecordType.setText(displayType(record.recordType));
            binding.healthRecordDate.setText(dateFormat.format(
                    new Date(record.recordedAt)
            ));

            boolean hasValue = !record.value.isEmpty();
            binding.healthRecordValue.setText(
                    hasValue
                            ? record.value
                            : binding.getRoot().getContext().getString(
                                    R.string.health_no_value
                            )
            );
            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.editHealthRecordButton.setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.deleteHealthRecordButton.setOnClickListener(
                    view -> listener.onDelete(item)
            );
        }

        @NonNull
        private String displayType(@NonNull String type) {
            int label;
            switch (type) {
                case HealthRecord.TYPE_MEDICINE:
                    label = R.string.health_type_medicine;
                    break;
                case HealthRecord.TYPE_CONDITION:
                    label = R.string.health_type_condition;
                    break;
                case HealthRecord.TYPE_ALLERGY:
                    label = R.string.health_type_allergy;
                    break;
                case HealthRecord.TYPE_MEASUREMENT:
                    label = R.string.health_type_measurement;
                    break;
                case HealthRecord.TYPE_APPOINTMENT:
                    label = R.string.health_type_appointment;
                    break;
                case HealthRecord.TYPE_VACCINATION:
                    label = R.string.health_type_vaccination;
                    break;
                default:
                    label = R.string.health_type_other;
            }
            return binding.getRoot().getContext().getString(label);
        }
    }
}
