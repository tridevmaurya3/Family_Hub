package com.tridev.familyhub.feature.grocery.widget;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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
        String[] actions = {
                getString(R.string.grocery_update_price),
                getString(R.string.grocery_update_category),
                getString(R.string.grocery_update_quantity)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.grocery_complete_title)
                .setMessage(getString(R.string.grocery_complete_message,
                        item.name))
                .setItems(actions, (dialog, which) -> showFieldEditor(item, which))
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setPositiveButton(R.string.grocery_skip_and_complete,
                        (d, w) -> complete(item))
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void showFieldEditor(GroceryItem item, int action) {
        View editor;
        if (action == 1) {
            Spinner spinner = new Spinner(this);
            String[] categories = getResources().getStringArray(
                    R.array.grocery_category_labels);
            spinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, categories));
            int selected = 0;
            for (int i = 1; i < categories.length; i++) {
                if (categories[i].equalsIgnoreCase(item.category)) selected = i;
            }
            spinner.setSelection(selected);
            editor = spinner;
        } else {
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint(action == 0 ? R.string.grocery_actual_cost
                    : R.string.grocery_quantity);
            input.setText(action == 0 && item.actualCost > 0
                    ? String.valueOf(item.actualCost) : item.quantity);
            input.setInputType(action == 0
                    ? InputType.TYPE_CLASS_NUMBER
                            | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT);
            editor = input;
        }
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout shell = new LinearLayout(this);
        shell.setPadding(pad, 0, pad, 0);
        shell.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(actionsTitle(action))
                .setView(shell)
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (editor instanceof Spinner) {
                        int position = ((Spinner) editor).getSelectedItemPosition();
                        if (position <= 0) { showFieldEditor(item, action); return; }
                        item.category = String.valueOf(
                                ((Spinner) editor).getSelectedItem());
                    } else if (action == 0) {
                        try {
                            item.actualCost = Double.parseDouble(
                                    ((EditText) editor).getText().toString().trim());
                        } catch (NumberFormatException ignored) { item.actualCost = 0D; }
                    } else {
                        item.quantity = ((EditText) editor).getText().toString().trim();
                    }
                    complete(item);
                }).show();
    }

    private int actionsTitle(int action) {
        return action == 0 ? R.string.grocery_update_price
                : action == 1 ? R.string.grocery_update_category
                : R.string.grocery_update_quantity;
    }

    private void complete(GroceryItem item) {
        new GroceryRepository(this).setPurchased(item, true, () -> {
            GroceryWidgetProvider.refreshAll(this);
            finish();
        });
    }
}
