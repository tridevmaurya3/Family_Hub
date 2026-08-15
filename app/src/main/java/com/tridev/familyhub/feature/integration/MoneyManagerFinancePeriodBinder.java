package com.tridev.familyhub.feature.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * STEP 13D UI period selector for MoneyManager-backed Family Hub finance.
 *
 * The selector is injected into the existing Analytics card so the XML layout
 * stays compact. The selected Month/Year is local-only and causes the existing
 * summary/category/account binders to refresh from MoneyManager.
 */
public final class MoneyManagerFinancePeriodBinder
        implements Application.ActivityLifecycleCallbacks {

    private static final String BUTTON_TAG = "money_manager_finance_period_selector";
    private static final String[] MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(new MoneyManagerFinancePeriodBinder());
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
        View analyticsCard = root.findViewById(R.id.finance_analytics_card);
        TextView analyticsSummary = root.findViewById(R.id.finance_analytics_summary);
        if (analyticsCard == null || analyticsSummary == null) return;
        if (!(analyticsSummary.getParent() instanceof LinearLayout)) return;

        LinearLayout container = (LinearLayout) analyticsSummary.getParent();
        MaterialButton button = findButton(container);
        if (button == null) {
            button = new MaterialButton(activity);
            button.setTag(BUTTON_TAG);
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setTextSize(13f);
            button.setMinHeight(dp(activity, 40));
            button.setContentDescription("Select MoneyManager finance month and year");

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(activity, 6);
            params.bottomMargin = dp(activity, 4);
            button.setLayoutParams(params);

            int summaryIndex = container.indexOfChild(analyticsSummary);
            container.addView(button, Math.max(1, summaryIndex));
            MaterialButton finalButton = button;
            button.setOnClickListener(v -> showPeriodPicker(activity, root, finalButton));
        }

        MoneyManagerFinancePeriodStore.Selection selected =
                MoneyManagerFinancePeriodStore.get(activity);
        button.setText(periodLabel(selected.year, selected.month) + "  ▾");
        updateSummaryPeriodLabel(root, selected.year, selected.month);
    }

    @Nullable
    private MaterialButton findButton(@NonNull LinearLayout container) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child instanceof MaterialButton && BUTTON_TAG.equals(child.getTag())) {
                return (MaterialButton) child;
            }
        }
        return null;
    }

    private void showPeriodPicker(
            @NonNull Activity activity,
            @NonNull View root,
            @NonNull MaterialButton button) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        MoneyManagerFinancePeriodStore.Selection selected =
                MoneyManagerFinancePeriodStore.get(activity);
        Calendar now = Calendar.getInstance();

        NumberPicker monthPicker = new NumberPicker(activity);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setDisplayedValues(MONTHS);
        monthPicker.setValue(selected.month);
        monthPicker.setWrapSelectorWheel(true);

        NumberPicker yearPicker = new NumberPicker(activity);
        yearPicker.setMinValue(2000);
        yearPicker.setMaxValue(now.get(Calendar.YEAR) + 1);
        yearPicker.setValue(Math.max(2000,
                Math.min(selected.year, now.get(Calendar.YEAR) + 1)));
        yearPicker.setWrapSelectorWheel(false);

        LinearLayout pickerRow = new LinearLayout(activity);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER);
        pickerRow.setPadding(dp(activity, 12), dp(activity, 8),
                dp(activity, 12), dp(activity, 4));
        pickerRow.addView(monthPicker, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pickerRow.addView(yearPicker, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Select finance period")
                .setView(pickerRow)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Current month", (dialog, which) -> {
                    MoneyManagerFinancePeriodStore.resetToCurrentMonth(activity);
                    MoneyManagerFinancePeriodStore.Selection current =
                            MoneyManagerFinancePeriodStore.get(activity);
                    refresh(activity, root, button, current.year, current.month);
                })
                .setPositiveButton("Apply", (dialog, which) -> {
                    int year = yearPicker.getValue();
                    int month = monthPicker.getValue();
                    MoneyManagerFinancePeriodStore.set(activity, year, month);
                    refresh(activity, root, button, year, month);
                })
                .show();
    }

    private void refresh(
            @NonNull Activity activity,
            @NonNull View root,
            @NonNull MaterialButton button,
            int year,
            int month) {
        button.setText(periodLabel(year, month) + "  ▾");
        updateSummaryPeriodLabel(root, year, month);
        root.requestLayout();
        root.postDelayed(root::requestLayout, 1800L);
        root.postDelayed(root::requestLayout, 3200L);
    }

    private void updateSummaryPeriodLabel(@NonNull View root, int year, int month) {
        View card = root.findViewById(R.id.month_summary_card);
        if (!(card instanceof ViewGroup)) return;
        ViewGroup cardGroup = (ViewGroup) card;
        if (cardGroup.getChildCount() == 0
                || !(cardGroup.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) cardGroup.getChildAt(0);
        if (content.getChildCount() == 0
                || !(content.getChildAt(0) instanceof TextView)) return;
        TextView label = (TextView) content.getChildAt(0);
        label.setText("Finance period • " + periodLabel(year, month));
    }

    @NonNull
    private String periodLabel(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, Math.max(0, Math.min(11, month - 1)));
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH)
                .format(calendar.getTime());
    }

    private int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(
            @NonNull Activity activity, @NonNull Bundle outState) { }
    @Override public void onActivityCreated(
            @NonNull Activity activity, @Nullable Bundle savedInstanceState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener == null) return;
        View root = activity.getWindow().getDecorView();
        if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }
}
