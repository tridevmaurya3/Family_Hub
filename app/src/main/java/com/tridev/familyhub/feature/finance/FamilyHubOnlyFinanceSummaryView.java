package com.tridev.familyhub.feature.finance;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.room.InvalidationTracker;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceEntry;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only monthly summary for entries that actually live in Family Hub Finance.
 * MoneyManager projections are intentionally excluded so the existing canonical
 * MoneyManager summary and this Family Hub-only summary can coexist independently.
 */
public final class FamilyHubOnlyFinanceSummaryView extends LinearLayout {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final NumberFormat currencyFormatter =
            NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final SimpleDateFormat monthKeyFormat =
            new SimpleDateFormat("yyyy-MM", Locale.US);
    private final SimpleDateFormat monthTitleFormat =
            new SimpleDateFormat("MMMM yyyy", Locale.US);

    private TextView titleView;
    private TextView expenseValue;
    private TextView incomeValue;
    private TextView balanceValue;
    private boolean observing;

    private final InvalidationTracker.Observer observer =
            new InvalidationTracker.Observer("finance_entries") {
                @Override
                public void onInvalidated(@NonNull Set<String> tables) {
                    refresh();
                }
            };

    public FamilyHubOnlyFinanceSummaryView(@NonNull Context context) {
        super(context);
        init();
    }

    public FamilyHubOnlyFinanceSummaryView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        init();
    }

    public FamilyHubOnlyFinanceSummaryView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);

        titleView = new TextView(getContext());
        TextViewCompat.setTextAppearance(
                titleView,
                R.style.TextAppearance_FamilyHub_Overline
        );
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(ContextCompat.getColor(
                getContext(), R.color.fh_module_finance));
        addView(titleView, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        LayoutParams rowParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        addView(row, rowParams);

        expenseValue = addSummaryCard(
                row,
                "Family expense",
                R.color.fh_error_container,
                R.color.fh_on_error_container,
                R.color.fh_error,
                1.0f,
                0,
                dp(4));

        incomeValue = addSummaryCard(
                row,
                "Family income",
                R.color.fh_success_container,
                R.color.fh_on_success_container,
                R.color.fh_success,
                1.0f,
                dp(4),
                dp(4));

        balanceValue = addSummaryCard(
                row,
                "Family balance",
                R.color.fh_info_container,
                R.color.fh_on_info_container,
                R.color.fh_info,
                1.15f,
                dp(4),
                0);

        render(0D, 0D);
    }

    @NonNull
    private TextView addSummaryCard(
            @NonNull LinearLayout row,
            @NonNull String label,
            int backgroundColor,
            int labelColor,
            int valueColor,
            float weight,
            int marginStart,
            int marginEnd
    ) {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setCardBackgroundColor(ContextCompat.getColor(getContext(), backgroundColor));
        card.setRadius(dp(12));
        card.setCardElevation(0f);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                weight);
        cardParams.setMarginStart(marginStart);
        cardParams.setMarginEnd(marginEnd);
        row.addView(card, cardParams);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setMinimumHeight(dp(64));
        content.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(getContext());
        TextViewCompat.setTextAppearance(
                labelView,
                R.style.TextAppearance_FamilyHub_Caption);
        labelView.setText(label);
        labelView.setMaxLines(1);
        labelView.setTextColor(ContextCompat.getColor(getContext(), labelColor));
        content.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(getContext());
        TextViewCompat.setTextAppearance(
                valueView,
                R.style.TextAppearance_FamilyHub_BodyStrong);
        valueView.setSingleLine(true);
        valueView.setTextColor(ContextCompat.getColor(getContext(), valueColor));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(6);
        content.addView(valueView, valueParams);
        return valueView;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observing) {
            FamilyHubDatabase.getInstance(getContext())
                    .getInvalidationTracker()
                    .addObserver(observer);
            observing = true;
        }
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (observing) {
            FamilyHubDatabase.getInstance(getContext())
                    .getInvalidationTracker()
                    .removeObserver(observer);
            observing = false;
        }
        super.onDetachedFromWindow();
    }

    private void refresh() {
        Context context = getContext().getApplicationContext();
        String monthPrefix = monthKeyFormat.format(new Date());
        EXECUTOR.execute(() -> {
            List<FinanceEntry> entries = FamilyHubDatabase.getInstance(context)
                    .financeEntryDao()
                    .getAll();
            double income = 0D;
            double expense = 0D;
            for (FinanceEntry entry : entries) {
                if (entry == null
                        || entry.transactionDate == null
                        || !entry.transactionDate.startsWith(monthPrefix)
                        || "UPCOMING".equals(entry.recurrenceStatus)
                        || FinanceEntrySourceClassifier.SOURCE_MONEY_MANAGER.equals(
                                FinanceEntrySourceClassifier.key(entry))) {
                    continue;
                }
                if (FinanceEntry.TYPE_INCOME.equals(entry.entryType)) {
                    income += entry.amount;
                } else if (FinanceEntry.TYPE_EXPENSE.equals(entry.entryType)) {
                    expense += entry.amount;
                }
            }
            double finalIncome = income;
            double finalExpense = expense;
            post(() -> render(finalIncome, finalExpense));
        });
    }

    private void render(double income, double expense) {
        titleView.setText("Family Hub only • " + monthTitleFormat.format(new Date()));
        expenseValue.setText(currencyFormatter.format(expense));
        incomeValue.setText(currencyFormatter.format(income));
        balanceValue.setText(currencyFormatter.format(income - expense));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
