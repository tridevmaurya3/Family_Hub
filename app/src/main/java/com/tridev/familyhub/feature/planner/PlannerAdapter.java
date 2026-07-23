package com.tridev.familyhub.feature.planner;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.PlannerItem;
import com.tridev.familyhub.databinding.ItemPlannerBinding;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Fluent event/task cards for the Family Planner. */
public class PlannerAdapter
        extends RecyclerView.Adapter<PlannerAdapter.PlannerViewHolder> {

    public interface ActionListener {
        void onCompletedChanged(@NonNull PlannerItem item, boolean completed);
        void onEdit(@NonNull PlannerItem item);
        void onDelete(@NonNull PlannerItem item);
    }

    private final List<PlannerItem> items = new ArrayList<>();
    private final ActionListener listener;

    public PlannerAdapter(@NonNull ActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<PlannerItem> updated) {
        items.clear();
        items.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PlannerViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return new PlannerViewHolder(ItemPlannerBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        ));
    }

    @Override
    public void onBindViewHolder(
            @NonNull PlannerViewHolder holder,
            int position
    ) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class PlannerViewHolder extends RecyclerView.ViewHolder {
        private final ItemPlannerBinding binding;

        PlannerViewHolder(@NonNull ItemPlannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull PlannerItem item) {
            binding.plannerCompleted.setOnCheckedChangeListener(null);
            binding.plannerCompleted.setChecked(item.isCompleted);
            binding.plannerTitle.setText(item.title);
            binding.plannerType.setText(
                    PlannerItem.TYPE_TASK.equals(item.itemType)
                            ? R.string.planner_type_task
                            : R.string.planner_type_event
            );
            binding.plannerDate.setText(
                    item.isAllDay
                            ? DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(new Date(item.startAt))
                            : DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM, DateFormat.SHORT
                            ).format(new Date(item.startAt))
            );
            binding.plannerLocation.setText(
                    item.location.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.planner_no_location
                            )
                            : item.location
            );
            binding.plannerPriority.setText(priorityLabel(item.priority));
            binding.plannerRepeat.setText(repeatLabel(item.repeatType));
            binding.plannerRepeat.setVisibility(
                    PlannerItem.REPEAT_NONE.equals(item.repeatType)
                            ? View.GONE : View.VISIBLE
            );

            int flags = binding.plannerTitle.getPaintFlags();
            if (item.isCompleted) {
                binding.plannerTitle.setPaintFlags(
                        flags | Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(0.65f);
            } else {
                binding.plannerTitle.setPaintFlags(
                        flags & ~Paint.STRIKE_THRU_TEXT_FLAG
                );
                binding.getRoot().setAlpha(1f);
            }
            binding.plannerCompleted.setOnCheckedChangeListener(
                    (button, checked) ->
                            listener.onCompletedChanged(item, checked)
            );
            binding.getRoot().setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.editPlannerButton.setOnClickListener(
                    view -> listener.onEdit(item)
            );
            binding.deletePlannerButton.setOnClickListener(
                    view -> listener.onDelete(item)
            );
        }

        private int priorityLabel(@NonNull String priority) {
            if (PlannerItem.PRIORITY_URGENT.equals(priority)) {
                return R.string.planner_priority_urgent;
            }
            if (PlannerItem.PRIORITY_HIGH.equals(priority)) {
                return R.string.planner_priority_high;
            }
            return R.string.planner_priority_normal;
        }

        private int repeatLabel(@NonNull String repeat) {
            if (PlannerItem.REPEAT_DAILY.equals(repeat)) {
                return R.string.planner_repeat_daily;
            }
            if (PlannerItem.REPEAT_WEEKLY.equals(repeat)) {
                return R.string.planner_repeat_weekly;
            }
            if (PlannerItem.REPEAT_MONTHLY.equals(repeat)) {
                return R.string.planner_repeat_monthly;
            }
            if (PlannerItem.REPEAT_YEARLY.equals(repeat)) {
                return R.string.planner_repeat_yearly;
            }
            return R.string.planner_repeat_none;
        }
    }
}
