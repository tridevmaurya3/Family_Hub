package com.tridev.familyhub.feature.finance;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.FileProvider;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.SemanticValueStyler;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.FinanceAccount;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.repository.FinanceRepository;
import com.tridev.familyhub.data.repository.FamilyAccountRepository;
import com.tridev.familyhub.databinding.DialogFinanceEntryBinding;
import com.tridev.familyhub.databinding.FragmentFinanceBinding;
import com.tridev.familyhub.feature.finance.adapter.FinanceEntryAdapter;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.File;
import java.util.concurrent.Executors;

/** Complete local income and expense feature. */
public class FinanceFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {

    private static final String ISO_DATE_PATTERN = "yyyy-MM-dd";
    private static final String FINANCE_PREFS = "finance_2_preferences";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget";
    private static final String KEY_ACCOUNTS = "accounts";

    private FragmentFinanceBinding binding;
    private FinanceEntryAdapter entryAdapter;
    private FinanceRepository repository;
    private FinanceSummary latestSummary = new FinanceSummary();
    @NonNull private List<FinanceEntry> latestEntries = new ArrayList<>();
    @NonNull private List<FinanceEntry> visibleEntries = new ArrayList<>();
    private int selectedFinanceFilter = R.id.finance_filter_all;
    private boolean financeHeaderCollapsed;
    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFinanceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = new FinanceRepository(requireContext());
        entryAdapter = new FinanceEntryAdapter(new FinanceEntryAdapter.EntryActionListener() {
            @Override
            public void onEdit(FinanceEntry entry) {
                showEntryEditor(entry);
            }

            @Override
            public void onDelete(FinanceEntry entry) {
                confirmDelete(entry);
            }
        });

