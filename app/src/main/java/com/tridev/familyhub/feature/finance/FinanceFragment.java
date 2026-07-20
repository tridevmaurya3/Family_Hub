package com.tridev.familyhub.feature.finance;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
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

/** Complete local income and expense feature. */
public class FinanceFragment extends Fragment implements com.tridev.familyhub.feature.main.AddActionHost {

    private static final String ISO_DATE_PATTERN = "yyyy-MM-dd";

    private FragmentFinanceBinding binding;
    private FinanceEntryAdapter entryAdapter;
    private FinanceRepository repository;
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
            binding.monthExpenseValue.setText(currencyFormatter.format(safeSummary.expense));
            binding.monthIncomeValue.setText(currencyFormatter.format(safeSummary.income));
            binding.monthBalanceValue.setText(currencyFormatter.format(
                    safeSummary.income - safeSummary.expense
            ));
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

        if (!isNewEntry) {
            dialogBinding.financeAmountInput.setText(String.valueOf(existingEntry.amount));
            dialogBinding.financeCategoryInput.setText(existingEntry.category);
            dialogBinding.financeNoteInput.setText(existingEntry.note);
            dialogBinding.financeTypeGroup.check(FinanceEntry.TYPE_INCOME.equals(existingEntry.entryType)
                    ? R.id.type_income_button
                    : R.id.type_expense_button);
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

    @Nullable
    private Double validateEditor(DialogFinanceEntryBinding editor) {
        String amountText = editor.financeAmountInput.getText().toString().trim();
        String category = editor.financeCategoryInput.getText().toString().trim();
        String date = editor.financeEntryDateInput.getText().toString().trim();
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
        if (!isValidIsoDate(date)) {
            editor.financeEntryDateLayout.setError(getString(R.string.finance_date_invalid));
            valid = false;
        } else {
            editor.financeEntryDateLayout.setError(null);
        }
        return valid ? amount : null;
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
