package com.tridev.familyhub.feature.grocery;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.ScheduleDropdownView;

import java.util.function.Consumer;

/** Shared name entry for the Grocery main form and its overlay form. */
public final class GroceryStoreNameDialog {
    private GroceryStoreNameDialog() { }

    public static void show(Context context, boolean overlay, Consumer<String> onAdded) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(R.string.grocery_new_store_hint);
        input.setTextSize(14);
        LinearLayout content = new LinearLayout(context);
        int padding = ScheduleDropdownView.dp(context, 16);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(context,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(R.string.grocery_add_store)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.grocery_store_add_action, null)
                .create();
        if (overlay && dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                if (!GroceryOptionCatalog.addCustomStore(context, value)) {
                    input.setError(context.getString(R.string.grocery_store_name_required));
                    return;
                }
                // Reuse the canonical option when only letter case differs.
                for (String store : GroceryOptionCatalog.storePresets(context)) {
                    if (store.equalsIgnoreCase(value)) {
                        onAdded.accept(store);
                        break;
                    }
                }
                dialog.dismiss();
            });
            input.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
    }
}
