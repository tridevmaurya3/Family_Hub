package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.integration.MoneyManagerMasterCatalogBridge;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * It also adds a presentation-only More details control to the same dialog.
 * The compact state hides optional fields only; the original views, listeners,
 * validation and save/update code stay in place and are restored unchanged.
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

    private boolean detailsInstalled;
    private boolean detailsExpanded;
    private boolean detailsStateApplied;
    @Nullable private MaterialButton detailsToggle;
    private final List<View> collapsibleDetailViews = new ArrayList<>();
    private final Map<View, Integer> visibilityBeforeCollapse = new IdentityHashMap<>();

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
        if (isAttachedToWindow()) {
            post(() -> {
                installProfessionalDetailsToggle();
                // New Add forms open compact. Existing rows open expanded so an
                // editor never misses saved optional data such as store/account.
                setDetailsExpanded(item.id > 0L);
            });
            refreshCatalog();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::installProfessionalDetailsToggle);
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

    /**
     * Adds a compact More details button without replacing dialog_grocery.xml.
     * This is deliberately view-only: no Entity/DAO/Firebase/Finance state is
     * changed and every original input remains the same instance.
     */
    private void installProfessionalDetailsToggle() {
        if (detailsInstalled || !isAttachedToWindow()) return;

        View root = getRootView();
        MaterialAutoCompleteTextView groceryCategory = root.findViewById(
                R.id.grocery_category_input);
        if (groceryCategory != null) {
            GrocerySmartCategoryPicker.attach(getContext(), groceryCategory);
        }

        View accountLayout = root.findViewById(R.id.grocery_money_account_layout);
        View moneyCard = findCardAncestor(accountLayout);
        if (moneyCard == null || !(moneyCard.getParent() instanceof ViewGroup)) return;

        ViewGroup container = (ViewGroup) moneyCard.getParent();
        int insertionIndex = container.indexOfChild(moneyCard);
        if (insertionIndex < 0) return;

        addCollapsible(parentView(root.findViewById(R.id.grocery_assignee_input)));
        addCollapsible(moneyCard);

        View costLayout = parentView(root.findViewById(R.id.grocery_cost_input));
        View actualCostLayout = parentView(root.findViewById(R.id.grocery_actual_cost_input));
        View priceRow = commonParentView(costLayout, actualCostLayout);
        addCollapsible(priceRow != null ? priceRow : costLayout);

        addCollapsible(parentView(root.findViewById(R.id.grocery_store_input)));
        addCollapsible(root.findViewById(R.id.grocery_auto_price_switch));
        addCollapsible(root.findViewById(R.id.grocery_price_comparison));
        addCollapsible(parentView(root.findViewById(R.id.grocery_priority_input)));

        MaterialButton toggle = new MaterialButton(getContext());
        toggle.setAllCaps(false);
        toggle.setTextSize(12f);
        toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        toggle.setMinHeight(dp(44));
        toggle.setMinimumHeight(dp(44));
        toggle.setPadding(dp(14), 0, dp(14), 0);
        toggle.setCornerRadius(dp(14));
        toggle.setStrokeWidth(dp(1));
        toggle.setStrokeColor(ColorStateList.valueOf(Color.rgb(187, 208, 201)));
        toggle.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(241, 249, 246)));
        toggle.setTextColor(Color.rgb(15, 108, 89));
        toggle.setContentDescription(
                "Show or hide price, store, account, expense category, assignee and priority");
        toggle.setOnClickListener(v -> setDetailsExpanded(!detailsExpanded));

        ViewGroup.LayoutParams baseParams = moneyCard.getLayoutParams();
        if (container instanceof LinearLayout) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            params.topMargin = dp(10);
            container.addView(toggle, insertionIndex, params);
        } else {
            ViewGroup.LayoutParams params = baseParams == null
                    ? new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
                    : new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            container.addView(toggle, insertionIndex, params);
        }

        detailsToggle = toggle;
        detailsInstalled = true;

        boolean editingExisting = boundItem != null && boundItem.id > 0L;
        setDetailsExpanded(editingExisting);
    }

    private void addCollapsible(@Nullable View view) {
        if (view == null || collapsibleDetailViews.contains(view)) return;
        collapsibleDetailViews.add(view);
    }

    @Nullable
    private View parentView(@Nullable View child) {
        if (child == null || !(child.getParent() instanceof View)) return null;
        return (View) child.getParent();
    }

    @Nullable
    private View commonParentView(@Nullable View first, @Nullable View second) {
        if (first == null || second == null) return null;
        if (first.getParent() == second.getParent() && first.getParent() instanceof View) {
            return (View) first.getParent();
        }
        return null;
    }

    @Nullable
    private View findCardAncestor(@Nullable View start) {
        View current = start;
        while (current != null) {
            if (current instanceof MaterialCardView) return current;
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return null;
    }

    private void setDetailsExpanded(boolean expanded) {
        if (!detailsInstalled) return;
        if (detailsStateApplied && expanded == detailsExpanded) {
            updateDetailsToggleLabel();
            return;
        }

        if (expanded) {
            for (Map.Entry<View, Integer> entry : visibilityBeforeCollapse.entrySet()) {
                View view = entry.getKey();
                if (view != null) view.setVisibility(entry.getValue());
            }
            visibilityBeforeCollapse.clear();
        } else {
            visibilityBeforeCollapse.clear();
            for (View view : collapsibleDetailViews) {
                if (view == null) continue;
                visibilityBeforeCollapse.put(view, view.getVisibility());
                view.setVisibility(View.GONE);
            }
        }

        detailsExpanded = expanded;
        detailsStateApplied = true;
        updateDetailsToggleLabel();
    }

    private void updateDetailsToggleLabel() {
        if (detailsToggle == null) return;
        detailsToggle.setText(detailsExpanded
                ? "Less details   ▴"
                : "More details   ▾");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
