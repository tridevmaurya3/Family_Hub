package com.tridev.familyhub.feature.tasks;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

final class FamilyTaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SECTION = 0;
    private static final int TYPE_TASK = 1;
    interface Listener {
        void onCompletedChanged(@NonNull FamilyTask task, boolean completed);
        void onEdit(@NonNull FamilyTask task);
        void onDelete(@NonNull FamilyTask task);
    }
    private final List<FamilyTask> tasks = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    @NonNull private String expandedPriority = "";
    private final Listener listener;
    FamilyTaskAdapter(@NonNull Listener listener) { this.listener = listener; }
    void submitList(@NonNull List<FamilyTask> updated) {
        tasks.clear(); tasks.addAll(updated); rebuildRows(); notifyDataSetChanged();
    }
    @Override public int getItemViewType(int position) {
        return rows.get(position) instanceof PrioritySection ? TYPE_SECTION : TYPE_TASK;
    }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SECTION) return new SectionHolder(sectionView(parent));
        return new Holder(ItemFamilyTaskBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof SectionHolder && row instanceof PrioritySection) {
            ((SectionHolder) holder).bind((PrioritySection) row);
        } else if (holder instanceof Holder && row instanceof FamilyTask) {
            ((Holder) holder).bind((FamilyTask) row);
        }
    }
    @Override public int getItemCount() { return rows.size(); }

    private void rebuildRows() {
        rows.clear();
        appendPriority(FamilyTask.PRIORITY_URGENT);
        appendPriority(FamilyTask.PRIORITY_HIGH);
        appendPriority(FamilyTask.PRIORITY_NORMAL);
    }

    private void appendPriority(@NonNull String priority) {
        List<FamilyTask> grouped = new ArrayList<>();
        for (FamilyTask task : tasks) {
            if (priority.equals(normalizedPriority(task))) grouped.add(task);
        }
        if (grouped.isEmpty()) return;
        boolean open = expandedPriority.isEmpty() || expandedPriority.equals(priority);
        rows.add(new PrioritySection(priority, grouped.size(), open));
        if (open) rows.addAll(grouped);
    }

    @NonNull
    private String normalizedPriority(@NonNull FamilyTask task) {
        if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) return FamilyTask.PRIORITY_URGENT;
        if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) return FamilyTask.PRIORITY_HIGH;
        return FamilyTask.PRIORITY_NORMAL;
    }

    @NonNull
    private TextView sectionView(@NonNull ViewGroup parent) {
        TextView view = new TextView(parent.getContext());
        view.setTextSize(11f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        int horizontal = dp(parent, 10);
        view.setPadding(horizontal, 0, horizontal, 0);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 34));
        params.setMargins(0, dp(parent, 3), 0, dp(parent, 3));
        view.setLayoutParams(params);
        return view;
    }

    private int dp(@NonNull View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static final class PrioritySection {
        @NonNull final String priority;
        final int count;
        final boolean open;
        PrioritySection(@NonNull String priority, int count, boolean open) {
            this.priority = priority; this.count = count; this.open = open;
        }
    }

    final class SectionHolder extends RecyclerView.ViewHolder {
        private final TextView label;
        SectionHolder(@NonNull TextView itemView) { super(itemView); label = itemView; }
        void bind(@NonNull PrioritySection section) {
            label.setText((section.open ? "▾  " : "▸  ")
                    + priorityText(label, section.priority) + "  (" + section.count + ")");
            int textColor;
            int fill;
            int stroke;
            if (FamilyTask.PRIORITY_URGENT.equals(section.priority)) {
                textColor = Color.rgb(164, 43, 62); fill = Color.rgb(255, 235, 239);
                stroke = Color.rgb(220, 118, 136);
            } else if (FamilyTask.PRIORITY_HIGH.equals(section.priority)) {
                textColor = Color.rgb(147, 91, 13); fill = Color.rgb(255, 246, 224);
                stroke = Color.rgb(224, 177, 105);
            } else {
                textColor = Color.rgb(15, 108, 89); fill = Color.rgb(232, 247, 241);
                stroke = Color.rgb(166, 207, 191);
            }
            label.setTextColor(textColor);
            GradientDrawable background = new GradientDrawable();
            background.setColor(fill);
            background.setCornerRadius(dp(label, 10));
            background.setStroke(dp(label, 1), stroke);
            label.setBackground(background);
            label.setOnClickListener(v -> {
                expandedPriority = section.priority.equals(expandedPriority)
                        ? "__NONE__" : section.priority;
                rebuildRows();
                notifyDataSetChanged();
            });
        }
    }

    private String priorityText(@NonNull View view, @NonNull String priority) {
        if (FamilyTask.PRIORITY_URGENT.equals(priority)) return view.getContext().getString(R.string.task_priority_urgent);
        if (FamilyTask.PRIORITY_HIGH.equals(priority)) return view.getContext().getString(R.string.task_priority_high);
        return view.getContext().getString(R.string.task_priority_normal);
    }

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
            stylePriorityBadge(task);
            String who = task.assignedMemberName.isEmpty()
                    ? binding.getRoot().getContext().getString(R.string.family_tasks_whole_family)
                    : task.assignedMemberName;
            String repeat = FamilyTask.REPEAT_NONE.equals(task.repeatType) ? "" : " • " + repeatText(task.repeatType);
            String created = task.createdByName.isEmpty() ? ""
                    : " • " + binding.getRoot().getContext().getString(
                    R.string.family_tasks_created_by, task.createdByName);
            String grocery = task.linkedGroceryCloudId.isEmpty()
                    && task.linkedGroceryItemId <= 0L ? "" : " • "
                    + binding.getRoot().getContext().getString(
                    R.string.family_tasks_linked_to_grocery);
            FamilyTaskLinkStore.Link links = FamilyTaskLinkStore.load(
                    binding.getRoot().getContext(), task);
            String finance = links.hasFinance() ? " • "
                    + binding.getRoot().getContext().getString(
                    R.string.family_tasks_linked_to_finance) : "";
            String loan = links.hasLoan() ? " • "
                    + binding.getRoot().getContext().getString(
                    R.string.family_tasks_linked_to_loan,
                    links.loanName.isEmpty()
                            ? binding.getRoot().getContext().getString(
                            R.string.family_tasks_loan_manager)
                            : links.loanName) : "";
            binding.taskMeta.setText(binding.getRoot().getContext().getString(
                    R.string.family_tasks_assigned_to, who) + repeat + created
                    + grocery + finance + loan);
            binding.taskNotes.setText(task.notes);
            binding.taskNotes.setVisibility(task.notes.isEmpty() ? View.GONE : View.VISIBLE);
            binding.taskCompleted.setOnCheckedChangeListener((button, checked) -> listener.onCompletedChanged(task, checked));
            binding.getRoot().setOnClickListener(v -> listener.onEdit(task));
            binding.taskHistoryButton.setOnClickListener(v ->
                    FamilyTaskActivityTimelineDialog.show(binding.getRoot().getContext(), task));
            binding.taskLinkButton.setOnClickListener(v -> FamilyTaskLinkDialog.show(
                    binding.getRoot().getContext(), task, () -> {
                        int position = getBindingAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position);
                    }));
            binding.editTaskButton.setOnClickListener(v -> listener.onEdit(task));
            binding.deleteTaskButton.setOnClickListener(v -> listener.onDelete(task));
        }
        private void stylePriorityBadge(@NonNull FamilyTask task) {
            int textColor;
            int fill;
            int stroke;
            if (FamilyTask.PRIORITY_URGENT.equals(task.priority)) {
                textColor = Color.rgb(164, 43, 62); fill = Color.rgb(255, 235, 239);
                stroke = Color.rgb(220, 118, 136);
            } else if (FamilyTask.PRIORITY_HIGH.equals(task.priority)) {
                textColor = Color.rgb(147, 91, 13); fill = Color.rgb(255, 246, 224);
                stroke = Color.rgb(224, 177, 105);
            } else {
                textColor = Color.rgb(15, 108, 89); fill = Color.rgb(232, 247, 241);
                stroke = Color.rgb(166, 207, 191);
            }
            binding.taskPriority.setGravity(Gravity.CENTER);
            binding.taskPriority.setTextColor(textColor);
            GradientDrawable background = new GradientDrawable();
            background.setColor(fill);
            background.setCornerRadius(dp(binding.taskPriority, 11));
            background.setStroke(dp(binding.taskPriority, 1), stroke);
            binding.taskPriority.setBackground(background);
        }
        private String dueText(FamilyTask task) {
            if (FamilyTask.STATUS_COMPLETED.equals(task.status)) {
                long completedAt = task.completedAt > 0L ? task.completedAt : task.updatedAt;
                String completedDate = DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(completedAt));
                String actor = task.completedByName.isEmpty()
                        ? "Completed" : binding.getRoot().getContext().getString(
                        R.string.family_tasks_completed_by, task.completedByName);
                return actor + " • " + completedDate;
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
