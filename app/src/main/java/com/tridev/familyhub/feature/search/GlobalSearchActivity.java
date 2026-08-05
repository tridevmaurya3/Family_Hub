package com.tridev.familyhub.feature.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.search.SearchBarModel;
import com.tridev.familyhub.core.security.VaultCipher;
import com.tridev.familyhub.databinding.ActivityGlobalSearchBinding;
import com.tridev.familyhub.feature.main.MainActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Search, filter, recent-query and direct-navigation surface for all modules. */
public class GlobalSearchActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_QUERY = "initial_global_query";
    private static final String PREFS = "global_search_history";
    private static final String KEY_RECENT = "recent_queries";
    private static final String ENCRYPTED_PREFIX = "enc1:";
    private ActivityGlobalSearchBinding binding;
    private GlobalSearchRepository repository;
    private GlobalSearchAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::performSearch;
    @NonNull private String activeFilter = GlobalSearchRepository.FILTER_ALL;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGlobalSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new GlobalSearchRepository(this);
        adapter = new GlobalSearchAdapter(this::openResult);
        binding.globalSearchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.globalSearchResults.setAdapter(adapter);
        binding.globalSearchBack.setOnClickListener(v -> finish());
        binding.globalSearchBar.setModel(new SearchBarModel(
                getString(R.string.global_search_hint), "", false, false));
        binding.globalSearchBar.setOnQueryChangeListener(query -> scheduleSearch());
        binding.globalSearchBar.setOnSearchActionListener(query -> {
            saveRecent(query); performSearch();
        });
        binding.globalSearchFilters.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            activeFilter = filterFor(ids.get(0));
            scheduleSearch();
        });
        renderRecent();
        String initial = getIntent().getStringExtra(EXTRA_INITIAL_QUERY);
        if (initial != null && !initial.trim().isEmpty()) {
            binding.globalSearchBar.setQuery(initial.trim());
            saveRecent(initial.trim());
            performSearch();
        } else renderState(false, true);
    }

    private void scheduleSearch() {
        handler.removeCallbacks(searchRunnable);
        handler.postDelayed(searchRunnable, 220L);
    }

    private void performSearch() {
        String query = GlobalSearchPolicy.normalize(binding.globalSearchBar.getQuery());
        if (query.isEmpty()) { adapter.submit(new ArrayList<>()); renderState(false, true); return; }
        binding.globalSearchLoading.setVisibility(View.VISIBLE);
        binding.globalSearchEmpty.setVisibility(View.GONE);
        repository.search(query, activeFilter, results -> {
            if (binding == null) return;
            binding.globalSearchLoading.setVisibility(View.GONE);
            adapter.submit(results);
            renderState(!results.isEmpty(), false);
            if (results.isEmpty()) {
                binding.globalSearchEmptyTitle.setText(R.string.global_search_no_results);
                binding.globalSearchEmptyDetail.setText(getString(
                        R.string.global_search_no_results_detail, query));
            }
        });
    }

    private void renderState(boolean hasResults, boolean start) {
        binding.globalSearchResults.setVisibility(hasResults ? View.VISIBLE : View.GONE);
        binding.globalSearchEmpty.setVisibility(hasResults ? View.GONE : View.VISIBLE);
        if (start) {
            binding.globalSearchEmptyTitle.setText(R.string.global_search_start_title);
            binding.globalSearchEmptyDetail.setText(R.string.global_search_start_detail);
        }
    }

    private String filterFor(int id) {
        if (id == R.id.search_filter_family) return "Family";
        if (id == R.id.search_filter_reminders) return "Reminders";
        if (id == R.id.search_filter_finance) return "Finance";
        if (id == R.id.search_filter_grocery) return "Grocery";
        if (id == R.id.search_filter_documents) return "Documents";
        if (id == R.id.search_filter_health) return "Health";
        if (id == R.id.search_filter_vehicles) return "Vehicles";
        if (id == R.id.search_filter_property) return "Property";
        if (id == R.id.search_filter_notes) return "Notes";
        if (id == R.id.search_filter_planner) return "Planner";
        if (id == R.id.search_filter_vault) return "Vault";
        return GlobalSearchRepository.FILTER_ALL;
    }

    private void openResult(@NonNull GlobalSearchResult result) {
        saveRecent(binding.globalSearchBar.getQuery());
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, result.route)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void renderRecent() {
        binding.recentSearchChips.removeAllViews();
        for (String query : readRecent()) {
            Chip chip = new Chip(this);
            chip.setText(query);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                binding.globalSearchBar.setQuery(query);
                performSearch();
            });
            binding.recentSearchChips.addView(chip);
        }
        binding.recentSearchSection.setVisibility(
                binding.recentSearchChips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void saveRecent(String query) {
        String clean = GlobalSearchPolicy.normalize(query);
        if (clean.isEmpty()) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(clean);
        values.addAll(readRecent());
        List<String> limited = new ArrayList<>(values);
        if (limited.size() > 5) limited = limited.subList(0, 5);
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_RECENT, ENCRYPTED_PREFIX + VaultCipher.encrypt(
                            String.join("\u001F", limited))).apply();
        } catch (IllegalStateException encryptionError) {
            // Never fall back to plaintext if Android Keystore is unavailable.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_RECENT).apply();
        }
        renderRecent();
    }

    private List<String> readRecent() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENT, "");
        if (stored.isEmpty()) return new ArrayList<>();
        String plain;
        try {
            plain = stored.startsWith(ENCRYPTED_PREFIX)
                    ? VaultCipher.decryptOrThrow(stored.substring(ENCRYPTED_PREFIX.length()))
                    : stored;
        } catch (Exception error) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_RECENT).apply();
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>(Arrays.asList(plain.split("\u001F")));
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // Upgrade legacy plaintext history immediately after a successful read.
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RECENT,
                        ENCRYPTED_PREFIX + VaultCipher.encrypt(plain)).apply();
            } catch (IllegalStateException encryptionError) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_RECENT).apply();
                return new ArrayList<>();
            }
        }
        return values;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (repository != null) repository.close();
        binding = null;
        super.onDestroy();
    }
}
