package com.tridev.familyhub.feature.quickhub;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.AlarmClock;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;

final class QuickHubTools {
    private final Context context;
    private AlertDialog dialog;

    QuickHubTools(Context context) {
        this.context = context;
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    void calculator() {
        LinearLayout content = column();
        TextView display = new TextView(context);
        display.setTextSize(24);
        display.setText("0");
        StringBuilder expression = new StringBuilder();
        boolean[] degrees = {true};
        boolean[] evaluated = {false};

        LinearLayout scientific = column();
        scientific.setVisibility(android.view.View.GONE);
        MaterialButton mode = button("Scientific");
        mode.setOnClickListener(v -> {
            boolean expanded = scientific.getVisibility() != android.view.View.VISIBLE;
            scientific.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
            mode.setText(expanded ? "Basic" : "Scientific");
        });
        content.addView(display);
        content.addView(mode);
        content.addView(scientific);

        java.util.function.Consumer<String> key = value -> {
            if ("=".equals(value)) {
                try {
                    double result = CalculatorExpression.evaluate(expression.toString(), degrees[0]);
                    String number = BigDecimal.valueOf(result).round(MathContext.DECIMAL64)
                            .stripTrailingZeros().toPlainString();
                    expression.setLength(0);
                    expression.append(number);
                    evaluated[0] = true;
                } catch (IllegalArgumentException e) {
                    display.setText("Invalid calculation");
                    return;
                }
            } else if ("AC".equals(value)) {
                expression.setLength(0);
                evaluated[0] = false;
            } else if ("⌫".equals(value)) {
                if (expression.length() > 0) expression.deleteCharAt(expression.length() - 1);
                evaluated[0] = false;
            } else {
                if (evaluated[0] && !"+-×÷^%".contains(value)) expression.setLength(0);
                evaluated[0] = false;
                if (expression.length() + value.length() <= 256) expression.append(value);
            }
            display.setText(expression.length() == 0 ? "0" : expression.toString());
        };
        MaterialButton angle = button("DEG");
        angle.setOnClickListener(v -> {
            degrees[0] = !degrees[0];
            angle.setText(degrees[0] ? "DEG" : "RAD");
        });
        scientific.addView(angle);
        keys(scientific, key, new String[][]{
                {"sin(", "cos(", "tan("}, {"sqrt(", "log(", "ln("}, {"π", "e", "^"}
        });
        keys(content, key, new String[][]{
                {"AC", "⌫", "%", "÷"}, {"7", "8", "9", "×"}, {"4", "5", "6", "-"},
                {"1", "2", "3", "+"}, {"(", "0", ")", "."}, {"="}
        });
        show("Calculator", content);
    }

    void alarm() {
        LinearLayout content = column();
        Calendar now = Calendar.getInstance();
        TimePicker time = (TimePicker) LayoutInflater.from(context)
                .inflate(R.layout.quick_hub_alarm_time, content, false);
        time.setIs24HourView(DateFormat.is24HourFormat(context));
        time.setHour(now.get(Calendar.HOUR_OF_DAY));
        time.setMinute(now.get(Calendar.MINUTE));
        content.addView(time);
        EditText label = new EditText(context);
        label.setHint("Alarm label (optional)");
        label.setSingleLine();
        content.addView(label);

        TextView repeat = new TextView(context);
        repeat.setText("Repeat days (optional)");
        content.addView(repeat);
        String[] names = new DateFormatSymbols().getShortWeekdays();
        CheckBox[] days = new CheckBox[7];
        for (int i = 0; i < days.length; i++) {
            days[i] = new CheckBox(context);
            days[i].setText(names[Calendar.SUNDAY + i]);
            content.addView(days[i]);
        }

        MaterialButton save = button("Set alarm");
        save.setOnClickListener(v -> {
            time.clearFocus();
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, time.getHour())
                    .putExtra(AlarmClock.EXTRA_MINUTES, time.getMinute())
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label.getText().toString().trim())
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            ArrayList<Integer> selectedDays = new ArrayList<>();
            for (int i = 0; i < days.length; i++) {
                if (days[i].isChecked()) selectedDays.add(Calendar.SUNDAY + i);
            }
            if (!selectedDays.isEmpty()) {
                intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, selectedDays);
            }
            openClock(intent);
        });
        content.addView(save);
        MaterialButton manage = button("Manage alarms in Clock");
        manage.setOnClickListener(v -> openClock(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        content.addView(manage);
        show("Alarm", content);
    }

    private void openClock(Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dismiss();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, "No compatible Clock app is available", Toast.LENGTH_SHORT).show();
        }
    }

    private void show(String title, LinearLayout content) {
        dismiss();
        int padding = dp(12);
        content.setPadding(padding, 0, padding, padding);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title).setView(scroll).setNegativeButton(android.R.string.cancel, null).create();
        dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE);
        dialog.show();
    }

    private void keys(LinearLayout panel, java.util.function.Consumer<String> action, String[][] rows) {
        for (String[] values : rows) {
            LinearLayout row = new LinearLayout(context);
            for (String value : values) {
                MaterialButton key = button("sqrt(".equals(value) ? "√" : value);
                key.setMinWidth(0);
                key.setMinimumWidth(0);
                key.setPadding(0, 0, 0, 0);
                key.setOnClickListener(v -> action.accept(value));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
                params.setMargins(dp(2), 0, dp(2), 0);
                row.addView(key, params);
            }
            panel.addView(row);
        }
    }

    private MaterialButton button(String title) {
        MaterialButton button = new MaterialButton(context);
        button.setText(title);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
