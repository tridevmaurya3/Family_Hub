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

/** RecyclerView adapter for locally scheduled reminders. */
public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.ReminderViewHolder> {

    public interface ReminderActionListener {
        void onEdit(Reminder reminder);

        void onDelete(Reminder reminder);

        void onEnabledChanged(Reminder reminder, boolean isEnabled);
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
            if (Reminder.REPEAT_DAILY.equals(reminder.repeatType)) {
                return binding.getRoot().getContext().getString(
                        R.string.reminder_daily_at,
                        timeFormat.format(date)
                );
            }
            return dateFormat.format(date) + " · " + timeFormat.format(date);
        }
    }
}
