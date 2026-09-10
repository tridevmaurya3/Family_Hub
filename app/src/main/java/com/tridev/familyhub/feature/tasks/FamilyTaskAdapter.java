package com.tridev.familyhub.feature.tasks;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.databinding.ItemFamilyTaskBinding;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

final class FamilyTaskAdapter extends RecyclerView.Adapter<FamilyTaskAdapter.Holder> {
    interface Listener {
        void onCompletedChanged(@NonNull FamilyTask task, boolean completed);
        void onEdit(@NonNull FamilyTask task);
        void onDelete(@NonNull FamilyTask task);
    }
    private final List<FamilyTask> tasks = new ArrayList<>();
    private final Listener listener;
    FamilyTaskAdapter(@NonNull Listener listener) { this.listener = listener; }
    void submitList(@NonNull List<FamilyTask> updated) {
        tasks.clear(); tasks.addAll(updated); notifyDataSetChanged();
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemFamilyTaskBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(tasks.get(position)); }
    @Override public int getItemCount() { return tasks.size(); }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemFamilyTaskBinding binding;
        Holder(ItemFamilyTaskBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(FamilyTask task) {
            boolean completed = FamilyTask.STATUS_COMPLETED.equals(task.status);
            binding.taskCompleted.setOnCheckedChangeListener(null);
            binding.taskCompleted.setChecked(completed);
            binding.taskTitle.setText(task.title);
            int flags = binding.taskTitle.getPaintFlags();
            binding.taskTitle.setPaintFlags(completed ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
            binding.getRoot().setAlpha(completed ? .68f : 1f);
            binding.taskDue.setText(dueText(task));
            binding.taskPriority.setText(priorityText(task.priority));
            String who = task.assignedMemberName.isEmpty()
                    ? binding.getRoot().getContext().getString(R.string.family_tasks_whole_family)
                    : task.assignedMemberName;
            String repeat = FamilyTask.REPEAT_NONE.equals(task.repeatType) ? "" : " • " + repeatText(task.repeatType);
            binding.taskMeta.setText(who + repeat);
            binding.taskNotes.setText(task.notes);
            binding.taskNotes.setVisibility(task.notes.isEmpty() ? View.GONE : View.VISIBLE);
            binding.taskCompleted.setOnCheckedChangeListener((button, checked) -> listener.onCompletedChanged(task, checked));
            binding.getRoot().setOnClickListener(v -> listener.onEdit(task));
            binding.editTaskButton.setOnClickListener(v -> listener.onEdit(task));
            binding.deleteTaskButton.setOnClickListener(v -> listener.onDelete(task));
        }
        private String dueText(FamilyTask task) {
            if (FamilyTask.STATUS_COMPLETED.equals(task.status) && !task.completedByName.isEmpty()) {
                return binding.getRoot().getContext().getString(R.string.family_tasks_completed_by, task.completedByName);
            }
            String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(task.dueAt));
            if (task.dueAt < System.currentTimeMillis()) return binding.getRoot().getContext().getString(R.string.family_tasks_overdue, date);
            return binding.getRoot().getContext().getString(R.string.family_tasks_due_date, date);
        }
        private String priorityText(String priority) {
            if (FamilyTask.PRIORITY_URGENT.equals(priority)) return binding.getRoot().getContext().getString(R.string.task_priority_urgent);
            if (FamilyTask.PRIORITY_HIGH.equals(priority)) return binding.getRoot().getContext().getString(R.string.task_priority_high);
            return binding.getRoot().getContext().getString(R.string.task_priority_normal);
        }
        private String repeatText(String repeat) {
            if (FamilyTask.REPEAT_DAILY.equals(repeat)) return binding.getRoot().getContext().getString(R.string.task_repeat_daily);
            if (FamilyTask.REPEAT_WEEKLY.equals(repeat)) return binding.getRoot().getContext().getString(R.string.task_repeat_weekly);
            return binding.getRoot().getContext().getString(R.string.task_repeat_monthly);
        }
    }
}
