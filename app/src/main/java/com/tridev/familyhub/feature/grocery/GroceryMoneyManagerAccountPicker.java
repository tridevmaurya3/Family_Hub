package com.tridev.familyhub.feature.grocery;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small optional checkpoint shown immediately before a grocery item is marked
 * purchased. It lets the user choose the exact existing MoneyManager Bank/Credit
 * Card for THIS purchase only.
 *
 * If MoneyManager is absent, not trusted, or has no active accounts, purchase
 * completion continues normally and MoneyManager mapping can be reviewed later.
 */
public final class GroceryMoneyManagerAccountPicker {

    private GroceryMoneyManagerAccountPicker() { }

    public static void chooseForNextPurchase(
            @NonNull Activity activity,
            @NonNull GroceryItem item,
            @NonNull Runnable continuePurchase) {
        new Thread(() -> {
            GroceryMoneyManagerBridge.AccountCatalog catalog =
                    GroceryMoneyManagerBridge.loadAccountCatalog(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!catalog.available || catalog.choices.isEmpty()) {
                    GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                            activity, item, null);
                    continuePurchase.run();
                    return;
                }
                showPicker(activity, item, catalog.choices, continuePurchase);
            });
        }, "GroceryMoneyAccountCatalog").start();
    }

    private static void showPicker(
            @NonNull Activity activity,
            @NonNull GroceryItem item,
            @NonNull List<GroceryMoneyManagerBridge.AccountChoice> choices,
            @NonNull Runnable continuePurchase) {
        List<String> labels = new ArrayList<>();
        for (GroceryMoneyManagerBridge.AccountChoice choice : choices) {
            labels.add(choice.label);
        }
        labels.add("Choose later in MoneyManager");
        AtomicBoolean continued = new AtomicBoolean(false);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Paid from")
                .setMessage("Select the MoneyManager bank or credit card used for this purchase.")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String ref = which >= 0 && which < choices.size()
                            ? choices.get(which).canonicalRef
                            : null;
                    GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                            activity, item, ref);
                    if (continued.compareAndSet(false, true)) {
                        continuePurchase.run();
                    }
                })
                .setNegativeButton("Cancel purchase", (dialog, which) -> {
                    // Intentionally do not continue. The grocery item stays pending.
                })
                .setOnCancelListener(dialog -> {
                    // Back/outside tap cancels this purchase completion checkpoint.
                })
                .show();
    }
}
