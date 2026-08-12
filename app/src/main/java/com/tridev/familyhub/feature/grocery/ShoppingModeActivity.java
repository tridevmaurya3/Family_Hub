package com.tridev.familyhub.feature.grocery;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetProvider;
import com.tridev.familyhub.feature.grocery.widget.GroceryWidgetPurchaseActivity;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Distraction-free, high-visibility checklist for use while shopping. */
public class ShoppingModeActivity extends AppCompatActivity {
    private GroceryRepository repository;
    private LinearLayout list;
    private TextView summary;
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(
            new Locale("en", "IN"));

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        repository = new GroceryRepository(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(248, 251, 249));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 30, true); back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label(getString(R.string.grocery_shopping_mode), 20, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(header);

        summary = label("", 13, true);
        summary.setTextColor(Color.rgb(15, 108, 89));
        summary.setPadding(dp(12), 0, dp(12), 0);
        summary.setBackground(round(Color.rgb(226, 244, 238), 12));
        root.addView(summary, new LinearLayout.LayoutParams(-1, dp(46)));

        MaterialSwitch awake = new MaterialSwitch(this);
        awake.setText(R.string.grocery_keep_screen_awake);
        awake.setChecked(false);
        awake.setOnCheckedChangeListener((button, checked) -> {
            if (checked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        root.addView(awake, new LinearLayout.LayoutParams(-1, dp(48)));

        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); load(); }

    private void load() {
        repository.loadItems("", items -> render(items));
    }

    private void render(List<GroceryItem> items) {
        list.removeAllViews();
        Map<String, java.util.ArrayList<GroceryItem>> groups = new LinkedHashMap<>();
        int pending = 0; double estimated = 0D;
        for (GroceryItem item : items) {
            if (item.isPurchased) continue;
            pending++; estimated += Math.max(0D, item.estimatedCost);
            String category = item.category.isEmpty()
                    ? getString(R.string.grocery_uncategorized) : item.category;
            groups.computeIfAbsent(category, key -> new java.util.ArrayList<>()).add(item);
        }
        summary.setText(getString(R.string.grocery_shopping_summary,
                pending, currency.format(estimated)));
        for (Map.Entry<String, java.util.ArrayList<GroceryItem>> group : groups.entrySet()) {
            TextView heading = label(group.getKey() + "  (" + group.getValue().size() + ")",
                    14, true);
            heading.setTextColor(Color.rgb(15, 108, 89));
            heading.setPadding(dp(12), 0, dp(12), 0);
            heading.setBackground(round(Color.rgb(226, 244, 238), 10));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(38));
            hp.topMargin = dp(8); list.addView(heading, hp);
            for (GroceryItem item : group.getValue()) {
                CheckBox row = new CheckBox(this);
                String detail = item.name + (item.quantity.isEmpty() ? ""
                        : "  •  " + item.quantity);
                row.setText(detail); row.setTextSize(16); row.setMinHeight(dp(58));
                row.setPadding(dp(8), 0, dp(8), 0);
                row.setBackground(round(Color.WHITE, 10));
                row.setOnCheckedChangeListener((button, checked) -> {
                    if (!checked) return;
                    button.setChecked(false);
                    startActivity(new Intent(this, GroceryWidgetPurchaseActivity.class)
                            .putExtra(GroceryWidgetProvider.EXTRA_ITEM_ID, item.id));
                });
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(60));
                rp.topMargin = dp(4); list.addView(row, rp);
            }
        }
        if (pending == 0) {
            TextView done = label(getString(R.string.grocery_shopping_complete), 18, true);
            done.setGravity(Gravity.CENTER); done.setTextColor(Color.rgb(15,108,89));
            list.addView(done, new LinearLayout.LayoutParams(-1, dp(160)));
        }
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 40, 38)); view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color);
        d.setCornerRadius(dp(radius)); d.setStroke(dp(1), Color.rgb(211,221,216)); return d;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
