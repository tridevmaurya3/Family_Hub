package com.tridev.familyhub.feature.passwordvault;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.PasswordEntry;
import com.tridev.familyhub.databinding.ItemPasswordEntryBinding;

import java.util.ArrayList;
import java.util.List;

/** Displays safe credential metadata; encrypted secrets are never bound to list rows. */
public class PasswordVaultAdapter
        extends RecyclerView.Adapter<PasswordVaultAdapter.EntryViewHolder> {

    public interface EntryActionListener {
        void onOpen(@NonNull PasswordEntry entry);

        void onDelete(@NonNull PasswordEntry entry);
    }

    private final List<PasswordEntry> entries = new ArrayList<>();
    private final EntryActionListener listener;

    public PasswordVaultAdapter(@NonNull EntryActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<PasswordEntry> updatedEntries) {
        entries.clear();
        entries.addAll(updatedEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EntryViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        ItemPasswordEntryBinding binding = ItemPasswordEntryBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new EntryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(
            @NonNull EntryViewHolder holder,
            int position
    ) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    class EntryViewHolder extends RecyclerView.ViewHolder {

        private final ItemPasswordEntryBinding binding;

        EntryViewHolder(@NonNull ItemPasswordEntryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull PasswordEntry entry) {
            binding.passwordEntryTitle.setText(entry.title);
            binding.passwordEntryWebsite.setText(
                    entry.website.isEmpty()
                            ? binding.getRoot().getContext().getString(
                                    R.string.vault_no_website
                            )
                            : entry.website
            );
            binding.getRoot().setOnClickListener(
                    view -> listener.onOpen(entry)
            );
            binding.openPasswordEntryButton.setOnClickListener(
                    view -> listener.onOpen(entry)
            );
            binding.deletePasswordEntryButton.setOnClickListener(
                    view -> listener.onDelete(entry)
            );
        }
    }
}
