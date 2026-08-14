package com.tridev.familyhub.feature.grocery;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional checkpoint shown immediately after a grocery purchase is completed.
 * It lets the user choose the exact existing MoneyManager Bank/Credit Card for
 * THIS purchase only.
 *
 * If MoneyManager is absent, not trusted, or has no active accounts, purchase
 * completion remains untouched and the bridge simply retries later.
 */
public final class GroceryMoneyManagerAccountPicker {

    private GroceryMoneyManagerAccountPicker() { }

    public static void chooseForCompletedPurchase(
            @NonNull Activity activity,
            @NonNull GroceryItem item,
            @NonNull Runnable continueSync) {
        new Thread(() -> {
            GroceryMoneyManagerBridge.AccountCatalog catalog =
                    GroceryMoneyManagerBridge.loadAccountCatalog(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!catalog.available || catalog.choices.isEmpty()) {
                    GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                            activity, item, null);
                    continueSync.run();
                    return;
                }
                showPicker(activity, item, catalog.choices, continueSync);
            });
        }, "GroceryMoneyAccountCatalog").start();
    }

    private static void showPicker(
            @NonNull Activity activity,
            @NonNull GroceryItem item,
            @NonNull List<GroceryMoneyManagerBridge.AccountChoice> choices,
            @NonNull Runnable continueSync) {
        List<String> labels = new ArrayList<>();
        for (GroceryMoneyManagerBridge.AccountChoice choice : choices) {
            labels.add(choice.label);
        }
        labels.add("Choose later in MoneyManager");
        AtomicBoolean continued = new AtomicBoolean(false);

        Runnable reviewLater = () -> {
            GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                    activity, item, null);
            if (continued.compareAndSet(false, true)) continueSync.run();
        };

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Paid from")
                .setMessage("Select the MoneyManager bank or credit card used for this grocery purchase.")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String ref = which >= 0 && which < choices.size()
                            ? choices.get(which).canonicalRef
                            : null;
                    GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                            activity, item, ref);
                    if (continued.compareAndSet(false, true)) {
                        continueSync.run();
                    }
                })
                .setNegativeButton("Choose later", (dialog, which) -> reviewLater.run())
                .setOnCancelListener(dialog -> reviewLater.run())
                .show();
    }
}
