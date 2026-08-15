package com.tridev.familyhub.feature.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wires Grocery and Finance dialogs to MoneyManager master data. */
public final class MoneyManagerFormAutoBinder implements Application.ActivityLifecycleCallbacks {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Map<View, Boolean> boundViews =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(new MoneyManagerFormAutoBinder());
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        View root = activity.getWindow().getDecorView();
        scan(activity, root);
        if (listeners.containsKey(activity)) return;
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> scan(activity, root);
        listeners.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private void scan(@NonNull Activity activity, @NonNull View root) {
        bindGrocery(activity, root);
        bindFinance(activity, root);
    }

    private void bindGrocery(@NonNull Activity activity, @NonNull View root) {
        MaterialAutoCompleteTextView account = root.findViewById(
                R.id.grocery_money_account_input);
        MaterialAutoCompleteTextView category = root.findViewById(
                R.id.grocery_money_category_input);
        TextView status = root.findViewById(R.id.grocery_money_catalog_status);
        if (account == null || category == null || status == null) return;
        if (boundViews.put(account, Boolean.TRUE) != null) return;

        account.setEnabled(false);
        category.setEnabled(false);
        status.setText(R.string.money_manager_catalog_loading);

        EXECUTOR.execute(() -> {
            MoneyManagerMasterCatalogBridge.Catalog catalog =
                    MoneyManagerMasterCatalogBridge.load(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!catalog.available) {
                    status.setText(R.string.money_manager_catalog_unavailable);
                    return;
                }

                account.setAdapter(new ArrayAdapter<>(activity,
                        R.layout.item_form_dropdown,
                        MoneyManagerMasterCatalogBridge.labels(catalog.accounts)));
                category.setAdapter(new ArrayAdapter<>(activity,
                        R.layout.item_form_dropdown,
                        MoneyManagerMasterCatalogBridge.labels(catalog.expenseCategories)));
                account.setEnabled(!catalog.accounts.isEmpty());
                category.setEnabled(!catalog.expenseCategories.isEmpty());

                MoneyManagerMasterCatalogBridge.Choice savedAccount =
                        MoneyManagerMasterCatalogBridge.findByRef(
                                catalog.accounts,
                                MoneyManagerMasterCatalogBridge.groceryDefaultAccountRef(activity));
                MoneyManagerMasterCatalogBridge.Choice savedCategory =
                        MoneyManagerMasterCatalogBridge.findByRef(
                                catalog.expenseCategories,
                                MoneyManagerMasterCatalogBridge.groceryDefaultCategoryRef(activity));
                if (savedAccount != null) account.setText(savedAccount.label, false);
                if (savedCategory != null) category.setText(savedCategory.label, false);

                account.setOnItemClickListener((parent, view, position, id) -> {
                    if (position < 0 || position >= catalog.accounts.size()) return;
                    MoneyManagerMasterCatalogBridge.Choice choice = catalog.accounts.get(position);
                    MoneyManagerMasterCatalogBridge.rememberGroceryDefaultAccount(
                            activity, choice.ref);
                    MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                            activity, choice.label, choice.ref);
                });
                category.setOnItemClickListener((parent, view, position, id) -> {
                    if (position < 0 || position >= catalog.expenseCategories.size()) return;
                    MoneyManagerMasterCatalogBridge.Choice choice =
                            catalog.expenseCategories.get(position);
                    MoneyManagerMasterCatalogBridge.rememberGroceryDefaultCategory(
                            activity, choice.ref);
                    MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                            activity, choice.label, choice.ref);
                });
                status.setText("MoneyManager synced • " + catalog.accounts.size()
                        + " account/card • " + catalog.expenseCategories.size()
                        + " expense categories");
            });
        });
    }

    private void bindFinance(@NonNull Activity activity, @NonNull View root) {
        MaterialAutoCompleteTextView account = root.findViewById(R.id.finance_account_input);
        MaterialAutoCompleteTextView category = root.findViewById(R.id.finance_category_input);
        RadioGroup typeGroup = root.findViewById(R.id.finance_type_group);
        TextInputLayout accountLayout = root.findViewById(R.id.finance_account_layout);
        TextInputLayout categoryLayout = root.findViewById(R.id.finance_category_layout);
        if (account == null || category == null || typeGroup == null
                || accountLayout == null || categoryLayout == null) return;
        if (boundViews.put(account, Boolean.TRUE) != null) return;

        accountLayout.setHelperText(activity.getString(R.string.money_manager_catalog_loading));
        categoryLayout.setHelperText(activity.getString(R.string.money_manager_catalog_loading));

        EXECUTOR.execute(() -> {
            MoneyManagerMasterCatalogBridge.Catalog catalog =
                    MoneyManagerMasterCatalogBridge.load(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!catalog.available) {
                    accountLayout.setHelperText(
                            activity.getString(R.string.money_manager_catalog_unavailable));
                    categoryLayout.setHelperText(
                            activity.getString(R.string.money_manager_catalog_unavailable));
                    return;
                }

                account.setAdapter(new ArrayAdapter<>(activity,
                        R.layout.item_form_dropdown,
                        MoneyManagerMasterCatalogBridge.labels(catalog.accounts)));
                accountLayout.setHint(activity.getString(R.string.money_manager_finance_account));
                accountLayout.setHelperText("Synced from MoneyManager");

                String currentAccount = account.getText() == null
                        ? "" : account.getText().toString().trim();
                MoneyManagerMasterCatalogBridge.Choice existingAccount =
                        MoneyManagerMasterCatalogBridge.findByLabel(
                                catalog.accounts, currentAccount);
                if (existingAccount != null) {
                    MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                            activity, existingAccount.label, existingAccount.ref);
                } else if ("Cash".equalsIgnoreCase(currentAccount)) {
                    account.setText("", false);
                }

                account.setOnItemClickListener((parent, view, position, id) -> {
                    if (position < 0 || position >= catalog.accounts.size()) return;
                    MoneyManagerMasterCatalogBridge.Choice choice = catalog.accounts.get(position);
                    MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                            activity, choice.label, choice.ref);
                });

                Runnable updateCategories = () -> updateFinanceCategories(
                        activity, category, categoryLayout, typeGroup, catalog);
                typeGroup.setOnCheckedChangeListener((group, checkedId) ->
                        updateCategories.run());
                updateCategories.run();
            });
        });
    }

    private void updateFinanceCategories(
            @NonNull Activity activity,
            @NonNull MaterialAutoCompleteTextView input,
            @NonNull TextInputLayout layout,
            @NonNull RadioGroup group,
            @NonNull MoneyManagerMasterCatalogBridge.Catalog catalog) {
        boolean income = group.getCheckedRadioButtonId() == R.id.type_income_button;
        List<MoneyManagerMasterCatalogBridge.Choice> choices = income
                ? catalog.incomeCategories : catalog.expenseCategories;
        String current = input.getText() == null ? "" : input.getText().toString().trim();
        input.setAdapter(new ArrayAdapter<>(activity, R.layout.item_form_dropdown,
                MoneyManagerMasterCatalogBridge.labels(choices)));
        layout.setHint(activity.getString(R.string.money_manager_finance_category));
        layout.setHelperText(income
                ? "MoneyManager Income categories"
                : "MoneyManager Expense categories");

        MoneyManagerMasterCatalogBridge.Choice existing =
                MoneyManagerMasterCatalogBridge.findByLabel(choices, current);
        if (existing != null) {
            MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                    activity, existing.label, existing.ref);
        } else if (!current.isEmpty()) {
            input.setText("", false);
        }
        input.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= choices.size()) return;
            MoneyManagerMasterCatalogBridge.Choice choice = choices.get(position);
            MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                    activity, choice.label, choice.ref);
        });
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        View root = activity.getWindow().getDecorView();
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                      @NonNull Bundle outState) { }
}
