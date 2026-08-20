package com.tridev.familyhub.feature.integration;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.SemanticValueStyler;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wires Grocery and Finance surfaces to MoneyManager master data. */
public final class MoneyManagerFormAutoBinder implements Application.ActivityLifecycleCallbacks {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long SUMMARY_REFRESH_GUARD_MS = 1_500L;
    private static final String ANALYTICS_PREFIX = "MoneyManager • ";

    private final Map<View, Boolean> boundViews =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Long> summaryRefreshAt =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Boolean> summaryLoading =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, String> familyAnalyticsText =
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
        bindFinanceMasterSummary(activity, root);
        bindGrocery(activity, root);
        bindFinance(activity, root);
    }

    /**
     * Keeps the visible Finance dashboard aligned with MoneyManager even when
     * Family Hub refreshes its local account card after a realtime DB update.
     * No aggregate is persisted or uploaded by this binder.
     */
    private void bindFinanceMasterSummary(@NonNull Activity activity, @NonNull View root) {
        TextView expense = root.findViewById(R.id.month_expense_value);
        TextView income = root.findViewById(R.id.month_income_value);
        TextView remaining = root.findViewById(R.id.month_balance_value);
        TextView accounts = root.findViewById(R.id.finance_accounts_summary);
        TextView analytics = root.findViewById(R.id.finance_analytics_summary);
        View analyticsCard = root.findViewById(R.id.finance_analytics_card);
        if (expense == null || income == null || remaining == null || accounts == null) return;

        if (analytics != null) {
            String currentAnalytics = analytics.getText() == null
                    ? "" : analytics.getText().toString().trim();
            if (!currentAnalytics.isEmpty() && !currentAnalytics.startsWith(ANALYTICS_PREFIX)) {
                familyAnalyticsText.put(analytics, currentAnalytics);
            }
        }

        long now = System.currentTimeMillis();
        Long last = summaryRefreshAt.get(accounts);
        if (last != null && now - last < SUMMARY_REFRESH_GUARD_MS) return;
        if (summaryLoading.put(accounts, Boolean.TRUE) != null) return;
        summaryRefreshAt.put(accounts, now);

        EXECUTOR.execute(() -> {
            MoneyManagerFinanceSummaryBridge.Summary master =
                    MoneyManagerFinanceSummaryBridge.loadCurrentMonth(activity);
            activity.runOnUiThread(() -> {
                summaryLoading.remove(accounts);
                if (activity.isFinishing() || activity.isDestroyed() || !master.available) return;

                NumberFormat formatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
                expense.setText(formatter.format(master.expense));
                income.setText(formatter.format(master.income));
                remaining.setText(formatter.format(master.remaining));
                SemanticValueStyler.apply(expense, -master.expense);
                SemanticValueStyler.apply(income, master.income);
                SemanticValueStyler.apply(remaining, master.remaining);

                String accountText = "MoneyManager • " + master.accountCount + " accounts";
                if (master.activeCardCount > 0) {
                    accountText += " + " + master.activeCardCount + " cards";
                }
                accountText += " • " + formatter.format(master.totalAccountBalance);
                accounts.setText(accountText);
                accounts.setContentDescription("Synced from MoneyManager • " + master.periodLabel);

                if (analytics != null) {
                    String familyInsight = familyAnalyticsText.get(analytics);
                    analytics.setMaxLines(8);
                    analytics.setText(compactAnalytics(master, formatter, familyInsight));
                    analytics.setContentDescription(
                            "MoneyManager category analytics • " + master.periodLabel);
                    View.OnClickListener detailsClick = clicked -> showAnalyticsDetails(
                            activity, master, formatter, familyInsight);
                    analytics.setOnClickListener(detailsClick);
                    if (analyticsCard != null) analyticsCard.setOnClickListener(detailsClick);
                }
            });
        });
    }

    @NonNull
    private String compactAnalytics(
            @NonNull MoneyManagerFinanceSummaryBridge.Summary master,
            @NonNull NumberFormat formatter,
            @Nullable String familyInsight) {
        StringBuilder text = new StringBuilder(ANALYTICS_PREFIX)
                .append(master.periodLabel.isEmpty() ? "Current month" : master.periodLabel)
                .append('\n')
                .append("Expense categories • ")
                .append(compactCategories(master.expenseCategories, formatter, 4));

        if (!master.incomeCategories.isEmpty()) {
            text.append('\n')
                    .append("Income categories • ")
                    .append(compactCategories(master.incomeCategories, formatter, 2));
        }

        String local = cleanInsight(familyInsight);
        if (!local.isEmpty()) {
            text.append('\n').append("Family insight • ").append(limit(local, 180));
        }
        text.append('\n').append("Tap for full category breakdown");
        return text.toString();
    }

    @NonNull
    private String compactCategories(
            @NonNull List<MoneyManagerFinanceSummaryBridge.CategoryTotal> categories,
            @NonNull NumberFormat formatter,
            int limit) {
        if (categories.isEmpty()) return "No entries";
        StringBuilder text = new StringBuilder();
        int shown = Math.min(Math.max(1, limit), categories.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) text.append(" • ");
            MoneyManagerFinanceSummaryBridge.CategoryTotal item = categories.get(index);
            text.append(item.label).append(' ').append(formatter.format(item.amount));
        }
        if (categories.size() > shown) {
            text.append(" • +").append(categories.size() - shown).append(" more");
        }
        return text.toString();
    }

    private void showAnalyticsDetails(
            @NonNull Activity activity,
            @NonNull MoneyManagerFinanceSummaryBridge.Summary master,
            @NonNull NumberFormat formatter,
            @Nullable String familyInsight) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 18), dp(activity, 8),
                dp(activity, 18), dp(activity, 12));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView period = dialogText(activity,
                "MoneyManager  •  " + (master.periodLabel.isEmpty()
                        ? "Current month" : master.periodLabel), 14f, true,
                R.color.fh_info);
        content.addView(period);

        addCategoryCard(activity, content, "EXPENSE BREAKDOWN",
                master.expenseCategories, master.expense, formatter,
                R.color.fh_error_container, R.color.fh_error);
        addCategoryCard(activity, content, "INCOME BREAKDOWN",
                master.incomeCategories, master.income, formatter,
                R.color.fh_success_container, R.color.fh_success);

        int remainingColor = master.remaining >= 0D ? R.color.fh_success : R.color.fh_error;
        int remainingBackground = master.remaining >= 0D
                ? R.color.fh_success_container : R.color.fh_error_container;
        addTotalCard(activity, content, "NET SAVING",
                formatter.format(master.remaining), remainingBackground, remainingColor);

        String local = cleanInsight(familyInsight);
        if (!local.isEmpty()) {
            addTextCard(activity, content, "FAMILY HUB INSIGHT", local,
                    R.color.fh_info_container, R.color.fh_info);
        }

        TextView privacy = dialogText(activity,
                "Read-only summary from MoneyManager • Individual transactions are not shared",
                10f, false, R.color.fh_text_secondary);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = dp(activity, 12);
        content.addView(privacy, privacyParams);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Finance Analytics")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void addCategoryCard(@NonNull Activity activity,
                                 @NonNull LinearLayout parent,
                                 @NonNull String title,
                                 @NonNull List<MoneyManagerFinanceSummaryBridge.CategoryTotal> rows,
                                 double total,
                                 @NonNull NumberFormat formatter,
                                 int backgroundColor,
                                 int accentColor) {
        LinearLayout body = cardBody(activity, parent, backgroundColor, accentColor);
        body.addView(dialogText(activity, title, 11f, true, accentColor));
        if (rows.isEmpty()) {
            body.addView(dialogText(activity, "No entries", 12f, false,
                    R.color.fh_text_secondary));
        } else {
            for (MoneyManagerFinanceSummaryBridge.CategoryTotal item : rows) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = dialogText(activity, item.label, 12f, false,
                        R.color.fh_on_surface);
                TextView amount = dialogText(activity, formatter.format(item.amount),
                        12f, true, accentColor);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nameParams.setMarginEnd(dp(activity, 8));
                row.addView(name, nameParams);
                row.addView(amount);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = dp(activity, 7);
                body.addView(row, rowParams);
            }
        }
        TextView totalView = dialogText(activity, "Total  " + formatter.format(total),
                13f, true, accentColor);
        totalView.setGravity(Gravity.END);
        LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        totalParams.topMargin = dp(activity, 10);
        body.addView(totalView, totalParams);
    }

    private void addTotalCard(@NonNull Activity activity, @NonNull LinearLayout parent,
                              @NonNull String title, @NonNull String value,
                              int backgroundColor, int accentColor) {
        LinearLayout body = cardBody(activity, parent, backgroundColor, accentColor);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(dialogText(activity, title, 11f, true, accentColor));
        TextView amount = dialogText(activity, value, 18f, true, accentColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 5);
        body.addView(amount, params);
    }

    private void addTextCard(@NonNull Activity activity, @NonNull LinearLayout parent,
                             @NonNull String title, @NonNull String value,
                             int backgroundColor, int accentColor) {
        LinearLayout body = cardBody(activity, parent, backgroundColor, accentColor);
        body.addView(dialogText(activity, title, 11f, true, accentColor));
        TextView detail = dialogText(activity, value, 12f, false, R.color.fh_on_surface);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 6);
        body.addView(detail, params);
    }

    @NonNull
    private LinearLayout cardBody(@NonNull Activity activity, @NonNull LinearLayout parent,
                                  int backgroundColor, int accentColor) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(activity, 16));
        card.setCardElevation(dp(activity, 1));
        card.setStrokeWidth(dp(activity, 1));
        card.setStrokeColor(ColorStateList.valueOf(
                withAlpha(ContextCompat.getColor(activity, accentColor), 85)));
        card.setCardBackgroundColor(ContextCompat.getColor(activity, backgroundColor));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(activity, 12);
        parent.addView(card, cardParams);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 14), dp(activity, 12),
                dp(activity, 14), dp(activity, 13));
        card.addView(body, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    @NonNull
    private TextView dialogText(@NonNull Activity activity, @NonNull String value,
                                float size, boolean bold, int colorResource) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(activity, colorResource));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void appendCategoryDetails(
            @NonNull StringBuilder body,
            @NonNull List<MoneyManagerFinanceSummaryBridge.CategoryTotal> categories,
            @NonNull NumberFormat formatter) {
        if (categories.isEmpty()) {
            body.append("No entries");
            return;
        }
        for (MoneyManagerFinanceSummaryBridge.CategoryTotal item : categories) {
            body.append("• ").append(item.label)
                    .append(" — ").append(formatter.format(item.amount)).append('\n');
        }
        if (body.length() > 0 && body.charAt(body.length() - 1) == '\n') {
            body.setLength(body.length() - 1);
        }
    }

    @NonNull
    private String cleanInsight(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.isEmpty()
                || clean.equalsIgnoreCase("Loading…")
                || clean.equalsIgnoreCase("Loading...")) return "";
        return clean.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ");
    }

    @NonNull
    private String limit(@NonNull String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).trim() + "…";
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
