package com.tridev.familyhub.feature.tasks;

import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.repository.FinanceRepository;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Optional, non-destructive Task -> Finance / Loan reference picker. */
final class FamilyTaskLinkDialog {
    private FamilyTaskLinkDialog() { }

    static void show(@NonNull Context context, @NonNull FamilyTask task,
                     @NonNull Runnable onSaved) {
        new FinanceRepository(context).loadEntries("", entries ->
                showLoaded(context, task, entries, onSaved));
    }

    private static void showLoaded(@NonNull Context context, @NonNull FamilyTask task,
                                   @NonNull List<FinanceEntry> entries,
                                   @NonNull Runnable onSaved) {
        FamilyTaskLinkStore.Link current = FamilyTaskLinkStore.load(context, task);

        List<String> financeLabels = new ArrayList<>();
        List<String> financeIds = new ArrayList<>();
        financeLabels.add(context.getString(R.string.family_tasks_no_finance_link));
        financeIds.add("");

        List<String> loanLabels = new ArrayList<>();
        List<String> loanIds = new ArrayList<>();
        List<String> loanNames = new ArrayList<>();
        loanLabels.add(context.getString(R.string.family_tasks_no_loan_link));
        loanIds.add("");
        loanNames.add("");

        Set<String> seenLoanIds = new HashSet<>();
        for (FinanceEntry entry : entries) {
            if (!entry.isShared || entry.cloudId == null || entry.cloudId.trim().isEmpty()) {
                continue;
            }
            if (isLoanProjection(entry)) {
                String loanId = loanId(entry.note);
                if (loanId.isEmpty() || !seenLoanIds.add(loanId)) continue;
                String loanName = clean(entry.accountName).isEmpty()
                        ? context.getString(R.string.family_tasks_loan_manager)
                        : clean(entry.accountName);
                loanLabels.add(loanLabel(loanName, entry));
                loanIds.add(loanId);
                loanNames.add(loanName);
            } else {
                financeLabels.add(financeLabel(context, entry));
                financeIds.add(entry.cloudId.trim());
            }
        }

        ensureCurrentFinance(current, financeLabels, financeIds, context);
        ensureCurrentLoan(current, loanLabels, loanIds, loanNames, context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        root.setPadding(pad, dp(context, 10), pad, dp(context, 8));
        root.setBackground(context.getDrawable(R.drawable.bg_form_three_tone));

        TextView intro = new TextView(context);
        intro.setText(R.string.family_tasks_link_dialog_message);
        intro.setTextSize(14f);
        root.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialAutoCompleteTextView financeInput = dropdown(context,
                root, R.string.family_tasks_finance_link, financeLabels);
        MaterialAutoCompleteTextView loanInput = dropdown(context,
                root, R.string.family_tasks_loan_link, loanLabels);

        int financeIndex = indexOf(financeIds, current.financeCloudId);
        int loanIndex = indexOf(loanIds, current.loanId);
        financeInput.setText(financeLabels.get(financeIndex), false);
        loanInput.setText(loanLabels.get(loanIndex), false);

        new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_FamilyHub_FormDialog)
                .setTitle(R.string.family_tasks_link_dialog_title)
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.family_tasks_save, (dialog, which) -> {
                    int selectedFinance = indexOf(financeLabels, text(financeInput));
                    int selectedLoan = indexOf(loanLabels, text(loanInput));
                    FamilyTaskLinkStore.Link link = new FamilyTaskLinkStore.Link(
                            financeIds.get(selectedFinance),
                            selectedFinance == 0 ? "" : financeLabels.get(selectedFinance),
                            loanIds.get(selectedLoan),
                            loanNames.get(selectedLoan)
                    );
                    FamilyTaskLinkStore.save(context, task, link);
                    Toast.makeText(context, R.string.family_tasks_links_saved,
                            Toast.LENGTH_SHORT).show();
                    onSaved.run();
                })
                .show();
    }

    @NonNull
    private static MaterialAutoCompleteTextView dropdown(
            @NonNull Context context, @NonNull LinearLayout root, int hint,
            @NonNull List<String> labels) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(context.getString(hint));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(context, 15), dp(context, 15),
                dp(context, 15), dp(context, 15));
        layout.setBoxBackgroundColor(context.getColor(R.color.fh_form_surface));
        layout.setBoxStrokeColor(context.getColor(R.color.fh_form_accent));
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 12);
        root.addView(layout, lp);

        MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(context);
        input.setInputType(InputType.TYPE_NULL);
        input.setTextSize(12f);
        input.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        input.setPadding(dp(context, 10), 0, dp(context, 8), 0);
        input.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_dropdown_item_1line, labels));
        input.setOnClickListener(v -> input.showDropDown());
        layout.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)));
        return input;
    }

    private static boolean isLoanProjection(@NonNull FinanceEntry entry) {
        if ("LoanManagerPro".equalsIgnoreCase(clean(entry.paymentMethod))) return true;
        return clean(entry.note).startsWith("[LoanManagerProjection]");
    }

    @NonNull
    private static String loanId(String note) {
        String safe = clean(note);
        int start = safe.indexOf(" loan=");
        if (start < 0) return "";
        start += 6;
        int end = safe.indexOf(" payment=", start);
        if (end < 0) end = safe.length();
        return safe.substring(start, end).trim();
    }

    @NonNull
    private static String financeLabel(@NonNull Context context,
                                       @NonNull FinanceEntry entry) {
        String category = clean(entry.category).isEmpty()
                ? context.getString(R.string.family_tasks_finance_entry)
                : clean(entry.category);
        return category + " • " + money(entry.amount) + dateSuffix(entry.transactionDate);
    }

    @NonNull
    private static String loanLabel(@NonNull String loanName,
                                    @NonNull FinanceEntry entry) {
        return loanName + " • " + money(entry.amount) + dateSuffix(entry.transactionDate);
    }

    @NonNull
    private static String money(double amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        format.setMaximumFractionDigits(amount == Math.rint(amount) ? 0 : 2);
        return format.format(amount);
    }

    @NonNull
    private static String dateSuffix(String date) {
        String safe = clean(date);
        return safe.isEmpty() ? "" : " • " + safe;
    }

    private static void ensureCurrentFinance(
            @NonNull FamilyTaskLinkStore.Link current,
            @NonNull List<String> labels, @NonNull List<String> ids,
            @NonNull Context context) {
        if (!current.hasFinance() || ids.contains(current.financeCloudId)) return;
        String label = current.financeLabel.isEmpty()
                ? context.getString(R.string.family_tasks_unavailable_finance_link)
                : current.financeLabel;
        labels.add(label);
        ids.add(current.financeCloudId);
    }

    private static void ensureCurrentLoan(
            @NonNull FamilyTaskLinkStore.Link current,
            @NonNull List<String> labels, @NonNull List<String> ids,
            @NonNull List<String> names, @NonNull Context context) {
        if (!current.hasLoan() || ids.contains(current.loanId)) return;
        String name = current.loanName.isEmpty()
                ? context.getString(R.string.family_tasks_loan_manager)
                : current.loanName;
        labels.add(context.getString(R.string.family_tasks_unavailable_loan_link, name));
        ids.add(current.loanId);
        names.add(name);
    }

    private static int indexOf(@NonNull List<String> values, String wanted) {
        String clean = wanted == null ? "" : wanted;
        int index = values.indexOf(clean);
        return index < 0 ? 0 : index;
    }

    @NonNull
    private static String text(@NonNull MaterialAutoCompleteTextView input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    @NonNull
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
