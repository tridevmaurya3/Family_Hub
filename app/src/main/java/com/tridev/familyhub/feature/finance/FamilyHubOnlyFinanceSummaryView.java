package com.tridev.familyhub.feature.finance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.room.InvalidationTracker;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.FinanceEntry;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only monthly summary for entries that actually live in Family Hub Finance.
 * MoneyManager projections are intentionally excluded so the existing canonical
 * MoneyManager summary and this Family Hub-only summary can coexist independently.
 *
 * The same already-mounted view also upgrades the existing Finance analytics card
 * visually. This keeps FinanceFragment, repositories, sync bridges and transaction
 * behaviour untouched while giving analytics its own premium read-only presentation.
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

    private boolean analyticsInstalled;
    @Nullable private MaterialCardView analyticsHost;
    @Nullable private MaterialButton analyticsYearButton;
    @Nullable private MaterialButton analyticsMonthButton;
    @Nullable private TextView analyticsPeriodLabel;
    @Nullable private TextView analyticsIncomeValue;
    @Nullable private TextView analyticsExpenseValue;
    @Nullable private TextView analyticsSavingValue;
    @Nullable private TextView analyticsIncomeCompare;
    @Nullable private TextView analyticsExpenseCompare;
    @Nullable private TextView analyticsSavingCompare;
    @Nullable private TextView analyticsYoyTitle;
    @Nullable private TextView analyticsYoyDetail;
    @Nullable private TextView analyticsMonthTitle;
    @Nullable private TextView analyticsMonthDetail;
    @Nullable private TextView analyticsTopCategory;
    @Nullable private TextView analyticsTopAccount;
    @Nullable private TextView analyticsFamilyInsight;
    @Nullable private TextView analyticsWatchItems;

    @NonNull private List<FinanceEntry> analyticsEntries = new ArrayList<>();
    private int selectedAnalyticsYear = Calendar.getInstance().get(Calendar.YEAR);
    private int selectedAnalyticsMonth = Calendar.getInstance().get(Calendar.MONTH);

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
        post(() -> {
            installPremiumAnalytics();
            refresh();
        });
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
            List<FinanceEntry> analyticsCopy = new ArrayList<>(entries);
            post(() -> {
                analyticsEntries = analyticsCopy;
                render(finalIncome, finalExpense);
                updateSelectorLabels();
                renderAnalytics();
            });
        });
    }

    private void render(double income, double expense) {
        titleView.setText("Family Hub only • " + monthTitleFormat.format(new Date()));
        expenseValue.setText(currencyFormatter.format(expense));
        incomeValue.setText(currencyFormatter.format(income));
        balanceValue.setText(currencyFormatter.format(income - expense));
    }

    private void installPremiumAnalytics() {
        if (analyticsInstalled) return;
        View root = getRootView();
        MaterialCardView host = root.findViewById(R.id.finance_analytics_card);
        if (host == null) return;
        analyticsInstalled = true;
        analyticsHost = host;

        host.removeAllViews();
        host.setRadius(dp(20));
        host.setCardElevation(0f);
        host.setStrokeWidth(dp(1));
        host.setStrokeColor(Color.argb(150, 182, 201, 215));
        host.setCardBackgroundColor(Color.rgb(239, 247, 253));

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        host.addView(body, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = premiumText(
                getContext().getString(R.string.finance_analytics_title), 16f, true,
                ContextCompat.getColor(getContext(), R.color.fh_on_surface));
        body.addView(title);

        LinearLayout selectors = new LinearLayout(getContext());
        selectors.setOrientation(HORIZONTAL);
        selectors.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams selectorRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        selectorRowParams.topMargin = dp(10);
        body.addView(selectors, selectorRowParams);

        analyticsYearButton = premiumSelectorButton(String.valueOf(selectedAnalyticsYear));
        analyticsMonthButton = premiumSelectorButton(monthName(selectedAnalyticsMonth));
        analyticsYearButton.setOnClickListener(this::showYearSelector);
        analyticsMonthButton.setOnClickListener(this::showMonthSelector);
        selectors.addView(analyticsYearButton, new LinearLayout.LayoutParams(dp(108), dp(42)));
        LinearLayout.LayoutParams monthParams = new LinearLayout.LayoutParams(dp(128), dp(42));
        monthParams.setMarginStart(dp(10));
        selectors.addView(analyticsMonthButton, monthParams);

        MaterialCardView summarySection = premiumSectionCard();
        LinearLayout summaryBody = sectionBody(summarySection);
        analyticsPeriodLabel = sectionTitle(summaryBody,
                "1. Summary • " + monthName(selectedAnalyticsMonth) + " " + selectedAnalyticsYear);

        LinearLayout statRow = new LinearLayout(getContext());
        statRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams statRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statRowParams.topMargin = dp(8);
        summaryBody.addView(statRow, statRowParams);

        TextView[] incomeCard = addAnalyticsStatCard(
                statRow, "Income", R.color.fh_success_container,
                R.color.fh_success, 0, dp(4));
        analyticsIncomeValue = incomeCard[0];
        analyticsIncomeCompare = incomeCard[1];

        TextView[] expenseCard = addAnalyticsStatCard(
                statRow, "Expense", R.color.fh_error_container,
                R.color.fh_error, dp(4), dp(4));
        analyticsExpenseValue = expenseCard[0];
        analyticsExpenseCompare = expenseCard[1];

        TextView[] savingCard = addAnalyticsStatCard(
                statRow, "Saving", R.color.fh_info_container,
                R.color.fh_info, dp(4), 0);
        analyticsSavingValue = savingCard[0];
        analyticsSavingCompare = savingCard[1];

        addSectionToBody(body, summarySection);

        MaterialCardView trendSection = premiumSectionCard();
        LinearLayout trendBody = sectionBody(trendSection);
        sectionTitle(trendBody, "2. Trend & comparison");
        LinearLayout trendRow = new LinearLayout(getContext());
        trendRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams trendRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        trendRowParams.topMargin = dp(8);
        trendBody.addView(trendRow, trendRowParams);

        LinearLayout yoyColumn = premiumInfoColumn(
                Color.argb(248, 244, 249, 255), Color.argb(150, 164, 196, 222));
        analyticsYoyTitle = premiumText("", 11f, true,
                ContextCompat.getColor(getContext(), R.color.fh_text_secondary));
        analyticsYoyDetail = premiumText("", 12f, false,
                ContextCompat.getColor(getContext(), R.color.fh_on_surface));
        yoyColumn.addView(analyticsYoyTitle);
        LinearLayout.LayoutParams yoyDetailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        yoyDetailParams.topMargin = dp(6);
        yoyColumn.addView(analyticsYoyDetail, yoyDetailParams);
        LinearLayout.LayoutParams yoyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        yoyParams.setMarginEnd(dp(4));
        trendRow.addView(yoyColumn, yoyParams);

        LinearLayout monthColumn = premiumInfoColumn(
                Color.argb(248, 250, 252, 255), Color.argb(150, 190, 204, 216));
        analyticsMonthTitle = premiumText("", 11f, true,
                ContextCompat.getColor(getContext(), R.color.fh_text_secondary));
        analyticsMonthDetail = premiumText("", 12f, false,
                ContextCompat.getColor(getContext(), R.color.fh_on_surface));
        monthColumn.addView(analyticsMonthTitle);
        LinearLayout.LayoutParams monthDetailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        monthDetailParams.topMargin = dp(6);
        monthColumn.addView(analyticsMonthDetail, monthDetailParams);
        LinearLayout.LayoutParams monthColumnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        monthColumnParams.setMarginStart(dp(4));
        trendRow.addView(monthColumn, monthColumnParams);

        addSectionToBody(body, trendSection);

        MaterialCardView insightsSection = premiumSectionCard();
        LinearLayout insightsBody = sectionBody(insightsSection);
        sectionTitle(insightsBody, "3. Smart insights & alerts");
        LinearLayout insightsRowOne = new LinearLayout(getContext());
        insightsRowOne.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams insightRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        insightRowParams.topMargin = dp(8);
        insightsBody.addView(insightsRowOne, insightRowParams);

        analyticsTopCategory = addInsightCard(
                insightsRowOne, "Top category", R.color.fh_success_container,
                R.color.fh_success, 0, dp(4));
        analyticsTopAccount = addInsightCard(
                insightsRowOne, "Top account", R.color.fh_info_container,
                R.color.fh_info, dp(4), 0);

        LinearLayout insightsRowTwo = new LinearLayout(getContext());
        insightsRowTwo.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams secondInsightParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        secondInsightParams.topMargin = dp(8);
        insightsBody.addView(insightsRowTwo, secondInsightParams);

        analyticsFamilyInsight = addInsightCard(
                insightsRowTwo, "Family insight", R.color.fh_module_finance_container,
                R.color.fh_module_finance, 0, dp(4));
        analyticsWatchItems = addInsightCard(
                insightsRowTwo, "Watch items", R.color.fh_warning_container,
                R.color.fh_warning, dp(4), 0);

        addSectionToBody(body, insightsSection);

        TextView hint = premiumText("Tap any section for deeper insights  ⓘ", 10.5f, false,
                ContextCompat.getColor(getContext(), R.color.fh_text_secondary));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(9);
        body.addView(hint, hintParams);

        updateSelectorLabels();
        renderAnalytics();
    }

    @NonNull
    private MaterialButton premiumSelectorButton(@NonNull String label) {
        MaterialButton button = new MaterialButton(getContext());
        button.setAllCaps(false);
        button.setText(label + "  ▾");
        button.setTextSize(12f);
        button.setSingleLine(true);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(10), 0);
        button.setCornerRadius(dp(14));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(Color.argb(180, 141, 174, 205)));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.argb(250, 250, 253, 255)));
        button.setTextColor(ContextCompat.getColor(getContext(), R.color.fh_info));
        button.setElevation(dp(1));
        return button;
    }

    private void showYearSelector(@NonNull View anchor) {
        Calendar now = Calendar.getInstance();
        int currentYear = now.get(Calendar.YEAR);
        TreeSet<Integer> availableYears = new TreeSet<>((left, right) -> Integer.compare(right, left));
        availableYears.add(currentYear);
        for (FinanceEntry entry : analyticsEntries) {
            int year = entryYear(entry);
            if (year > 0 && year <= currentYear) availableYears.add(year);
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (Integer year : availableYears) {
            labels.add(String.valueOf(year));
            values.add(year);
        }
        int selectedIndex = Math.max(0, values.indexOf(selectedAnalyticsYear));
        showPremiumChoicePopup(anchor, labels, selectedIndex, index -> {
            selectedAnalyticsYear = values.get(index);
            if (selectedAnalyticsYear == currentYear
                    && selectedAnalyticsMonth > now.get(Calendar.MONTH)) {
                selectedAnalyticsMonth = now.get(Calendar.MONTH);
            }
            updateSelectorLabels();
            renderAnalytics();
        });
    }

    private void showMonthSelector(@NonNull View anchor) {
        Calendar now = Calendar.getInstance();
        int currentYear = now.get(Calendar.YEAR);
        int maxMonth = selectedAnalyticsYear == currentYear
                ? now.get(Calendar.MONTH) : Calendar.DECEMBER;
        List<String> labels = new ArrayList<>();
        for (int month = 0; month <= maxMonth; month++) {
            labels.add(monthName(month));
        }
        int selectedIndex = Math.max(0, Math.min(selectedAnalyticsMonth, labels.size() - 1));
        showPremiumChoicePopup(anchor, labels, selectedIndex, index -> {
            selectedAnalyticsMonth = index;
            updateSelectorLabels();
            renderAnalytics();
        });
    }

    private interface ChoiceListener {
        void onChoice(int index);
    }

    private void showPremiumChoicePopup(
            @NonNull View anchor,
            @NonNull List<String> labels,
            int selectedIndex,
            @NonNull ChoiceListener listener
    ) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(5), dp(5), dp(5), dp(5));
        content.setBackground(popupBackground());

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        PopupWindow popup = new PopupWindow(getContext());
        popup.setContentView(content);
        popup.setWidth(Math.max(anchor.getWidth(), dp(136)));
        int desiredHeight = dp(10) + labels.size() * dp(40);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        popup.setHeight(Math.min(desiredHeight, Math.round(screenHeight * 0.42f)));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        for (int index = 0; index < labels.size(); index++) {
            final int choiceIndex = index;
            boolean selected = index == selectedIndex;
            TextView row = premiumText(
                    (selected ? "✓  " : "   ") + labels.get(index),
                    12.5f,
                    selected,
                    selected
                            ? ContextCompat.getColor(getContext(), R.color.fh_info)
                            : ContextCompat.getColor(getContext(), R.color.fh_on_surface));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), 0, dp(10), 0);
            row.setBackground(popupRowBackground(selected));
            row.setOnClickListener(v -> {
                listener.onChoice(choiceIndex);
                popup.dismiss();
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
            if (index < labels.size() - 1) rowParams.bottomMargin = dp(4);
            rows.addView(row, rowParams);
        }
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void updateSelectorLabels() {
        if (analyticsYearButton != null) {
            analyticsYearButton.setText(selectedAnalyticsYear + "  ▾");
        }
        if (analyticsMonthButton != null) {
            analyticsMonthButton.setText(monthName(selectedAnalyticsMonth) + "  ▾");
        }
        if (analyticsPeriodLabel != null) {
            analyticsPeriodLabel.setText("1. Summary • "
                    + monthName(selectedAnalyticsMonth) + " " + selectedAnalyticsYear);
        }
    }

    private void renderAnalytics() {
        if (!analyticsInstalled || analyticsIncomeValue == null) return;
        AnalyticsSnapshot snapshot = buildAnalyticsSnapshot();
        analyticsIncomeValue.setText(currencyFormatter.format(snapshot.monthIncome));
        analyticsExpenseValue.setText(currencyFormatter.format(snapshot.monthExpense));
        analyticsSavingValue.setText(currencyFormatter.format(snapshot.monthSaving));

        analyticsIncomeCompare.setText(compareText(snapshot.monthIncome, snapshot.previousMonthIncome));
        analyticsExpenseCompare.setText(compareText(snapshot.monthExpense, snapshot.previousMonthExpense));
        analyticsSavingCompare.setText(compareText(snapshot.monthSaving, snapshot.previousMonthSaving));

        analyticsYoyTitle.setText("YoY • January–" + monthName(selectedAnalyticsMonth));
        analyticsYoyDetail.setText(coloredTrendText(
                trendValue(snapshot.ytdIncome, snapshot.previousYtdIncome),
                trendValue(snapshot.ytdExpense, snapshot.previousYtdExpense),
                trendValue(snapshot.ytdSaving, snapshot.previousYtdSaving),
                snapshot.ytdSaving));

        analyticsMonthTitle.setText("Month • "
                + monthName(selectedAnalyticsMonth) + " " + selectedAnalyticsYear);
        analyticsMonthDetail.setText(coloredTrendText(
                currencyFormatter.format(snapshot.monthIncome),
                currencyFormatter.format(snapshot.monthExpense),
                currencyFormatter.format(snapshot.monthSaving),
                snapshot.monthSaving));

        analyticsTopCategory.setText(
                snapshot.topCategory + "\n" + currencyFormatter.format(snapshot.topCategoryAmount));
        analyticsTopAccount.setText(
                snapshot.topAccount + "\n" + currencyFormatter.format(snapshot.topAccountAmount));

        String payerLine = snapshot.topPayer.isEmpty()
                ? "No family payer yet"
                : "Top payer • " + snapshot.topPayer;
        analyticsFamilyInsight.setText(
                "Saving rate " + String.format(Locale.getDefault(), "%+.1f%%", snapshot.savingRate)
                        + "\n" + payerLine);

        String watchText;
        if (snapshot.watchCount == 0) {
            watchText = "No alerts\nSpending looks stable";
        } else if (snapshot.watchCount == 1) {
            watchText = "1 alert\n" + snapshot.watchMessage;
        } else {
            watchText = snapshot.watchCount + " alerts\n" + snapshot.watchMessage;
        }
        analyticsWatchItems.setText(watchText);
    }

    @NonNull
    private AnalyticsSnapshot buildAnalyticsSnapshot() {
        AnalyticsSnapshot result = new AnalyticsSnapshot();
        int previousYear = selectedAnalyticsYear - 1;
        Map<String, Double> categories = new HashMap<>();
        Map<String, Double> accounts = new HashMap<>();
        Map<String, Double> payers = new HashMap<>();

        for (FinanceEntry entry : analyticsEntries) {
            if (entry == null || entry.transactionDate == null
                    || entry.transactionDate.length() < 7
                    || "UPCOMING".equals(entry.recurrenceStatus)) {
                continue;
            }
            int year = entryYear(entry);
            int month = entryMonth(entry);
            boolean income = FinanceEntry.TYPE_INCOME.equals(entry.entryType);
            boolean expense = FinanceEntry.TYPE_EXPENSE.equals(entry.entryType);
            if (!income && !expense) continue;

            if (year == selectedAnalyticsYear && month == selectedAnalyticsMonth) {
                if (income) {
                    result.monthIncome += entry.amount;
                } else {
                    result.monthExpense += entry.amount;
                    addAmount(categories, safe(entry.category), entry.amount);
                    addAmount(accounts, safe(entry.accountName), entry.amount);
                    if (!safe(entry.paidByName).isEmpty()) {
                        addAmount(payers, safe(entry.paidByName), entry.amount);
                    }
                }
            }
            if (year == previousYear && month == selectedAnalyticsMonth) {
                if (income) result.previousMonthIncome += entry.amount;
                else result.previousMonthExpense += entry.amount;
            }
            if (year == selectedAnalyticsYear && month <= selectedAnalyticsMonth) {
                if (income) result.ytdIncome += entry.amount;
                else result.ytdExpense += entry.amount;
            }
            if (year == previousYear && month <= selectedAnalyticsMonth) {
                if (income) result.previousYtdIncome += entry.amount;
                else result.previousYtdExpense += entry.amount;
            }
        }

        result.monthSaving = result.monthIncome - result.monthExpense;
        result.previousMonthSaving = result.previousMonthIncome - result.previousMonthExpense;
        result.ytdSaving = result.ytdIncome - result.ytdExpense;
        result.previousYtdSaving = result.previousYtdIncome - result.previousYtdExpense;
        result.savingRate = result.monthIncome <= 0D
                ? 0D : (result.monthSaving / result.monthIncome) * 100D;

        result.topCategory = largestKey(categories, "No expense yet");
        result.topCategoryAmount = categories.getOrDefault(result.topCategory, 0D);
        result.topAccount = largestKey(accounts, "No account yet");
        result.topAccountAmount = accounts.getOrDefault(result.topAccount, 0D);
        result.topPayer = largestKey(payers, "");

        double previousExpense = result.previousMonthExpense;
        double monthExpenseChange = previousExpense <= 0D
                ? 0D : ((result.monthExpense - previousExpense) / previousExpense) * 100D;
        double concentration = result.monthExpense <= 0D
                ? 0D : (result.topCategoryAmount / result.monthExpense) * 100D;
        if (monthExpenseChange > 5D) {
            result.watchCount++;
            result.watchMessage = "Expense increased";
        }
        if (concentration >= 40D) {
            result.watchCount++;
            result.watchMessage = result.watchMessage.isEmpty()
                    ? "High category concentration"
                    : "Expense increased • High concentration";
        }
        return result;
    }

    private void addAmount(@NonNull Map<String, Double> map,
                           @NonNull String key,
                           double amount) {
        String safeKey = key.isEmpty() ? "Uncategorized" : key;
        map.put(safeKey, map.getOrDefault(safeKey, 0D) + amount);
    }

    @NonNull
    private String largestKey(@NonNull Map<String, Double> values,
                              @NonNull String fallback) {
        String result = fallback;
        double largest = -1D;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (entry.getValue() > largest) {
                largest = entry.getValue();
                result = entry.getKey();
            }
        }
        return result;
    }

    @NonNull
    private String compareText(double current, double previous) {
        if (Math.abs(previous) < 0.01D) {
            return "vs " + (selectedAnalyticsYear - 1) + " • No data";
        }
        double change = ((current - previous) / Math.abs(previous)) * 100D;
        return "vs " + (selectedAnalyticsYear - 1) + " "
                + String.format(Locale.getDefault(), "%+.1f%%", change);
    }

    @NonNull
    private String signedPercent(double current, double previous) {
        if (Math.abs(previous) < 0.01D) return "No previous data";
        double change = ((current - previous) / Math.abs(previous)) * 100D;
        return String.format(Locale.getDefault(), "%+.1f%%", change);
    }

    @NonNull
    private String trendValue(double current, double previous) {
        if (Math.abs(previous) < 0.01D) return "No previous-year data";
        return signedPercent(current, previous);
    }

    @NonNull
    private CharSequence coloredTrendText(
            @NonNull String income,
            @NonNull String expense,
            @NonNull String saving,
            double savingValue
    ) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        appendTrendLine(text, "Income", income,
                ContextCompat.getColor(getContext(), R.color.fh_success));
        text.append('\n');
        appendTrendLine(text, "Expense", expense,
                ContextCompat.getColor(getContext(), R.color.fh_error));
        text.append('\n');
        appendTrendLine(text, "Net saving", saving,
                ContextCompat.getColor(getContext(), savingValue >= 0D
                        ? R.color.fh_success : R.color.fh_error));
        return text;
    }

    private void appendTrendLine(
            @NonNull SpannableStringBuilder target,
            @NonNull String label,
            @NonNull String value,
            int color
    ) {
        int start = target.length();
        target.append(label).append("  ").append(value);
        target.setSpan(new ForegroundColorSpan(color), start, target.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setSpan(new StyleSpan(Typeface.BOLD), start, target.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private int entryYear(@NonNull FinanceEntry entry) {
        try {
            return Integer.parseInt(entry.transactionDate.substring(0, 4));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int entryMonth(@NonNull FinanceEntry entry) {
        try {
            return Integer.parseInt(entry.transactionDate.substring(5, 7)) - 1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    @NonNull
    private String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private String monthName(int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.MONTH, Math.max(Calendar.JANUARY, Math.min(Calendar.DECEMBER, month)));
        return new SimpleDateFormat("MMMM", Locale.getDefault()).format(calendar.getTime());
    }

    @NonNull
    private MaterialCardView premiumSectionCard() {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setRadius(dp(14));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(Color.argb(130, 204, 215, 224));
        card.setCardBackgroundColor(Color.argb(248, 255, 255, 255));
        return card;
    }

    @NonNull
    private LinearLayout sectionBody(@NonNull MaterialCardView card) {
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setPadding(dp(10), dp(9), dp(10), dp(10));
        card.addView(body, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    @NonNull
    private TextView sectionTitle(@NonNull LinearLayout parent, @NonNull String text) {
        TextView title = premiumText(text, 12f, true,
                ContextCompat.getColor(getContext(), R.color.fh_info));
        parent.addView(title);
        return title;
    }

    private void addSectionToBody(@NonNull LinearLayout body,
                                  @NonNull MaterialCardView section) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        body.addView(section, params);
    }

    @NonNull
    private TextView[] addAnalyticsStatCard(
            @NonNull LinearLayout row,
            @NonNull String label,
            int backgroundColor,
            int valueColor,
            int marginStart,
            int marginEnd
    ) {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setCardBackgroundColor(ContextCompat.getColor(getContext(), backgroundColor));
        card.setRadius(dp(12));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(Color.argb(95, 184, 200, 210));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMarginStart(marginStart);
        cardParams.setMarginEnd(marginEnd);
        row.addView(card, cardParams);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.setMinimumHeight(dp(82));
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = premiumText(label, 10.5f, false,
                ContextCompat.getColor(getContext(), R.color.fh_text_secondary));
        content.addView(labelView);
        TextView value = premiumText("₹0.00", 12f, true,
                ContextCompat.getColor(getContext(), valueColor));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(5);
        content.addView(value, valueParams);
        TextView compare = premiumText("", 9.5f, false,
                ContextCompat.getColor(getContext(), R.color.fh_text_secondary));
        LinearLayout.LayoutParams compareParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        compareParams.topMargin = dp(4);
        content.addView(compare, compareParams);
        return new TextView[]{value, compare};
    }

    @NonNull
    private LinearLayout premiumInfoColumn(int fillColor, int strokeColor) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setPadding(dp(10), dp(9), dp(10), dp(10));
        column.setMinimumHeight(dp(102));
        column.setBackground(roundedBackground(fillColor, strokeColor, 12));
        column.setElevation(dp(1));
        return column;
    }

    @NonNull
    private TextView addInsightCard(
            @NonNull LinearLayout row,
            @NonNull String label,
            int backgroundColor,
            int accentColor,
            int marginStart,
            int marginEnd
    ) {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setCardBackgroundColor(ContextCompat.getColor(getContext(), backgroundColor));
        card.setRadius(dp(12));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(Color.argb(95, 184, 200, 210));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMarginStart(marginStart);
        cardParams.setMarginEnd(marginEnd);
        row.addView(card, cardParams);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(9), dp(8), dp(9), dp(9));
        content.setMinimumHeight(dp(78));
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView labelView = premiumText(label + "  ›", 10.5f, true,
                ContextCompat.getColor(getContext(), accentColor));
        content.addView(labelView);
        TextView detail = premiumText("—", 10.5f, false,
                ContextCompat.getColor(getContext(), R.color.fh_on_surface));
        detail.setMaxLines(3);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(6);
        content.addView(detail, detailParams);
        return detail;
    }

    @NonNull
    private TextView premiumText(@NonNull String text,
                                 float size,
                                 boolean bold,
                                 int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    @NonNull
    private GradientDrawable popupBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(253, 255, 255, 255), Color.argb(250, 241, 248, 253)});
        drawable.setCornerRadius(dp(15));
        drawable.setStroke(dp(1), Color.argb(210, 199, 211, 221));
        return drawable;
    }

    @NonNull
    private GradientDrawable popupRowBackground(boolean selected) {
        return roundedBackground(
                selected ? Color.argb(245, 232, 244, 253) : Color.argb(250, 255, 255, 255),
                selected ? Color.argb(170, 120, 170, 208) : Color.argb(120, 212, 222, 226),
                10);
    }

    @NonNull
    private GradientDrawable roundedBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private static final class AnalyticsSnapshot {
        double monthIncome;
        double monthExpense;
        double monthSaving;
        double previousMonthIncome;
        double previousMonthExpense;
        double previousMonthSaving;
        double ytdIncome;
        double ytdExpense;
        double ytdSaving;
        double previousYtdIncome;
        double previousYtdExpense;
        double previousYtdSaving;
        double savingRate;
        String topCategory = "No expense yet";
        double topCategoryAmount;
        String topAccount = "No account yet";
        double topAccountAmount;
        String topPayer = "";
        int watchCount;
        String watchMessage = "";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