        binding.financeRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.financeRecyclerView.setAdapter(entryAdapter);
        binding.financeOverview.setNavigationAction(
                R.drawable.ic_menu_hamburger,
                R.string.feature_menu_title,
                clickedView -> {
                    if (requireActivity() instanceof MainActivity) {
                        ((MainActivity) requireActivity()).showFeatureMenu();
                    }
                }
        );
        binding.financeRecyclerView.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(
                            @NonNull RecyclerView recyclerView,
                            int newState
                    ) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING
                                && recyclerView.canScrollVertically(1)
                                && !financeHeaderCollapsed) {
                            setFinanceHeaderCollapsed(true);
                        }
                    }

                    @Override
                    public void onScrolled(
                            @NonNull RecyclerView recyclerView,
                            int dx,
                            int dy
                    ) {
                        if (dy > 2 && !financeHeaderCollapsed) {
                            setFinanceHeaderCollapsed(true);
                        } else if (!recyclerView.canScrollVertically(-1)
                                && financeHeaderCollapsed) {
                            setFinanceHeaderCollapsed(false);
                        }
                    }
                }
        );
        binding.emptyAddFinanceButton.setOnClickListener(v -> showEntryEditor(null));
        binding.financeBudgetButton.setOnClickListener(v -> showBudgetEditor());
        binding.financeReportButton.setOnClickListener(v -> showReportOptions());
        binding.financeManageAccountsButton.setOnClickListener(v -> showAccountEditor());
        binding.financeSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No work before the text changes.
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Filtering is applied after the text changes.
            }

            @Override
            public void afterTextChanged(Editable searchText) {
                loadEntries(searchText.toString());
            }
        });
        binding.financeFilterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            selectedFinanceFilter = checkedIds.isEmpty()
                    ? R.id.finance_filter_all : checkedIds.get(0);
            applyFinanceFilters();
        });
        refreshData();
    }

    /** Matches Grocery: keep search visible while the list uses the full page. */
    private void setFinanceHeaderCollapsed(boolean collapsed) {
        if (binding == null || financeHeaderCollapsed == collapsed) return;
        financeHeaderCollapsed = collapsed;
        TransitionManager.beginDelayedTransition(binding.getRoot());
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        binding.financeOverview.setVisibility(visibility);
        binding.monthSummaryCard.setVisibility(visibility);
        binding.financeAccountsCard.setVisibility(visibility);
        binding.financeSmartHealthCard.setVisibility(visibility);
        binding.financeInsightsActions.setVisibility(visibility);
        binding.financeAnalyticsCard.setVisibility(visibility);
        if (collapsed) {
            binding.financeSearchInput.clearFocus();
        }

        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(binding.getRoot());
        constraints.clear(R.id.finance_search_layout, ConstraintSet.TOP);
        constraints.connect(
                R.id.finance_search_layout,
                ConstraintSet.TOP,
                collapsed ? ConstraintSet.PARENT_ID : R.id.finance_analytics_card,
                collapsed ? ConstraintSet.TOP : ConstraintSet.BOTTOM,
                getResources().getDimensionPixelSize(
                        collapsed ? R.dimen.space_4 : R.dimen.space_16
                )
        );
        constraints.applyTo(binding.getRoot());
    }

    @Override
    public void onAddRequested() {
        showEntryEditor(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            repository.startSharedSync(this::refreshData);
            repository.runRecurringAutomation(this::refreshData);
        }
    }

    @Override
    public void onPause() {
        repository.stopSharedSync();
        super.onPause();
    }

    private void refreshData() {
        loadEntries(binding.financeSearchInput.getText().toString());
        loadSummary();
        loadAccountSummary();
    }

    private void loadAccountSummary() {
        repository.loadAccounts(accounts -> {
            if (binding == null) return;
            double total = 0D;
            for (FinanceAccount account : accounts) total += account.currentBalance;
            binding.financeAccountsSummary.setText(getString(
                    R.string.finance_account_summary, accounts.size(), currencyFormatter.format(total)));
        });
    }

    private void showAccountEditor() {
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL); form.setPadding(padding, padding / 2, padding, 0);
        EditText name = new EditText(requireContext()); name.setHint(R.string.finance_account_name);
        EditText type = new EditText(requireContext()); type.setHint(R.string.finance_account_type);
        EditText opening = new EditText(requireContext()); opening.setHint(R.string.finance_opening_balance);
        opening.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(name); form.addView(type); form.addView(opening);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.finance_add_account).setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String accountName = name.getText().toString().trim();
                    if (accountName.isEmpty()) return;
                    FinanceAccount account = new FinanceAccount(); account.name = accountName;
                    account.accountType = type.getText().toString().trim().isEmpty()
                            ? "OTHER" : type.getText().toString().trim().toUpperCase(Locale.ROOT);
                    try { account.openingBalance = Double.parseDouble(opening.getText().toString().trim()); }
                    catch (NumberFormatException ignored) { account.openingBalance = 0D; }
                    rememberAccount(account.name);
                    repository.saveAccount(account, this::refreshData);
                }).show();
    }

    private void loadEntries(String query) {
        repository.loadEntries(query, entries -> {
            if (binding == null) {
                return;
            }
            if (query.trim().isEmpty()) {
                latestEntries = new ArrayList<>(entries);
                updateSmartInsights();
                updateAnalytics();
            }
            applyFinanceFilters(entries);
        });
    }

    private void applyFinanceFilters() {
        String query = binding.financeSearchInput.getText().toString().trim();
        if (!query.isEmpty()) {
            loadEntries(query);
            return;
        }
        applyFinanceFilters(latestEntries);
    }

    private void applyFinanceFilters(@NonNull List<FinanceEntry> source) {
        List<FinanceEntry> filtered = new ArrayList<>();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        if (selectedFinanceFilter == R.id.finance_filter_week) {
            start.set(Calendar.DAY_OF_WEEK, start.getFirstDayOfWeek());
        } else if (selectedFinanceFilter == R.id.finance_filter_month) {
            start.set(Calendar.DAY_OF_MONTH, 1);
        }
        String startDate = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US)
                .format(start.getTime());
        String today = todayAsIsoDate();
        for (FinanceEntry entry : source) {
            boolean include;
            if (selectedFinanceFilter == R.id.finance_filter_today) {
                include = today.equals(entry.transactionDate);
            } else if (selectedFinanceFilter == R.id.finance_filter_week
                    || selectedFinanceFilter == R.id.finance_filter_month) {
                include = entry.transactionDate.compareTo(startDate) >= 0
                        && entry.transactionDate.compareTo(today) <= 0;
            } else if (selectedFinanceFilter == R.id.finance_filter_income) {
                include = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
            } else if (selectedFinanceFilter == R.id.finance_filter_expense) {
                include = FinanceEntry.TYPE_EXPENSE.equals(entry.entryType);
            } else {
                include = true;
            }
            if (include) filtered.add(entry);
        }
        entryAdapter.submitList(filtered);
        visibleEntries = new ArrayList<>(filtered);
        boolean isEmpty = filtered.isEmpty();
        binding.financeRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        binding.financeEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void loadSummary() {
        repository.loadCurrentMonthSummary(summary -> {
            if (binding == null) {
                return;
            }
            FinanceSummary safeSummary = summary == null ? new FinanceSummary() : summary;
            latestSummary = safeSummary;
            binding.monthExpenseValue.setText(currencyFormatter.format(safeSummary.expense));
            binding.monthIncomeValue.setText(currencyFormatter.format(safeSummary.income));
            binding.monthBalanceValue.setText(currencyFormatter.format(
                    safeSummary.income - safeSummary.expense
            ));
            SemanticValueStyler.apply(
                    binding.monthExpenseValue,
                    -safeSummary.expense
            );
            SemanticValueStyler.apply(
                    binding.monthIncomeValue,
                    safeSummary.income
            );
            SemanticValueStyler.apply(
                    binding.monthBalanceValue,
                    safeSummary.income - safeSummary.expense
            );
            updateSmartInsights();
        });
    }

    private void updateSmartInsights() {
        if (binding == null) {
            return;
        }
        double budget = monthlyBudget();
        double expense = latestSummary.expense;
        int budgetPercent = budget <= 0D ? 0 : (int) Math.round(
                (expense / budget) * 100D
        );
        binding.financeBudgetProgress.setProgressCompat(
                Math.min(100, Math.max(0, budgetPercent)),
                true
        );

        if (budget <= 0D) {
            binding.financeBudgetHealthTitle.setText(
                    R.string.finance_health_no_budget
            );
            binding.financeBudgetHealthDetail.setText(
                    R.string.finance_health_no_budget_detail
            );
        } else {
            int title;
            if (expense > budget) {
                title = R.string.finance_health_over_budget;
            } else if (budgetPercent >= 90) {
                title = R.string.finance_health_critical;
            } else if (budgetPercent >= 70) {
                title = R.string.finance_health_watch;
            } else {
                title = R.string.finance_health_good;
            }
            binding.financeBudgetHealthTitle.setText(title);
            binding.financeBudgetHealthDetail.setText(getString(
                    R.string.finance_health_budget_detail,
                    budgetPercent,
                    currencyFormatter.format(Math.max(0D, budget - expense))
            ));
        }

        String monthPrefix = new SimpleDateFormat(
                "yyyy-MM",
                Locale.US
        ).format(new Date());
        Map<String, Double> categoryExpense = new HashMap<>();
        Set<String> accounts = new LinkedHashSet<>();
        int recurringCount = 0;
        int sharedCount = 0;
        int monthTransactions = 0;
        for (FinanceEntry entry : latestEntries) {
            if (!entry.transactionDate.startsWith(monthPrefix)) {
                continue;
            }
            monthTransactions++;
            accounts.add(entry.accountName);
            if (entry.isRecurring) {
                recurringCount++;
            }
            if (entry.isShared) {
                sharedCount++;
            }
            if (FinanceEntry.TYPE_EXPENSE.equals(entry.entryType)) {
                categoryExpense.put(
                        entry.category,
                        categoryExpense.getOrDefault(entry.category, 0D)
                                + entry.amount
                );
            }
        }
        String topCategory = getString(R.string.finance_insight_none);
        double topAmount = 0D;
        for (Map.Entry<String, Double> item : categoryExpense.entrySet()) {
            if (item.getValue() > topAmount) {
                topCategory = item.getKey();
                topAmount = item.getValue();
            }
        }
        Calendar calendar = Calendar.getInstance();
        int day = Math.max(1, calendar.get(Calendar.DAY_OF_MONTH));
        int daysInMonth = calendar.getActualMaximum(
                Calendar.DAY_OF_MONTH
        );
        double projectedExpense = expense <= 0D
                ? 0D : (expense / day) * daysInMonth;
        binding.financeSmartInsightDetail.setText(getString(
                R.string.finance_smart_insight_detail,
                monthTransactions,
                topCategory,
                currencyFormatter.format(topAmount),
                recurringCount,
                sharedCount,
                accounts.size(),
                currencyFormatter.format(projectedExpense)
        ));
    }

    private void updateAnalytics() {
        if (binding == null) return;
        Calendar now = Calendar.getInstance();
        String current = new SimpleDateFormat("yyyy-MM", Locale.US).format(now.getTime());
        now.add(Calendar.MONTH, -1);
        String previous = new SimpleDateFormat("yyyy-MM", Locale.US).format(now.getTime());
        double currentExpense = 0D, previousExpense = 0D, currentIncome = 0D;
        Map<String, Double> categories = new HashMap<>(), accounts = new HashMap<>(), payers = new HashMap<>();
        for (FinanceEntry entry : latestEntries) {
            if ("UPCOMING".equals(entry.recurrenceStatus)) continue;
            if (entry.transactionDate.startsWith(previous) && FinanceEntry.TYPE_EXPENSE.equals(entry.entryType)) {
                previousExpense += entry.amount;
            }
            if (!entry.transactionDate.startsWith(current)) continue;
            if (FinanceEntry.TYPE_INCOME.equals(entry.entryType)) { currentIncome += entry.amount; continue; }
            currentExpense += entry.amount;
            categories.put(entry.category, categories.getOrDefault(entry.category, 0D) + entry.amount);
            accounts.put(entry.accountName, accounts.getOrDefault(entry.accountName, 0D) + entry.amount);
            if (!entry.paidByName.isEmpty()) payers.put(entry.paidByName,
                    payers.getOrDefault(entry.paidByName, 0D) + entry.amount);
        }
        double change = previousExpense <= 0D ? (currentExpense > 0D ? 100D : 0D)
                : ((currentExpense - previousExpense) / previousExpense) * 100D;
        int savingsRate = currentIncome <= 0D ? 0 : (int) Math.round(
                Math.max(0D, (currentIncome - currentExpense) / currentIncome * 100D));
        String trend = change > 5D ? getString(R.string.finance_trend_up)
                : change < -5D ? getString(R.string.finance_trend_down) : getString(R.string.finance_trend_flat);
        binding.financeAnalyticsSummary.setText(getString(R.string.finance_analytics_summary,
                String.format(Locale.getDefault(), "%+.0f%%", change), savingsRate,
                largestKey(categories), largestKey(accounts), largestKey(payers), trend));
    }

    private String largestKey(Map<String, Double> values) {
        String result = getString(R.string.finance_insight_none); double largest = -1D;
        for (Map.Entry<String, Double> value : values.entrySet()) {
            if (value.getValue() > largest) { largest = value.getValue(); result = value.getKey(); }
        }
        return result;
    }

    private void showEntryEditor(@Nullable FinanceEntry existingEntry) {
        DialogFinanceEntryBinding dialogBinding = DialogFinanceEntryBinding.inflate(getLayoutInflater());
        boolean isNewEntry = existingEntry == null;
        dialogBinding.financeEditorTitle.setText(isNewEntry
                ? R.string.add_finance_entry
                : R.string.edit_finance_entry);
        dialogBinding.financeEntryDateInput.setText(isNewEntry
                ? todayAsIsoDate()
                : existingEntry.transactionDate);
        String[] expenseCategories = getResources().getStringArray(
                R.array.finance_expense_category_labels
        );
        String[] incomeCategories = getResources().getStringArray(
                R.array.finance_income_category_labels
        );
        updateCategoryChoices(dialogBinding, expenseCategories);
        dialogBinding.financeAccountInput.setAdapter(new ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown, loadAccounts()
        ));
        dialogBinding.financePaymentMethodInput.setAdapter(new ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown,
                getResources().getStringArray(R.array.finance_payment_method_labels)
        ));
        dialogBinding.financeSplitTypeInput.setAdapter(new ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown,
                getResources().getStringArray(R.array.finance_split_type_labels)));
        dialogBinding.financeSettlementInput.setAdapter(new ArrayAdapter<>(
                requireContext(), R.layout.item_form_dropdown,
                getResources().getStringArray(R.array.finance_settlement_labels)));
        dialogBinding.financeSplitTypeInput.setText("NONE", false);
        dialogBinding.financeSettlementInput.setText("NOT_APPLICABLE", false);
        new FamilyAccountRepository().loadAuthorisedMembers(
                new FamilyAccountRepository.ResultCallback<List<FamilyAccountRepository.Member>>() {
                    @Override public void onSuccess(@Nullable List<FamilyAccountRepository.Member> members) {
                        if (binding == null || members == null) return;
                        List<String> names = new ArrayList<>();
                        for (FamilyAccountRepository.Member member : members) {
                            if (!member.displayName.trim().isEmpty()) names.add(member.displayName);
                        }
                        dialogBinding.financePaidByInput.setAdapter(new ArrayAdapter<>(requireContext(),
                                R.layout.item_form_dropdown, names));
                    }
                    @Override public void onError(@NonNull Exception error) { }
                });
        dialogBinding.financeAccountInput.setText(isNewEntry ? "Cash" : existingEntry.accountName, false);
        dialogBinding.financePaymentMethodInput.setText(
                isNewEntry ? "Cash" : existingEntry.paymentMethod, false
        );
        dialogBinding.financeTypeGroup.setOnCheckedChangeListener(
                (group, checkedId) -> updateCategoryChoices(
                        dialogBinding,
                        checkedId == R.id.type_income_button
                                ? incomeCategories
                                : expenseCategories
                )
        );

        if (!isNewEntry) {
            dialogBinding.financeAmountInput.setText(String.valueOf(existingEntry.amount));
            dialogBinding.financeCategoryInput.setText(existingEntry.category);
            dialogBinding.financeNoteInput.setText(existingEntry.note);
            dialogBinding.financeRecurringSwitch.setChecked(existingEntry.isRecurring);
            dialogBinding.financeSharedSwitch.setChecked(existingEntry.isShared);
            dialogBinding.financePaidByInput.setText(existingEntry.paidByName, false);
            dialogBinding.financeSplitTypeInput.setText(existingEntry.splitType, false);
            dialogBinding.financeParticipantsInput.setText(existingEntry.participantNames);
            dialogBinding.financeSplitAmountsInput.setText(existingEntry.splitAmounts);
            dialogBinding.financeSettlementInput.setText(existingEntry.settlementStatus, false);
            dialogBinding.financeTypeGroup.check(FinanceEntry.TYPE_INCOME.equals(existingEntry.entryType)
                    ? R.id.type_income_button
                    : R.id.type_expense_button);
            updateCategoryChoices(
                    dialogBinding,
                    FinanceEntry.TYPE_INCOME.equals(existingEntry.entryType)
                            ? incomeCategories
                            : expenseCategories
            );
        }

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

        dialogBinding.financeEntryDateInput.setOnClickListener(v -> showDatePicker(
                dialogBinding.financeEntryDateInput
        ));
        dialogBinding.financeEntryDateLayout.setEndIconOnClickListener(v -> showDatePicker(
                dialogBinding.financeEntryDateInput
        ));
        dialogBinding.cancelFinanceButton.setOnClickListener(v -> dialog.dismiss());
        dialogBinding.saveFinanceButton.setOnClickListener(v -> {
            Double amount = validateEditor(dialogBinding);
            if (amount == null) {
                return;
            }

            FinanceEntry entry = isNewEntry ? new FinanceEntry() : existingEntry;
            entry.entryType = dialogBinding.financeTypeGroup.getCheckedRadioButtonId() == R.id.type_income_button
                    ? FinanceEntry.TYPE_INCOME
                    : FinanceEntry.TYPE_EXPENSE;
            entry.amount = amount;
            entry.category = dialogBinding.financeCategoryInput.getText().toString().trim();
            entry.note = dialogBinding.financeNoteInput.getText().toString().trim();
            entry.transactionDate = dialogBinding.financeEntryDateInput.getText().toString().trim();
            entry.accountName = dialogBinding.financeAccountInput.getText().toString().trim();
            entry.paymentMethod = dialogBinding.financePaymentMethodInput.getText().toString().trim();
            entry.isRecurring = dialogBinding.financeRecurringSwitch.isChecked();
            entry.isShared = dialogBinding.financeSharedSwitch.isChecked();
            entry.paidByName = dialogBinding.financePaidByInput.getText().toString().trim();
            entry.splitType = dialogBinding.financeSplitTypeInput.getText().toString().trim();
            entry.participantNames = dialogBinding.financeParticipantsInput.getText().toString().trim();
            entry.splitAmounts = dialogBinding.financeSplitAmountsInput.getText().toString().trim();
            entry.settlementStatus = dialogBinding.financeSettlementInput.getText().toString().trim();
            if (!"NONE".equals(entry.splitType)) entry.isShared = true;
            rememberAccount(entry.accountName);

            repository.save(entry, () -> {
                if (binding == null) {
                    return;
                }
                dialog.dismiss();
                refreshData();
                Snackbar.make(
                        binding.getRoot(),
                        isNewEntry ? R.string.finance_entry_added : R.string.finance_entry_updated,
                        Snackbar.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    private void updateCategoryChoices(
            @NonNull DialogFinanceEntryBinding editor,
            @NonNull String[] categories
    ) {
        editor.financeCategoryInput.setAdapter(new ArrayAdapter<>(
                requireContext(),
                R.layout.item_form_dropdown,
                categories
        ));
    }

    @Nullable
    private Double validateEditor(DialogFinanceEntryBinding editor) {
        String amountText = editor.financeAmountInput.getText().toString().trim();
        String category = editor.financeCategoryInput.getText().toString().trim();
        String date = editor.financeEntryDateInput.getText().toString().trim();
        String account = editor.financeAccountInput.getText().toString().trim();
        String paymentMethod = editor.financePaymentMethodInput.getText().toString().trim();
        boolean valid = true;
        Double amount = null;

        try {
            amount = Double.parseDouble(amountText);
            if (!Double.isFinite(amount) || amount <= 0) {
                throw new NumberFormatException();
            }
            editor.financeAmountLayout.setError(null);
        } catch (NumberFormatException exception) {
            editor.financeAmountLayout.setError(getString(R.string.finance_amount_invalid));
            valid = false;
        }
        valid &= requireText(editor.financeCategoryLayout, category, R.string.finance_category_required);
        valid &= requireText(editor.financeAccountLayout, account, R.string.finance_account_required);
        valid &= requireText(editor.financePaymentMethodLayout, paymentMethod, R.string.finance_payment_required);
        String splitType = editor.financeSplitTypeInput.getText().toString().trim();
        String participants = editor.financeParticipantsInput.getText().toString().trim();
        if (!"NONE".equals(splitType)) {
            valid &= requireText(editor.financeParticipantsLayout, participants,
                    R.string.finance_participants_required);
            if (participants.split(",").length < 2) {
                editor.financeParticipantsLayout.setError(getString(R.string.finance_participants_minimum));
                valid = false;
            }
        } else {
            editor.financeParticipantsLayout.setError(null);
        }
        if ("CUSTOM".equals(splitType) && amount != null) {
            double splitTotal = customSplitTotal(editor.financeSplitAmountsInput.getText().toString());
            if (Math.abs(splitTotal - amount) > 0.01D) {
                editor.financeSplitAmountsLayout.setError(getString(
                        R.string.finance_split_total_error, currencyFormatter.format(amount)));
                valid = false;
            } else editor.financeSplitAmountsLayout.setError(null);
        } else editor.financeSplitAmountsLayout.setError(null);
        if (!isValidIsoDate(date)) {
            editor.financeEntryDateLayout.setError(getString(R.string.finance_date_invalid));
            valid = false;
        } else {
            editor.financeEntryDateLayout.setError(null);
        }
        return valid ? amount : null;
    }

    private double customSplitTotal(String input) {
        double total = 0D;
        try {
            for (String part : input.split(",")) {
                int separator = part.lastIndexOf(':');
                if (separator <= 0) return Double.NaN;
                double value = Double.parseDouble(part.substring(separator + 1).trim());
                if (!Double.isFinite(value) || value < 0) return Double.NaN;
                total += value;
            }
            return total;
        } catch (NumberFormatException error) { return Double.NaN; }
    }

    private String[] loadAccounts() {
        Set<String> accounts = new LinkedHashSet<>();
        for (String defaultAccount : getResources().getStringArray(R.array.finance_account_defaults)) {
            accounts.add(defaultAccount);
        }
        accounts.addAll(financePreferences().getStringSet(KEY_ACCOUNTS, new LinkedHashSet<>()));
        return accounts.toArray(new String[0]);
    }

    private void rememberAccount(String account) {
        if (account.isEmpty()) {
            return;
        }
        Set<String> accounts = new LinkedHashSet<>(
                financePreferences().getStringSet(KEY_ACCOUNTS, new LinkedHashSet<>())
        );
        accounts.add(account);
        financePreferences().edit().putStringSet(KEY_ACCOUNTS, accounts).apply();
    }

    private SharedPreferences financePreferences() {
        return requireContext().getSharedPreferences(FINANCE_PREFS, Context.MODE_PRIVATE);
    }

    private double monthlyBudget() {
        return Double.longBitsToDouble(financePreferences().getLong(
                KEY_MONTHLY_BUDGET, Double.doubleToRawLongBits(0D)
        ));
    }

    private void showBudgetEditor() {
        EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double budget = monthlyBudget();
        if (budget > 0) {
            input.setText(String.valueOf(budget));
        }
        int padding = getResources().getDimensionPixelSize(R.dimen.space_20);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.finance_budget)
                .setMessage(R.string.finance_budget_prompt)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        double value = Double.parseDouble(input.getText().toString().trim());
                        if (!Double.isFinite(value) || value < 0) {
                            throw new NumberFormatException();
                        }
                        financePreferences().edit().putLong(
                                KEY_MONTHLY_BUDGET, Double.doubleToRawLongBits(value)
                        ).apply();
                        dialog.dismiss();
                        updateSmartInsights();
                        Snackbar.make(binding.getRoot(), R.string.finance_budget_saved, Snackbar.LENGTH_SHORT).show();
                    } catch (NumberFormatException exception) {
                        input.setError(getString(R.string.finance_budget_invalid));
                    }
                }));
        dialog.show();
    }

    private void showMonthlyReport() {
        double balance = latestSummary.income - latestSummary.expense;
        double budget = monthlyBudget();
        String month = new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.finance_report_title, month))
                .setMessage(getString(
                        R.string.finance_report_body,
                        currencyFormatter.format(latestSummary.income),
                        currencyFormatter.format(latestSummary.expense),
                        currencyFormatter.format(balance),
                        currencyFormatter.format(budget),
                        currencyFormatter.format(budget - latestSummary.expense)
                ))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showReportOptions() {
        if (visibleEntries.isEmpty()) {
            Snackbar.make(binding.getRoot(), R.string.finance_export_empty,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        String[] options = {
                getString(R.string.finance_export_pdf),
                getString(R.string.finance_export_excel),
                getString(R.string.finance_share_pdf)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.finance_report_options)
                .setItems(options, (dialog, which) -> exportFinanceReport(
                        which != 1, which == 2))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void exportFinanceReport(boolean pdf, boolean share) {
        Context context = requireContext().getApplicationContext();
        List<FinanceEntry> reportRows = new ArrayList<>(visibleEntries);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File folder = new File(context.getCacheDir(), "finance_reports");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                        .format(new Date());
                File file = new File(folder, "Finance_Report_" + stamp
                        + (pdf ? ".pdf" : ".xls"));
                if (pdf) FinanceReportExporter.pdf(file, reportRows);
                else FinanceReportExporter.excel(file, reportRows);
                if (isAdded()) requireActivity().runOnUiThread(() -> shareFinanceFile(
                        file, pdf ? "application/pdf" : "application/vnd.ms-excel", share));
            } catch (Exception error) {
                if (isAdded()) requireActivity().runOnUiThread(() -> Snackbar.make(
                        binding.getRoot(), R.string.finance_export_error,
                        Snackbar.LENGTH_SHORT).show());
            }
        });
    }

    private void shareFinanceFile(File file, String mime, boolean directShare) {
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".backupfiles", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(directShare
                ? R.string.finance_share_pdf : R.string.finance_report_options)));
    }

    private boolean requireText(TextInputLayout layout, String value, int errorMessage) {
        if (TextUtils.isEmpty(value)) {
            layout.setError(getString(errorMessage));
            return false;
        }
        layout.setError(null);
        return true;
    }

    private void showDatePicker(EditText dateInput) {
        Calendar calendar = Calendar.getInstance();
        String value = dateInput.getText().toString().trim();
        if (isValidIsoDate(value)) {
            try {
                Date parsedDate = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).parse(value);
                if (parsedDate != null) {
                    calendar.setTime(parsedDate);
                }
            } catch (ParseException ignored) {
                // The date validator keeps the input safe.
            }
        }

        new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> dateInput.setText(String.format(
                        Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth
                )),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private String todayAsIsoDate() {
        return new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).format(new Date());
    }

    private boolean isValidIsoDate(String value) {
        if (value.isEmpty()) {
            return false;
        }
        SimpleDateFormat formatter = new SimpleDateFormat(ISO_DATE_PATTERN, Locale.US);
        formatter.setLenient(false);
        try {
            formatter.parse(value);
            return true;
        } catch (ParseException exception) {
            return false;
        }
    }

    private void confirmDelete(FinanceEntry entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_finance_entry_title)
                .setMessage(getString(R.string.delete_finance_entry_message, entry.category))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_delete, (dialog, which) -> repository.delete(entry, () -> {
                    if (binding == null) {
                        return;
                    }
                    refreshData();
                    Snackbar.make(binding.getRoot(), R.string.finance_entry_deleted, Snackbar.LENGTH_SHORT).show();
                }))
                .show();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
