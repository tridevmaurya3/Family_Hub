package com.tridev.familyhub.feature.chat;

import android.text.format.DateFormat;

import androidx.fragment.app.Fragment;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.LongConsumer;

/** Converts Material's UTC calendar selection into a local calendar date and time. */
final class ChatDateTimePicker {
    private ChatDateTimePicker() {}

    static void pickDate(Fragment host, String title, long initial, boolean futureOnly, LongConsumer selected) {
        if (!host.isAdded() || host.getParentFragmentManager().isStateSaved()) return;
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(initial);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(title).setSelection(utc.getTimeInMillis());
        if (futureOnly) {
            builder.setCalendarConstraints(new CalendarConstraints.Builder()
                    .setValidator(DateValidatorPointForward.from(MaterialDatePicker.todayInUtcMilliseconds()))
                    .build());
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(value -> {
            Calendar chosen = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            chosen.setTimeInMillis(value);
            Calendar localDay = Calendar.getInstance();
            localDay.clear();
            localDay.set(chosen.get(Calendar.YEAR), chosen.get(Calendar.MONTH), chosen.get(Calendar.DAY_OF_MONTH));
            selected.accept(localDay.getTimeInMillis());
        });
        picker.show(host.getParentFragmentManager(), "chat-date-picker");
    }

    static void pickDateTime(Fragment host, long initial, boolean futureOnly, LongConsumer selected) {
        pickDate(host, "Select date", initial, futureOnly, date -> {
            if (!host.isAdded() || host.getParentFragmentManager().isStateSaved()) return;
            Calendar start = Calendar.getInstance();
            start.setTimeInMillis(initial);
            MaterialTimePicker picker = new MaterialTimePicker.Builder()
                    .setTitleText("Select time")
                    .setTimeFormat(DateFormat.is24HourFormat(host.requireContext()) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                    .setHour(start.get(Calendar.HOUR_OF_DAY)).setMinute(start.get(Calendar.MINUTE)).build();
            picker.addOnPositiveButtonClickListener(view -> {
                Calendar local = Calendar.getInstance();
                local.setTimeInMillis(date);
                local.set(Calendar.HOUR_OF_DAY, picker.getHour());
                local.set(Calendar.MINUTE, picker.getMinute());
                selected.accept(local.getTimeInMillis());
            });
            picker.show(host.getParentFragmentManager(), "chat-time-picker");
        });
    }
}
