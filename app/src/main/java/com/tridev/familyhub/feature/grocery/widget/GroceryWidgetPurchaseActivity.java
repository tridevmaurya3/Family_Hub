package com.tridev.familyhub.feature.grocery.widget;

import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;

/** Compact purchase checkpoint opened from a home-screen widget item. */
public class GroceryWidgetPurchaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long id = getIntent().getLongExtra(
                GroceryWidgetProvider.EXTRA_ITEM_ID, 0L);
        if (id <= 0L) {
            finish();
            return;
        }
        GroceryWidgetExecutors.DATABASE.execute(() -> {
            GroceryItem item = FamilyHubDatabase.getInstance(this)
                    .groceryItemDao().getById(id);
            runOnUiThread(() -> {
                if (item == null) finish(); else showCheckpoint(item);
            });
        });
    }

    private void showCheckpoint(GroceryItem item) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, 0, pad, 0);

        EditText price = new EditText(this);
        price.setHint(R.string.grocery_actual_cost);
        price.setSingleLine(true);
        price.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (item.actualCost > 0D) price.setText(String.valueOf(item.actualCost));
        form.addView(price);

        EditText quantity = new EditText(this);
        quantity.setHint(R.string.grocery_quantity);
        quantity.setSingleLine(true);
        quantity.setInputType(InputType.TYPE_CLASS_TEXT);
        quantity.setText(item.quantity);
        form.addView(quantity);

        Spinner category = new Spinner(this);
        String[] categories = getResources().getStringArray(
                R.array.grocery_category_labels);
        category.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categories));
        for (int i = 1; i < categories.length; i++) {
            if (categories[i].equalsIgnoreCase(item.category)) category.setSelection(i);
        }
        form.addView(category);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.grocery_complete_title)
                .setMessage(getString(R.string.grocery_complete_message,
                        item.name))
                .setView(form)
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setNeutralButton(R.string.grocery_skip_and_complete,
                        (d, w) -> complete(item))
                .setPositiveButton(R.string.save, (d, w) -> {
                    String priceValue = price.getText().toString().trim();
                    if (!priceValue.isEmpty()) {
                        try { item.actualCost = Double.parseDouble(priceValue); }
                        catch (NumberFormatException ignored) { item.actualCost = 0D; }
                    }
                    item.quantity = quantity.getText().toString().trim();
                    if (category.getSelectedItemPosition() > 0) {
                        item.category = String.valueOf(category.getSelectedItem());
                    }
                    complete(item);
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void complete(GroceryItem item) {
        new GroceryRepository(this).setPurchased(item, true, () -> {
            GroceryWidgetProvider.refreshAll(this);
            finish();
        });
    }
}
