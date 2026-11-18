package com.tridev.familyhub.feature.quickhub;

import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.AlarmClock;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;

final class QuickHubTools {
    private static final int INK = 0xff192644;
    private static final int BLUE = 0xff365bd9;
    private static final int PANEL = 0xfff7f8ff;
    private static final int ACCENT_SURFACE = 0xffe5ebff;

    private final Context context;
    private AlertDialog dialog;
    private TimePickerDialog alarmTimePicker;

    QuickHubTools(Context context) {
        this.context = context;
    }

    void dismiss() {
        if (alarmTimePicker != null) alarmTimePicker.dismiss();
        alarmTimePicker = null;
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    void calculator() {
        LinearLayout content = column();
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("Calculator");
        title.setTextSize(22);
        title.setTextColor(INK);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton close = calculatorButton("×", ACCENT_SURFACE, INK);
        close.setContentDescription("Close calculator");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        LinearLayout expressionCard = new LinearLayout(context);
        expressionCard.setGravity(Gravity.CENTER_VERTICAL);
        expressionCard.setBackground(rounded(INK, 18));
        expressionCard.setPadding(dp(14), dp(8), dp(8), dp(8));
        TextView display = new TextView(context);
        display.setTextSize(26);
        display.setTextColor(Color.WHITE);
        display.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        display.setTextIsSelectable(true);
        display.setText("0");
        expressionCard.addView(display, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton backspace = calculatorButton("⌫", 0xff344564, Color.WHITE);
        backspace.setContentDescription("Delete last character");
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        deleteParams.setMarginStart(dp(8));
        expressionCard.addView(backspace, deleteParams);
        addCalculatorSection(content, expressionCard, 8);

        StringBuilder expression = new StringBuilder();
        boolean[] degrees = {true};
        boolean[] evaluated = {false};
        LinearLayout modes = new LinearLayout(context);
        MaterialButton basic = calculatorButton("Basic", BLUE, Color.WHITE);
        MaterialButton advanced = calculatorButton("Scientific", ACCENT_SURFACE, INK);
        basic.setTextSize(15);
        advanced.setTextSize(15);
        modes.addView(basic, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        advancedParams.setMarginStart(dp(6));
        modes.addView(advanced, advancedParams);
        addCalculatorSection(content, modes, 8);
        LinearLayout scientific = column();
        scientific.setVisibility(View.GONE);
        basic.setOnClickListener(v -> {
            scientific.setVisibility(View.GONE);
            setCalculatorMode(basic, advanced);
        });
        advanced.setOnClickListener(v -> {
            scientific.setVisibility(View.VISIBLE);
            setCalculatorMode(advanced, basic);
        });
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
        backspace.setOnClickListener(v -> key.accept("⌫"));
        MaterialButton angle = calculatorButton("DEG", ACCENT_SURFACE, INK);
        angle.setOnClickListener(v -> {
            degrees[0] = !degrees[0];
            angle.setText(degrees[0] ? "DEG" : "RAD");
        });
        addCalculatorSection(scientific, angle, 6);
        keys(scientific, key, new String[][]{
                {"sin(", "cos(", "tan("}, {"sqrt(", "log(", "ln("}, {"π", "e", "^"}
        });
        keys(content, key, new String[][]{
                {"AC", "%", "(", "÷"}, {"7", "8", "9", "×"}, {"4", "5", "6", "-"},
                {"1", "2", "3", "+"}, {")", "0", ".", "="}
        });
        content.setBackgroundColor(PANEL);
        show(null, content);
    }

    void alarm() {
        LinearLayout content = column();
        content.setBackgroundColor(PANEL);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("Alarm");
        title.setTextSize(22);
        title.setTextColor(INK);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton close = calculatorButton("×", ACCENT_SURFACE, INK);
        close.setContentDescription("Close alarm");
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        Calendar selection = Calendar.getInstance();
        MaterialButton time = calculatorButton(alarmTimeLabel(selection), INK, Color.WHITE);
        time.setTextSize(26);
        time.setOnClickListener(v -> {
            alarmTimePicker = new TimePickerDialog(context, (picker, hour, minute) -> {
                selection.set(Calendar.HOUR_OF_DAY, hour);
                selection.set(Calendar.MINUTE, minute);
                time.setText(alarmTimeLabel(selection));
            }, selection.get(Calendar.HOUR_OF_DAY), selection.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context));
            alarmTimePicker.setOnDismissListener(dialog -> alarmTimePicker = null);
            alarmTimePicker.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            alarmTimePicker.show();
        });
        LinearLayout timeCard = column();
        timeCard.addView(time, new LinearLayout.LayoutParams(-1, dp(66)));
        addCalculatorSection(content, timeCard, 6);

        TextInputLayout labelField = new TextInputLayout(context, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        labelField.setHint("Alarm label (optional)");
        TextInputEditText label = new TextInputEditText(labelField.getContext());
        label.setSingleLine();
        label.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelField.addView(label);
        addCalculatorSection(content, labelField, 8);

        TextView repeat = new TextView(context);
        repeat.setText("Repeat days");
        repeat.setTextColor(INK);
        repeat.setTypeface(null, Typeface.BOLD);
        addCalculatorSection(content, repeat, 10);
        String[] shortNames = new DateFormatSymbols().getShortWeekdays();
        String[] fullNames = new DateFormatSymbols().getWeekdays();
        MaterialButton[] days = new MaterialButton[7];
        LinearLayout row = null;
        for (int i = 0; i < days.length; i++) {
            if (i == 0 || i == 4) {
                row = new LinearLayout(context);
                addCalculatorSection(content, row, 4);
            }
            int day = Calendar.SUNDAY + i;
            MaterialButton chip = calculatorButton(shortNames[day], ACCENT_SURFACE, INK);
            chip.setTextSize(13);
            chip.setContentDescription(fullNames[day]);
            chip.setCheckable(true);
            chip.addOnCheckedChangeListener((button, checked) -> {
                button.setBackgroundTintList(ColorStateList.valueOf(checked ? BLUE : ACCENT_SURFACE));
                button.setTextColor(checked ? Color.WHITE : INK);
            });
            days[i] = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMarginEnd(dp(4));
            row.addView(chip, params);
        }

        MaterialButton save = calculatorButton("Set alarm", BLUE, Color.WHITE);
        save.setTextSize(16);
        save.setOnClickListener(v -> {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, selection.get(Calendar.HOUR_OF_DAY))
                    .putExtra(AlarmClock.EXTRA_MINUTES, selection.get(Calendar.MINUTE))
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
        addCalculatorSection(content, save, 10);
        MaterialButton manage = calculatorButton("Manage alarms in Clock", ACCENT_SURFACE, INK);
        manage.setTextSize(15);
        manage.setOnClickListener(v -> openClock(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        addCalculatorSection(content, manage, 4);
        show(null, content);
    }

    private String alarmTimeLabel(Calendar selection) {
        return DateFormat.getTimeFormat(context).format(selection.getTime()) + "  ›";
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
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context).setView(scroll);
        if (title != null) builder.setTitle(title).setNegativeButton(android.R.string.cancel, null);
        else content.setPadding(padding, padding, padding, padding);
        dialog = builder.create();
        dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE);
        dialog.show();
    }

    private void keys(LinearLayout panel, java.util.function.Consumer<String> action, String[][] rows) {
        for (String[] values : rows) {
            LinearLayout row = new LinearLayout(context);
            for (String value : values) {
                int fill = value.matches("[0-9.]") ? Color.WHITE : ACCENT_SURFACE;
                int foreground = INK;
                if ("AC".equals(value)) {
                    fill = 0xffffe7e5;
                    foreground = 0xff9e382f;
                } else if ("=".equals(value)) {
                    fill = BLUE;
                    foreground = Color.WHITE;
                }
                String label = "sqrt(".equals(value) ? "√" : "-".equals(value) ? "−" : value;
                MaterialButton key = calculatorButton(label, fill, foreground);
                key.setOnClickListener(v -> action.accept(value));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
                params.setMargins(dp(3), 0, dp(3), 0);
                row.addView(key, params);
            }
            addCalculatorSection(panel, row, 6);
        }
    }

    private void addCalculatorSection(LinearLayout parent, View section, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(section, params);
    }

    private void setCalculatorMode(MaterialButton selected, MaterialButton other) {
        selected.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        selected.setTextColor(Color.WHITE);
        other.setBackgroundTintList(ColorStateList.valueOf(ACCENT_SURFACE));
        other.setTextColor(INK);
    }

    private MaterialButton calculatorButton(String title, int fill, int textColor) {
        MaterialButton key = button(title);
        key.setTextSize(20);
        key.setTypeface(null, Typeface.BOLD);
        key.setBackgroundTintList(ColorStateList.valueOf(fill));
        key.setTextColor(textColor);
        key.setCornerRadius(dp(14));
        key.setMinWidth(0);
        key.setMinimumWidth(0);
        key.setInsetTop(0);
        key.setInsetBottom(0);
        key.setPadding(0, 0, 0, 0);
        if (fill == Color.WHITE) {
            key.setStrokeWidth(dp(1));
            key.setStrokeColor(ColorStateList.valueOf(0xffdde4f6));
        }
        return key;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
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
