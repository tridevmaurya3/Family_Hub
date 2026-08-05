package com.tridev.familyhub.feature.search;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.ItemGlobalSearchResultBinding;
import java.util.ArrayList;
import java.util.List;

public class GlobalSearchAdapter extends RecyclerView.Adapter<GlobalSearchAdapter.Holder> {
    public interface Listener { void onOpen(@NonNull GlobalSearchResult result); }
    private final List<GlobalSearchResult> items = new ArrayList<>();
    private final Listener listener;
    public GlobalSearchAdapter(Listener listener) { this.listener = listener; }
    public void submit(List<GlobalSearchResult> results) { items.clear(); items.addAll(results); notifyDataSetChanged(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemGlobalSearchResultBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(items.get(position)); }
    @Override public int getItemCount() { return items.size(); }
    class Holder extends RecyclerView.ViewHolder {
        private final ItemGlobalSearchResultBinding binding;
        Holder(ItemGlobalSearchResultBinding binding) { super(binding.getRoot()); this.binding = binding; }
        void bind(GlobalSearchResult item) {
            binding.searchResultIcon.setImageResource(item.icon);
            binding.searchResultModule.setText(item.module);
            binding.searchResultTitle.setText(item.title);
            binding.searchResultDetail.setText(item.detail.isEmpty()
                    ? binding.getRoot().getContext().getString(R.string.global_search_open_module) : item.detail);
            binding.searchResultUrgent.setVisibility(item.urgent ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.getRoot().setOnClickListener(v -> listener.onOpen(item));
        }
    }
}
