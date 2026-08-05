package com.tridev.familyhub.feature.finance;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.SemanticValueStyler;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.tridev.familyhub.data.repository.FinanceRepository;
import com.tridev.familyhub.databinding.DialogFinanceEntryBinding;
import com.tridev.familyhub.databinding.FragmentFinanceBinding;
import com.tridev.familyhub.feature.finance.adapter.FinanceEntryAdapter;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

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
        binding.emptyAddFinanceButton.setOnClickListener(v -> showEntryEditor(null));
        binding.financeBudgetButton.setOnClickListener(v -> showBudgetEditor());
        binding.financeReportButton.setOnClickListener(v -> showMonthlyReport());
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
        refreshData();
    }

    @Override
    public void onAddRequested() {
        showEntryEditor(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            refreshData();
        }
    }

    private void refreshData() {
        loadEntries(binding.financeSearchInput.getText().toString());
        loadSummary();
    }

    private void loadEntries(String query) {
        repository.loadEntries(query, entries -> {
            if (binding == null) {
                return;
            }
            entryAdapter.submitList(entries);
            boolean isEmpty = entries.isEmpty();
            binding.financeRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            binding.financeEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        });
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
        });
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
                requireContext(), android.R.layout.simple_dropdown_item_1line, loadAccounts()
        ));
        dialogBinding.financePaymentMethodInput.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.finance_payment_method_labels)
        ));
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
                android.R.layout.simple_dropdown_item_1line,
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
        if (!isValidIsoDate(date)) {
            editor.financeEntryDateLayout.setError(getString(R.string.finance_date_invalid));
            valid = false;
        } else {
            editor.financeEntryDateLayout.setError(null);
        }
        return valid ? amount : null;
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
