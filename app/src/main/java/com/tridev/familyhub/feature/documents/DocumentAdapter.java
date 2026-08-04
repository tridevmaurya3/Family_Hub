package com.tridev.familyhub.feature.documents;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.databinding.ItemDocumentBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders professional Documents Vault cards and routes protected actions. */
public class DocumentAdapter
        extends RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder> {

    public interface DocumentActionListener {
        void onOpen(@NonNull DocumentEntry document);

        void onShare(@NonNull DocumentEntry document);

        void onEdit(@NonNull DocumentEntry document);

        void onToggleFavorite(@NonNull DocumentEntry document);

        void onDelete(@NonNull DocumentEntry document);

        boolean isFavorite(@NonNull DocumentEntry document);
    }

    private final List<DocumentEntry> documents = new ArrayList<>();
    private final DocumentActionListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
    private int reminderDays = DocumentVaultPreferences.DEFAULT_REMINDER_DAYS;

    public DocumentAdapter(@NonNull DocumentActionListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitList(@NonNull List<DocumentEntry> updatedDocuments) {
        documents.clear();
        documents.addAll(updatedDocuments);
        notifyDataSetChanged();
    }

    public void setReminderDays(int reminderDays) {
        this.reminderDays = reminderDays;
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return documents.get(position).id;
    }

    @NonNull
    @Override
    public DocumentViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        ItemDocumentBinding binding = ItemDocumentBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new DocumentViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(
            @NonNull DocumentViewHolder holder,
            int position
    ) {
        holder.bind(documents.get(position));
    }

    @Override
    public int getItemCount() {
        return documents.size();
    }

    class DocumentViewHolder extends RecyclerView.ViewHolder {

        private final ItemDocumentBinding binding;

        DocumentViewHolder(@NonNull ItemDocumentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull DocumentEntry document) {
            Context context = binding.getRoot().getContext();
            long now = System.currentTimeMillis();
            String status = DocumentExpiryPolicy.status(
                    document.expiryAt,
                    now,
                    reminderDays
            );

            binding.documentTitle.setText(document.title);
            binding.documentCategory.setText(document.category);
            binding.documentDate.setText(context.getString(
                    R.string.documents_vault_created_on,
                    dateFormat.format(new Date(document.createdAt))
            ));
            bindStatus(context, document, status, now);
            bindFavorite(document);

            binding.getRoot().setOnClickListener(
                    view -> listener.onOpen(document)
            );
            binding.openDocumentButton.setOnClickListener(
                    view -> listener.onOpen(document)
            );
            binding.shareDocumentButton.setOnClickListener(
                    view -> listener.onShare(document)
            );
            binding.editDocumentButton.setOnClickListener(
                    view -> listener.onEdit(document)
            );
            binding.favoriteDocumentButton.setOnClickListener(
                    view -> listener.onToggleFavorite(document)
            );
            binding.deleteDocumentButton.setOnClickListener(
                    view -> listener.onDelete(document)
            );
        }

        private void bindFavorite(@NonNull DocumentEntry document) {
            boolean favorite = listener.isFavorite(document);
            binding.favoriteDocumentButton.setIconResource(favorite
                    ? R.drawable.ic_document_star
                    : R.drawable.ic_document_star_outline);
            binding.favoriteDocumentButton.setContentDescription(
                    binding.getRoot().getContext().getString(favorite
                            ? R.string.documents_vault_unfavorite
                            : R.string.documents_vault_favorite)
            );
        }

        private void bindStatus(
                @NonNull Context context,
                @NonNull DocumentEntry document,
                @NonNull String status,
                long now
        ) {
            int containerColor;
            int accentColor;
            int statusLabel;
            String expiryText;

            if (DocumentExpiryPolicy.STATUS_EXPIRED.equals(status)) {
                containerColor = R.color.fh_error_container;
                accentColor = R.color.fh_error;
                statusLabel = R.string.documents_vault_status_expired;
                expiryText = context.getString(
                        R.string.documents_vault_expired_on,
                        dateFormat.format(new Date(document.expiryAt))
                );
            } else if (DocumentExpiryPolicy.STATUS_EXPIRING.equals(status)) {
                containerColor = R.color.fh_warning_container;
                accentColor = R.color.fh_warning;
                statusLabel = R.string.documents_vault_status_expiring;
                long days = DocumentExpiryPolicy.daysRemaining(
                        document.expiryAt,
                        now
                );
                expiryText = days <= 0L
                        ? context.getString(
                        R.string.documents_vault_expires_today
                )
                        : context.getString(
                        R.string.documents_vault_expires_in,
                        days
                );
            } else if (DocumentExpiryPolicy.STATUS_VALID.equals(status)) {
                containerColor = R.color.fh_success_container;
                accentColor = R.color.fh_success;
                statusLabel = R.string.documents_vault_status_valid;
                expiryText = context.getString(
                        R.string.documents_vault_valid_until,
                        dateFormat.format(new Date(document.expiryAt))
                );
            } else {
                containerColor = R.color.fh_info_container;
                accentColor = R.color.fh_info;
                statusLabel = R.string.documents_vault_status_no_expiry;
                expiryText = context.getString(
                        R.string.documents_vault_no_expiry
                );
            }

            int resolvedContainer = ContextCompat.getColor(
                    context,
                    containerColor
            );
            int resolvedAccent = ContextCompat.getColor(
                    context,
                    accentColor
            );
            binding.documentStatusCard.setCardBackgroundColor(
                    resolvedContainer
            );
            binding.documentStatusCard.setStrokeColor(resolvedAccent);
            binding.documentStatus.setText(statusLabel);
            binding.documentStatus.setTextColor(resolvedAccent);
            binding.documentExpiry.setText(expiryText);
            binding.documentExpiry.setTextColor(resolvedAccent);
        }
    }
}
