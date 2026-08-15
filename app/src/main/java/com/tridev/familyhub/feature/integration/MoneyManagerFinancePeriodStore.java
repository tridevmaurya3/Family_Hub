package com.tridev.familyhub.feature.integration;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Calendar;

/**
 * Local-only selected MoneyManager finance period for Family Hub analytics.
 * The selection is never uploaded to Firebase.
 */
public final class MoneyManagerFinancePeriodStore {

    private static final String PREFS = "money_manager_finance_period_v1";
    private static final String KEY_YEAR = "year";
    private static final String KEY_MONTH = "month";

    public static final class Selection {
        public final int year;
        public final int month;

        private Selection(int year, int month) {
            this.year = year;
            this.month = month;
        }
    }

    private MoneyManagerFinancePeriodStore() { }

    @NonNull
    public static Selection get(@NonNull Context context) {
        Calendar now = Calendar.getInstance();
        int defaultYear = now.get(Calendar.YEAR);
        int defaultMonth = now.get(Calendar.MONTH) + 1;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int year = prefs.getInt(KEY_YEAR, defaultYear);
        int month = prefs.getInt(KEY_MONTH, defaultMonth);
        if (!valid(year, month)) {
            year = defaultYear;
            month = defaultMonth;
        }
        return new Selection(year, month);
    }

    public static void set(@NonNull Context context, int year, int month) {
        if (!valid(year, month)) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_YEAR, year)
                .putInt(KEY_MONTH, month)
                .apply();
    }

    public static void resetToCurrentMonth(@NonNull Context context) {
        Calendar now = Calendar.getInstance();
        set(context, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
    }

    private static boolean valid(int year, int month) {
        return year >= 2000 && year <= 2100 && month >= 1 && month <= 12;
    }
}
