package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog-window aware MoneyManager catalog binder for the Family Hub Finance form.
 *
 * Application ActivityLifecycleCallbacks cannot see AlertDialog's separate Window,
 * so this account field binds the dialog from inside that Window when attached.
 */
public final class MoneyManagerFinanceDialogAccountView
        extends MaterialAutoCompleteTextView {

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Map<View, Boolean> BOUND =
            Collections.synchronizedMap(new WeakHashMap<>());

    public MoneyManagerFinanceDialogAccountView(@NonNull Context context) {
        super(context);
    }

    public MoneyManagerFinanceDialogAccountView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
    }

    public MoneyManagerFinanceDialogAccountView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::bindDialogWindow);
    }

    private void bindDialogWindow() {
        if (!isAttachedToWindow()) return;
        View root = getRootView();
        MaterialAutoCompleteTextView account = root.findViewById(R.id.finance_account_input);
        MaterialAutoCompleteTextView category = root.findViewById(R.id.finance_category_input);
        RadioGroup typeGroup = root.findViewById(R.id.finance_type_group);
        TextInputLayout accountLayout = root.findViewById(R.id.finance_account_layout);
        TextInputLayout categoryLayout = root.findViewById(R.id.finance_category_layout);
        TextInputEditText amountInput = root.findViewById(R.id.finance_amount_input);
        if (account != this || category == null || typeGroup == null
                || accountLayout == null || categoryLayout == null) {
            return;
        }
        if (BOUND.put(this, Boolean.TRUE) != null) return;

        final boolean newEntry = amountInput == null
                || amountInput.getText() == null
                || amountInput.getText().toString().trim().isEmpty();

        accountLayout.setHint(getContext().getString(R.string.money_manager_finance_account));
        accountLayout.setHelperText(getContext().getString(R.string.money_manager_catalog_loading));
        categoryLayout.setHint(getContext().getString(R.string.money_manager_finance_category));
        categoryLayout.setHelperText(getContext().getString(R.string.money_manager_catalog_loading));
        account.setEnabled(false);
        category.setEnabled(false);

        Context context = getContext();
        EXECUTOR.execute(() -> {
            MoneyManagerMasterCatalogBridge.Catalog catalog =
                    MoneyManagerMasterCatalogBridge.load(context);
            post(() -> applyCatalog(
                    catalog,
                    account,
                    category,
                    typeGroup,
                    accountLayout,
                    categoryLayout,
                    newEntry
            ));
        });
    }

    private void applyCatalog(
            @NonNull MoneyManagerMasterCatalogBridge.Catalog catalog,
            @NonNull MaterialAutoCompleteTextView account,
            @NonNull MaterialAutoCompleteTextView category,
            @NonNull RadioGroup typeGroup,
            @NonNull TextInputLayout accountLayout,
            @NonNull TextInputLayout categoryLayout,
            boolean newEntry
    ) {
        if (!isAttachedToWindow()) return;
        if (!catalog.available) {
            account.setEnabled(true);
            category.setEnabled(true);
            accountLayout.setHelperText(getContext().getString(
                    R.string.money_manager_catalog_unavailable));
            categoryLayout.setHelperText(getContext().getString(
                    R.string.money_manager_catalog_unavailable));
            return;
        }

        account.setAdapter(new ArrayAdapter<>(
                getContext(),
                R.layout.item_form_dropdown,
                MoneyManagerMasterCatalogBridge.labels(catalog.accounts)
        ));
        account.setEnabled(!catalog.accounts.isEmpty());
        accountLayout.setHelperText("MoneyManager • " + catalog.accounts.size()
                + " bank/card accounts");

        String currentAccount = account.getText() == null
                ? "" : account.getText().toString().trim();
        MoneyManagerMasterCatalogBridge.Choice matchedAccount =
                MoneyManagerMasterCatalogBridge.findByLabel(
                        catalog.accounts, currentAccount);
        if (matchedAccount != null) {
            MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                    getContext(), matchedAccount.label, matchedAccount.ref);
        } else if (newEntry) {
            // Remove Family Hub's old generic Cash/Credit-card default. A new
            // canonical entry must explicitly choose a MoneyManager account/card.
            account.setText("", false);
        }

        account.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= catalog.accounts.size()) return;
            MoneyManagerMasterCatalogBridge.Choice choice =
                    catalog.accounts.get(position);
            MoneyManagerMasterCatalogBridge.rememberAccountChoice(
                    getContext(), choice.label, choice.ref);
            accountLayout.setHelperText("MoneyManager • " + choice.type);
        });
        account.setOnClickListener(v -> account.showDropDown());

        Runnable updateCategories = () -> bindCategories(
                catalog,
                category,
                categoryLayout,
                typeGroup,
                newEntry
        );
        typeGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateCategories.run());
        updateCategories.run();
    }

    private void bindCategories(
            @NonNull MoneyManagerMasterCatalogBridge.Catalog catalog,
            @NonNull MaterialAutoCompleteTextView category,
            @NonNull TextInputLayout categoryLayout,
            @NonNull RadioGroup typeGroup,
            boolean newEntry
    ) {
        boolean income = typeGroup.getCheckedRadioButtonId()
                == R.id.type_income_button;
        List<MoneyManagerMasterCatalogBridge.Choice> choices = income
                ? catalog.incomeCategories
                : catalog.expenseCategories;

        String current = category.getText() == null
                ? "" : category.getText().toString().trim();
        category.setAdapter(new ArrayAdapter<>(
                getContext(),
                R.layout.item_form_dropdown,
                MoneyManagerMasterCatalogBridge.labels(choices)
        ));
        category.setEnabled(!choices.isEmpty());
        categoryLayout.setHelperText(income
                ? "MoneyManager Income categories • " + choices.size()
                : "MoneyManager Expense categories • " + choices.size());

        MoneyManagerMasterCatalogBridge.Choice matched =
                MoneyManagerMasterCatalogBridge.findByLabel(choices, current);
        if (matched != null) {
            MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                    getContext(), matched.label, matched.ref);
        } else if (newEntry || !current.isEmpty()) {
            // Prevent a stale local Expense category being reused after the
            // user switches between Expense and Income.
            category.setText("", false);
        }

        category.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= choices.size()) return;
            MoneyManagerMasterCatalogBridge.Choice choice = choices.get(position);
            MoneyManagerMasterCatalogBridge.rememberCategoryChoice(
                    getContext(), choice.label, choice.ref);
        });
        category.setOnClickListener(v -> category.showDropDown());
    }
}
