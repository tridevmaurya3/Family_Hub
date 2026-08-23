package com.tridev.familyhub.feature.grocery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.databinding.ActivityGroceryStoreAnalyticsBinding;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/** Current-month store spend, cheapest-price wins, saving and price trends. */
public class GroceryStoreAnalyticsActivity extends AppCompatActivity {
    private ActivityGroceryStoreAnalyticsBinding binding;
    private final List<GroceryPurchase> purchases = new ArrayList<>();
    private final NumberFormat money = NumberFormat.getCurrencyInstance(
            new Locale("en", "IN"));
    private String selectedCategory = "All";
    private String selectedStore = "All";

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityGroceryStoreAnalyticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        installSystemBarInsets();
        binding.storeAnalyticsOverview.setNavigationAction(
                R.drawable.ic_arrow_back, R.string.back, view -> finish());
        binding.storeAnalyticsPdf.setOnClickListener(view -> export(true));
        binding.storeAnalyticsExcel.setOnClickListener(view -> export(false));
        new GroceryRepository(this).loadCurrentMonthPurchases(rows -> {
            purchases.clear();
            purchases.addAll(rows);
            setupFilters();
            render();
        });
    }

    private void installSystemBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(
                binding.storeAnalyticsRoot,
                (view, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return windowInsets;
                });
        ViewCompat.requestApplyInsets(binding.storeAnalyticsRoot);
    }

    private void setupFilters() {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> stores = new LinkedHashSet<>();
        categories.add("All"); stores.add("All");
        for (GroceryPurchase purchase : purchases) {
            categories.add(category(purchase));
            stores.add(store(purchase));
        }
        binding.storeAnalyticsCategoryFilter.setAdapter(new ArrayAdapter<>(this,
                R.layout.item_form_dropdown,
                new ArrayList<>(categories)));
        binding.storeAnalyticsStoreFilter.setAdapter(new ArrayAdapter<>(this,
                R.layout.item_form_dropdown,
                new ArrayList<>(stores)));
        binding.storeAnalyticsCategoryFilter.setText("All", false);
        binding.storeAnalyticsStoreFilter.setText("All", false);
        binding.storeAnalyticsCategoryFilter.setOnItemClickListener((p, v, i, id) -> {
            selectedCategory = String.valueOf(p.getItemAtPosition(i)); render();
        });
        binding.storeAnalyticsStoreFilter.setOnItemClickListener((p, v, i, id) -> {
            selectedStore = String.valueOf(p.getItemAtPosition(i)); render();
        });
    }

    private List<GroceryPurchase> filtered() {
        List<GroceryPurchase> result = new ArrayList<>();
        for (GroceryPurchase purchase : purchases) {
            boolean categoryMatches = "All".equals(selectedCategory)
                    || selectedCategory.equalsIgnoreCase(category(purchase));
            boolean storeMatches = "All".equals(selectedStore)
                    || selectedStore.equalsIgnoreCase(store(purchase));
            if (categoryMatches && storeMatches) result.add(purchase);
        }
        return result;
    }

    private void render() {
        List<GroceryPurchase> rows = filtered();
        Map<String, StoreStat> stores = new LinkedHashMap<>();
        Map<String, Double> cheapest = cheapestByComparablePurchase(purchases);
        double spend = 0D, saving = 0D;
        for (GroceryPurchase row : rows) {
            String store = store(row);
            StoreStat stat = stores.computeIfAbsent(store, StoreStat::new);
            stat.spend += row.actualCost; stat.count++;
            stat.categories.add(category(row));
            Double best = cheapest.get(comparisonKey(row));
            if (best != null && Math.abs(row.actualCost - best) < 0.01D) stat.wins++;
            if (best != null && row.actualCost > best) saving += row.actualCost - best;
            spend += row.actualCost;
        }
        StoreStat bestStore = stores.values().stream()
                .max(Comparator.comparingInt(value -> value.wins)).orElse(null);
        binding.storeAnalyticsSpend.setText(getString(
                R.string.grocery_analytics_spend, money.format(spend), rows.size()));
        binding.storeAnalyticsSaving.setText(getString(
                R.string.grocery_analytics_saving, money.format(saving)));
        binding.storeAnalyticsBest.setText(getString(
                R.string.grocery_analytics_best,
                bestStore == null ? "—" : bestStore.name,
                bestStore == null ? 0 : bestStore.wins));
        renderStoreCards(new ArrayList<>(stores.values()));
        renderTrends(rows);
    }

    private void renderStoreCards(List<StoreStat> stats) {
        binding.storeAnalyticsCards.removeAllViews();
        stats.sort((a, b) -> Double.compare(b.spend, a.spend));
        if (stats.isEmpty()) {
            binding.storeAnalyticsCards.addView(label(
                    getString(R.string.grocery_store_analytics_empty)));
            return;
        }
        int rank = 1;
        for (StoreStat stat : stats) {
            MaterialCardView card = new MaterialCardView(this);
            card.setCardBackgroundColor(ContextCompat.getColor(this,
                    R.color.fh_module_grocery_container));
            card.setStrokeColor(ContextCompat.getColor(this, R.color.fh_module_grocery));
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(18));
            card.setCardElevation(dp(2));

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(14), dp(12), dp(14), dp(12));

            TextView title = label("#" + rank + "  " + stat.name);
            title.setTextSize(15);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(this, R.color.fh_module_grocery));
            title.setPadding(0, 0, 0, dp(5));
            content.addView(title);

            TextView amount = label(money.format(stat.spend)
                    + "  •  " + stat.count + " purchases");
            amount.setTextSize(13);
            amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
            amount.setPadding(0, 0, 0, dp(3));
            content.addView(amount);

            TextView detail = label(stat.wins + " best-price wins  •  "
                    + stat.categories.size() + " categories");
            detail.setTextSize(12);
            detail.setTextColor(ContextCompat.getColor(this, R.color.fh_text_secondary));
            detail.setPadding(0, 0, 0, 0);
            content.addView(detail);

            card.addView(content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(9);
            binding.storeAnalyticsCards.addView(card, params);
            rank++;
        }
    }

    private void renderTrends(List<GroceryPurchase> rows) {
        binding.storeAnalyticsTrends.removeAllViews();
        Map<String, List<GroceryPurchase>> byItem = new LinkedHashMap<>();
        rows.stream().sorted(Comparator.comparingLong(value -> value.purchasedAt))
                .forEach(row -> byItem.computeIfAbsent(row.itemName,
                        key -> new ArrayList<>()).add(row));
        int shown = 0;
        for (Map.Entry<String, List<GroceryPurchase>> entry : byItem.entrySet()) {
            List<GroceryPurchase> values = entry.getValue();
            if (values.size() < 2) continue;
            GroceryPurchase first = values.get(0), last = values.get(values.size() - 1);
            boolean lower = last.actualCost < first.actualCost;
            boolean higher = last.actualCost > first.actualCost;
            String arrow = lower ? " ↓ " : higher ? " ↑ " : " → ";

            MaterialCardView card = new MaterialCardView(this);
            int fill = lower ? R.color.fh_success_container
                    : higher ? R.color.fh_warning_container
                    : R.color.fh_primary_container;
            int accent = lower ? R.color.fh_success
                    : higher ? R.color.fh_warning
                    : R.color.fh_primary;
            card.setCardBackgroundColor(ContextCompat.getColor(this, fill));
            card.setStrokeColor(ContextCompat.getColor(this, accent));
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(16));
            card.setCardElevation(0);

            TextView trend = label(entry.getKey() + "\n"
                    + money.format(first.actualCost) + arrow + money.format(last.actualCost)
                    + "  •  " + store(last));
            trend.setTextSize(13);
            trend.setTypeface(trend.getTypeface(), Typeface.BOLD);
            trend.setTextColor(ContextCompat.getColor(this, accent));
            trend.setPadding(dp(13), dp(10), dp(13), dp(10));
            card.addView(trend);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            binding.storeAnalyticsTrends.addView(card, params);
            if (++shown == 8) break;
        }
        if (shown == 0) binding.storeAnalyticsTrends.addView(label(
                getString(R.string.grocery_price_trends_empty)));
    }

    private Map<String, Double> cheapestByComparablePurchase(List<GroceryPurchase> rows) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (GroceryPurchase row : rows) {
            if (row.actualCost <= 0D || row.storeName.trim().isEmpty()) continue;
            String key = comparisonKey(row);
            result.put(key, Math.min(result.getOrDefault(key, row.actualCost),
                    row.actualCost));
        }
        return result;
    }

    private String comparisonKey(GroceryPurchase row) {
        return row.itemName.toLowerCase(Locale.ENGLISH) + "|"
                + row.quantity.toLowerCase(Locale.ENGLISH);
    }

    private String store(GroceryPurchase purchase) {
        return purchase.storeName == null || purchase.storeName.trim().isEmpty()
                ? getString(R.string.grocery_store_not_specified)
                : purchase.storeName.trim();
    }

    private String category(GroceryPurchase purchase) {
        return purchase.category == null || purchase.category.trim().isEmpty()
                ? getString(R.string.grocery_uncategorized)
                : purchase.category.trim();
    }

    private TextView label(String value) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(13); text.setGravity(Gravity.START);
        text.setPadding(dp(12), dp(10), dp(12), dp(10));
        return text;
    }

    private void export(boolean pdf) {
        List<GroceryPurchase> rows = filtered();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File folder = new File(getCacheDir(), "grocery_reports");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException();
                File file = new File(folder, "Store_Analytics_"
                        + new SimpleDateFormat("yyyy_MM", Locale.ENGLISH).format(new Date())
                        + (pdf ? ".pdf" : ".xls"));
                if (pdf) GroceryReportExporter.pdf(file, rows);
                else GroceryReportExporter.excel(file, rows);
                runOnUiThread(() -> share(file, pdf));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.grocery_export_error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void share(File file, boolean pdf) {
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".backupfiles", file);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(pdf ? "application/pdf" : "application/vnd.ms-excel")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent,
                getString(R.string.grocery_share_report)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class StoreStat {
        final String name; final Set<String> categories = new LinkedHashSet<>();
        double spend; int count; int wins;
        StoreStat(String name) { this.name = name; }
    }
}
