package com.tridev.familyhub.feature.reminders.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.databinding.ItemReminderBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.DateFormat;

/** RecyclerView adapter for locally scheduled reminders. */
public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.ReminderViewHolder> {

    public interface ReminderActionListener {
        void onEdit(Reminder reminder);

        void onDelete(Reminder reminder);

        void onEnabledChanged(Reminder reminder, boolean isEnabled);

        void onStatusChanged(Reminder reminder);

        void onSeen(Reminder reminder);

        void onOpenModule(Reminder reminder);
    }

    private final List<Reminder> reminders = new ArrayList<>();
    private final ReminderActionListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

    public ReminderAdapter(ReminderActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<Reminder> updatedReminders) {
        reminders.clear();
        reminders.addAll(updatedReminders);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ReminderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReminderBinding binding = ItemReminderBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ReminderViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ReminderViewHolder holder, int position) {
        holder.bind(reminders.get(position));
    }

    @Override
    public int getItemCount() {
        return reminders.size();
    }

    class ReminderViewHolder extends RecyclerView.ViewHolder {
        private final ItemReminderBinding binding;

        ReminderViewHolder(ItemReminderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Reminder reminder) {
            binding.reminderTitle.setText(reminder.title);
            binding.reminderDetail.setText(formatDetail(reminder));
            binding.reminderNote.setText(reminder.note);
            binding.reminderNote.setVisibility(reminder.note.trim().isEmpty()
                    ? android.view.View.GONE
                    : android.view.View.VISIBLE);
            binding.reminderCollaborationTimeline.setText(collaborationTimeline(reminder));
            binding.reminderStatusButton.setText(friendly(reminder.collaborationStatus) + "  ›");
            binding.reminderStatusButton.setOnClickListener(v -> listener.onStatusChanged(reminder));
            boolean linked = !reminder.relatedModule.trim().isEmpty();
            binding.reminderOpenModuleButton.setVisibility(linked
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            if (linked) {
                String item = reminder.relatedItemTitle.trim().isEmpty()
                        ? "" : " • " + reminder.relatedItemTitle.trim();
                binding.reminderOpenModuleButton.setText(
                        "Open " + friendly(reminder.relatedModule) + item);
                binding.reminderOpenModuleButton.setOnClickListener(
                        v -> listener.onOpenModule(reminder));
            }
            if (reminder.isShared && reminder.seenAt == 0L) listener.onSeen(reminder);
            binding.reminderEnabledSwitch.setOnCheckedChangeListener(null);
            binding.reminderEnabledSwitch.setChecked(reminder.isEnabled);
            binding.reminderEnabledSwitch.setOnCheckedChangeListener((buttonView, enabled) -> {
                if (buttonView.isPressed()) {
                    listener.onEnabledChanged(reminder, enabled);
                }
            });
            binding.getRoot().setAlpha(reminder.isEnabled ? 1f : 0.58f);
            binding.getRoot().setOnClickListener(v -> listener.onEdit(reminder));
            binding.editReminderButton.setOnClickListener(v -> listener.onEdit(reminder));
            binding.deleteReminderButton.setOnClickListener(v -> listener.onDelete(reminder));
        }

        private String formatDetail(Reminder reminder) {
            Date date = new Date(reminder.reminderAt);
            String smartMeta = " · " + friendly(reminder.priority)
                    + " · " + friendly(reminder.category)
                    + (reminder.assignedMemberName.trim().isEmpty()
                    ? "" : " · " + reminder.assignedMemberName.trim());
            if (!Reminder.REPEAT_ONCE.equals(reminder.repeatType)) {
                return binding.getRoot().getContext().getString(
                        R.string.reminder_daily_at,
                        timeFormat.format(date)
                ) + " · " + friendly(reminder.repeatType) + smartMeta;
            }
            return dateFormat.format(date) + " · " + timeFormat.format(date) + smartMeta;
        }

        private String friendly(String value) {
            if (value == null || value.trim().isEmpty()) return "General";
            String clean = value.trim().toLowerCase(Locale.getDefault());
            return clean.substring(0, 1).toUpperCase(Locale.getDefault()) + clean.substring(1);
        }

        private String collaborationTimeline(Reminder reminder) {
            StringBuilder text = new StringBuilder("Status • ")
                    .append(friendly(reminder.collaborationStatus));
            if (reminder.seenAt > 0L) text.append("  ·  Seen ").append(shortTime(reminder.seenAt));
            if (reminder.acceptedAt > 0L) text.append("  ·  Accepted ").append(shortTime(reminder.acceptedAt));
            if (reminder.startedAt > 0L) text.append("  ·  Started ").append(shortTime(reminder.startedAt));
            if (reminder.completedAt > 0L) {
                text.append("  ·  Completed ").append(shortTime(reminder.completedAt));
                if (!reminder.completedByName.trim().isEmpty()) {
                    text.append(" by ").append(reminder.completedByName.trim());
                }
            }
            return text.toString();
        }

        private String shortTime(long value) {
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(value));
        }
    }
}
