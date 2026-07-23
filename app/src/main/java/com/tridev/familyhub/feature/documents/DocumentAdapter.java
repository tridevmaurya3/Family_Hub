package com.tridev.familyhub.feature.documents;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.databinding.ItemDocumentBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders document metadata and routes open/remove actions to the screen. */
public class DocumentAdapter
        extends RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder> {

    public interface DocumentActionListener {
        void onOpen(@NonNull DocumentEntry document);

        void onDelete(@NonNull DocumentEntry document);
    }

    private final List<DocumentEntry> documents = new ArrayList<>();
    private final DocumentActionListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public DocumentAdapter(@NonNull DocumentActionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<DocumentEntry> updatedDocuments) {
        documents.clear();
        documents.addAll(updatedDocuments);
        notifyDataSetChanged();
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
            binding.documentTitle.setText(document.title);
            binding.documentCategory.setText(document.category);
            binding.documentDate.setText(dateFormat.format(
                    new Date(document.createdAt)
            ));

            binding.getRoot().setOnClickListener(
                    view -> listener.onOpen(document)
            );
            binding.openDocumentButton.setOnClickListener(
                    view -> listener.onOpen(document)
            );
            binding.deleteDocumentButton.setOnClickListener(
                    view -> listener.onDelete(document)
            );
        }
    }
}
