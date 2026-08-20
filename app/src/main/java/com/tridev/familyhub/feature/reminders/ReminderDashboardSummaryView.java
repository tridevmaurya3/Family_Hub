package com.tridev.familyhub.feature.reminders;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.Reminder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only premium dashboard calculated from the reminders already loaded by
 * {@link RemindersFragment}. It deliberately does not persist, edit, schedule,
 * publish or otherwise mutate reminder data.
 */
public final class ReminderDashboardSummaryView extends LinearLayout {

    private final TextView todayValue;
    private final TextView upcomingValue;
    private final TextView overdueValue;
    private final TextView completedValue;
    private final TextView nextTitle;
    private final TextView nextDetail;
    private final SimpleDateFormat dateTimeFormat =
            new SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault());

    public ReminderDashboardSummaryView(@NonNull Context context) {
        this(context, null);
    }

    public ReminderDashboardSummaryView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        setOrientation(VERTICAL);

        MaterialCardView host = card(R.color.fh_surface_blue, R.color.fh_outline);
        addView(host, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(11), dp(12), dp(12));
        host.addView(body, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headingRow = new LinearLayout(context);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(headingRow, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = text("Smart reminder overview", 13f, true,
                R.color.fh_on_surface);
        headingRow.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView live = text("LIVE", 9.5f, true, R.color.fh_module_reminders);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(9), dp(4), dp(9), dp(4));
        live.setBackground(roundRect(
                ContextCompat.getColor(context, R.color.fh_module_reminders_container),
                ContextCompat.getColor(context, R.color.fh_module_reminders), 12));
        headingRow.addView(live);

        LinearLayout firstRow = statRow();
        body.addView(firstRow, topParams(9));
        todayValue = addStat(firstRow, "Today", R.color.fh_info_container,
                R.color.fh_info, 0, 4);
        upcomingValue = addStat(firstRow, "Upcoming", R.color.fh_warning_container,
                R.color.fh_warning, 4, 0);

        LinearLayout secondRow = statRow();
        body.addView(secondRow, topParams(8));
        overdueValue = addStat(secondRow, "Overdue", R.color.fh_error_container,
                R.color.fh_error, 0, 4);
        completedValue = addStat(secondRow, "Completed", R.color.fh_success_container,
                R.color.fh_success, 4, 0);

        MaterialCardView nextCard = card(
                R.color.fh_module_reminders_container, R.color.fh_module_reminders);
        body.addView(nextCard, topParams(10));
        LinearLayout nextBody = new LinearLayout(context);
        nextBody.setOrientation(VERTICAL);
        nextBody.setPadding(dp(12), dp(10), dp(12), dp(11));
        nextCard.addView(nextBody, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView nextLabel = text("NEXT IMPORTANT REMINDER", 9.5f, true,
                R.color.fh_module_reminders);
        nextBody.addView(nextLabel);
        nextTitle = text("No upcoming reminder", 12.5f, true, R.color.fh_on_surface);
        nextBody.addView(nextTitle, topParams(5));
        nextDetail = text("Add a reminder to start planning", 10.5f, false,
                R.color.fh_text_secondary);
        nextBody.addView(nextDetail, topParams(4));
    }

    public void setReminders(@Nullable List<Reminder> reminders) {
        long now = System.currentTimeMillis();
        long startToday = startOfToday();
        long endToday = startToday + 24L * 60L * 60L * 1000L;
        int today = 0;
        int upcoming = 0;
        int overdue = 0;
        int completed = 0;
        Reminder next = null;
        long nextAt = Long.MAX_VALUE;

        if (reminders != null) {
            for (Reminder reminder : reminders) {
                if (reminder == null) continue;
                if ("COMPLETED".equalsIgnoreCase(reminder.collaborationStatus)) {
                    completed++;
                    continue;
                }
                if (!reminder.isEnabled) continue;

                long effectiveAt = effectiveTrigger(reminder, now);
                if (Reminder.REPEAT_ONCE.equals(reminder.repeatType)
                        && reminder.reminderAt < now) {
                    overdue++;
                    continue;
                }
                if (effectiveAt >= startToday && effectiveAt < endToday) today++;
                if (effectiveAt >= now) {
                    upcoming++;
                    if (effectiveAt < nextAt) {
                        nextAt = effectiveAt;
                        next = reminder;
                    }
                }
            }
        }

        todayValue.setText(String.valueOf(today));
        upcomingValue.setText(String.valueOf(upcoming));
        overdueValue.setText(String.valueOf(overdue));
        completedValue.setText(String.valueOf(completed));

        if (next == null) {
            nextTitle.setText("No upcoming reminder");
            nextDetail.setText(overdue > 0
                    ? overdue + " overdue • Review pending reminders"
                    : "Your reminder schedule is clear");
        } else {
            nextTitle.setText(next.title.trim().isEmpty() ? "Untitled reminder" : next.title);
            String repeat = Reminder.REPEAT_ONCE.equals(next.repeatType)
                    ? "" : " • " + friendlyRepeat(next.repeatType);
            String assignee = next.assignedMemberName.trim().isEmpty()
                    ? "" : " • " + next.assignedMemberName.trim();
            nextDetail.setText(dateTimeFormat.format(new Date(nextAt)) + repeat + assignee);
        }
    }

    private long effectiveTrigger(@NonNull Reminder reminder, long now) {
        if (Reminder.REPEAT_ONCE.equals(reminder.repeatType)) return reminder.reminderAt;
        Calendar source = Calendar.getInstance();
        source.setTimeInMillis(reminder.reminderAt);
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(now);
        next.set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY));
        next.set(Calendar.MINUTE, source.get(Calendar.MINUTE));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        int field = Calendar.DAY_OF_YEAR;
        if (Reminder.REPEAT_WEEKLY.equals(reminder.repeatType)) field = Calendar.WEEK_OF_YEAR;
        else if (Reminder.REPEAT_MONTHLY.equals(reminder.repeatType)) field = Calendar.MONTH;
        else if (Reminder.REPEAT_YEARLY.equals(reminder.repeatType)) field = Calendar.YEAR;
        while (next.getTimeInMillis() < now) next.add(field, 1);
        return next.getTimeInMillis();
    }

    @NonNull
    private String friendlyRepeat(@NonNull String value) {
        String clean = value.toLowerCase(Locale.getDefault());
        return clean.substring(0, 1).toUpperCase(Locale.getDefault()) + clean.substring(1);
    }

    private long startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @NonNull
    private LinearLayout statRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        return row;
    }

    private TextView addStat(@NonNull LinearLayout row, @NonNull String label,
                             @ColorRes int fill, @ColorRes int accent,
                             int startMargin, int endMargin) {
        MaterialCardView card = card(fill, accent);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMarginStart(dp(startMargin));
        cardParams.setMarginEnd(dp(endMargin));
        row.addView(card, cardParams);

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.addView(body, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView labelView = text(label, 10.5f, true, R.color.fh_text_secondary);
        body.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("0", 17f, true, accent);
        body.addView(value);
        return value;
    }

    @NonNull
    private MaterialCardView card(@ColorRes int fill, @ColorRes int stroke) {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setRadius(dp(14));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(getContext(), stroke));
        card.setCardBackgroundColor(ContextCompat.getColor(getContext(), fill));
        return card;
    }

    @NonNull
    private TextView text(@NonNull String value, float size, boolean bold,
                          @ColorRes int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(getContext(), color));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    @NonNull
    private LayoutParams topParams(int topMargin) {
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    @NonNull
    private android.graphics.drawable.GradientDrawable roundRect(
            int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.argb(
                100, Color.red(stroke), Color.green(stroke), Color.blue(stroke)));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
