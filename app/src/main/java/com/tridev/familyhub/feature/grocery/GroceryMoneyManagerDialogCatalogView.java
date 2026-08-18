package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.integration.MoneyManagerMasterCatalogBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog-local MoneyManager catalog binder for the main Grocery page.
 *
 * The Grocery editor is an AlertDialog with its own window, so the app-level
 * MoneyManagerFormAutoBinder cannot see these dropdowns. This lightweight status
 * view binds the two fields when the dialog is attached, without changing the
 * floating overlay, Finance, Loan or SmartSMS integration paths.
 *
 * When editing a Grocery item, the exact MoneyManager account/category selected
 * for that item is retained. This prevents a later global default from silently
 * replacing the transaction channel of an already-posted Grocery expense.
 */
public final class GroceryMoneyManagerDialogCatalogView extends AppCompatTextView {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private boolean catalogLoading;
    private long lastRefreshAt;
    @Nullable private GroceryItem boundItem;

    public GroceryMoneyManagerDialogCatalogView(@NonNull Context context) {
        super(context);
    }

    public GroceryMoneyManagerDialogCatalogView(
            @NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public GroceryMoneyManagerDialogCatalogView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Associates this dialog with the exact Grocery row being added/edited. */
    public void bindItem(@NonNull GroceryItem item) {
        boundItem = item;
        if (isAttachedToWindow()) refreshCatalog();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshCatalog();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && isAttachedToWindow()
                && System.currentTimeMillis() - lastRefreshAt > 750L) {
            refreshCatalog();
        }
    }

    private void refreshCatalog() {
        if (catalogLoading) return;
        catalogLoading = true;
        lastRefreshAt = System.currentTimeMillis();

        View root = getRootView();
        MaterialAutoCompleteTextView account = root.findViewById(
                R.id.grocery_money_account_input);
        MaterialAutoCompleteTextView category = root.findViewById(
                R.id.grocery_money_category_input);
        if (account == null || category == null) {
            catalogLoading = false;
            setText(R.string.money_manager_catalog_unavailable);
            return;
        }

        account.setEnabled(false);
        category.setEnabled(false);
        setText(R.string.money_manager_catalog_loading);

        Context appContext = getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            MoneyManagerMasterCatalogBridge.Catalog catalog =
                    MoneyManagerMasterCatalogBridge.load(appContext);
            post(() -> {
                catalogLoading = false;
                applyCatalog(account, category, catalog);
            });
        });
    }

    private void applyCatalog(
            @NonNull MaterialAutoCompleteTextView account,
            @NonNull MaterialAutoCompleteTextView category,
            @NonNull MoneyManagerMasterCatalogBridge.Catalog catalog) {
        if (!isAttachedToWindow()) return;

        String currentAccountLabel = account.getText() == null
                ? "" : account.getText().toString().trim();
        String currentCategoryLabel = category.getText() == null
                ? "" : category.getText().toString().trim();

        if (!catalog.available) {
            account.setEnabled(false);
            category.setEnabled(false);
            setText(catalog.reason.isEmpty()
                    ? getContext().getString(R.string.money_manager_catalog_unavailable)
                    : catalog.reason);
            return;
        }

        account.setAdapter(new ArrayAdapter<>(
                getContext(),
                R.layout.item_form_dropdown,
                MoneyManagerMasterCatalogBridge.labels(catalog.accounts)));
        category.setAdapter(new ArrayAdapter<>(
                getContext(),
                R.layout.item_form_dropdown,
                MoneyManagerMasterCatalogBridge.labels(catalog.expenseCategories)));

        account.setEnabled(!catalog.accounts.isEmpty());
        category.setEnabled(!catalog.expenseCategories.isEmpty());

        MoneyManagerMasterCatalogBridge.Choice currentAccount =
                MoneyManagerMasterCatalogBridge.findByLabel(
                        catalog.accounts, currentAccountLabel);
        MoneyManagerMasterCatalogBridge.Choice currentCategory =
                MoneyManagerMasterCatalogBridge.findByLabel(
                        catalog.expenseCategories, currentCategoryLabel);

        String savedAccountRef = boundItem == null
                ? MoneyManagerMasterCatalogBridge.groceryDefaultAccountRef(getContext())
                : GroceryMoneyManagerBridge.selectedAccountRef(getContext(), boundItem);
        String savedCategoryRef = boundItem == null
                ? MoneyManagerMasterCatalogBridge.groceryDefaultCategoryRef(getContext())
                : GroceryMoneyManagerBridge.selectedCategoryRef(getContext(), boundItem);

        MoneyManagerMasterCatalogBridge.Choice savedAccount =
                MoneyManagerMasterCatalogBridge.findByRef(
                        catalog.accounts, savedAccountRef);
        MoneyManagerMasterCatalogBridge.Choice savedCategory =
                MoneyManagerMasterCatalogBridge.findByRef(
                        catalog.expenseCategories, savedCategoryRef);
        MoneyManagerMasterCatalogBridge.Choice accountToShow =
                currentAccount != null ? currentAccount : savedAccount;
        MoneyManagerMasterCatalogBridge.Choice categoryToShow =
                currentCategory != null ? currentCategory : savedCategory;
        if (accountToShow != null) account.setText(accountToShow.label, false);
        if (categoryToShow != null) category.setText(categoryToShow.label, false);

        account.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= catalog.accounts.size()) return;
            MoneyManagerMasterCatalogBridge.Choice choice = catalog.accounts.get(position);
            if (boundItem != null) {
                GroceryMoneyManagerBridge.rememberNextPurchaseAccount(
                        getContext(), boundItem, choice.ref);
            } else {
                MoneyManagerMasterCatalogBridge.rememberGroceryDefaultAccount(
                        getContext(), choice.ref);
            }
            MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                    getContext(), choice.label, choice.ref);
        });

        category.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= catalog.expenseCategories.size()) return;
            MoneyManagerMasterCatalogBridge.Choice choice =
                    catalog.expenseCategories.get(position);
            if (boundItem != null) {
                GroceryMoneyManagerBridge.rememberNextPurchaseCategory(
                        getContext(), boundItem, choice.ref);
            } else {
                MoneyManagerMasterCatalogBridge.rememberGroceryDefaultCategory(
                        getContext(), choice.ref);
            }
            MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                    getContext(), choice.label, choice.ref);
        });

        setText("MoneyManager synced • " + catalog.accounts.size()
                + " account/card • " + catalog.expenseCategories.size()
                + " expense categories");
    }
}
