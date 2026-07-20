package com.tridev.familyhub.feature.finance.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.databinding.ItemFinanceEntryBinding;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders local finance entries and routes edit/delete actions to the screen. */
public class FinanceEntryAdapter extends RecyclerView.Adapter<FinanceEntryAdapter.EntryViewHolder> {

    public interface EntryActionListener {
        void onEdit(FinanceEntry entry);

        void onDelete(FinanceEntry entry);
    }

    private final List<FinanceEntry> entries = new ArrayList<>();
    private final EntryActionListener listener;
    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final SimpleDateFormat storedDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat displayDateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public FinanceEntryAdapter(EntryActionListener listener) {
        this.listener = listener;
        storedDateFormat.setLenient(false);
    }

    public void submitList(List<FinanceEntry> updatedEntries) {
        entries.clear();
        entries.addAll(updatedEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EntryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFinanceEntryBinding binding = ItemFinanceEntryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new EntryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryViewHolder holder, int position) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    class EntryViewHolder extends RecyclerView.ViewHolder {
        private final ItemFinanceEntryBinding binding;

        EntryViewHolder(ItemFinanceEntryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FinanceEntry entry) {
            boolean isIncome = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
            Context context = binding.getRoot().getContext();
            binding.entryTypeSymbol.setText(isIncome ? "+" : "−");
            binding.entryTypeSymbol.setBackgroundResource(isIncome
                    ? R.drawable.bg_income_avatar
                    : R.drawable.bg_expense_avatar);
            binding.entryCategory.setText(entry.category);
            binding.entryDetails.setText(details(entry));
            binding.entryAmount.setText((isIncome ? "+" : "−") + currencyFormatter.format(entry.amount));
            binding.entryAmount.setTextColor(ContextCompat.getColor(
                    context, isIncome ? R.color.fh_success : R.color.fh_tertiary
            ));
            binding.getRoot().setOnClickListener(v -> listener.onEdit(entry));
            binding.editEntryButton.setOnClickListener(v -> listener.onEdit(entry));
            binding.deleteEntryButton.setOnClickListener(v -> listener.onDelete(entry));
        }

        private String details(FinanceEntry entry) {
            String date = displayDate(entry.transactionDate);
            return TextUtils.isEmpty(entry.note) ? date : date + " · " + entry.note;
        }

        private String displayDate(String storedDate) {
            try {
                Date date = storedDateFormat.parse(storedDate);
                return date == null ? storedDate : displayDateFormat.format(date);
            } catch (ParseException exception) {
                return storedDate;
            }
        }
    }
}
