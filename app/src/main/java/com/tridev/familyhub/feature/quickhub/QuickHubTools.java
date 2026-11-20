package com.tridev.familyhub.feature.quickhub;

import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.AlarmClock;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
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
import com.tridev.familyhub.core.reminders.ReminderAlarmAccessActivity;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.repository.ReminderRepository;

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
        LinearLayout calculation = column();
        calculation.addView(display);
        TextView result = new TextView(context);
        result.setTextSize(17);
        result.setTextColor(0xffcddcff);
        result.setGravity(Gravity.END);
        result.setVisibility(View.GONE);
        calculation.addView(result);
        expressionCard.addView(calculation, new LinearLayout.LayoutParams(0, -2, 1));
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

        Runnable updatePreview = () -> {
            String input = expression.toString();
            while (input.length() > 1 && "+-×÷^".indexOf(input.charAt(input.length() - 1)) >= 0) {
                input = input.substring(0, input.length() - 1);
            }
            try {
                String total = calculatorResult(input, degrees[0]);
                result.setText(total);
                result.setVisibility(View.VISIBLE);
            } catch (IllegalArgumentException incompleteOrInvalid) {
                result.setVisibility(View.GONE);
            }
        };

        java.util.function.Consumer<String> key = value -> {
            if ("=".equals(value)) {
                try {
                    result.setText(calculatorResult(expression.toString(), degrees[0]));
                    result.setVisibility(View.VISIBLE);
                    evaluated[0] = true;
                } catch (IllegalArgumentException e) {
                    result.setText("Invalid calculation");
                    result.setVisibility(View.VISIBLE);
                }
                return;
            }
            if ("AC".equals(value)) {
                expression.setLength(0);
            } else if ("⌫".equals(value)) {
                if (expression.length() > 0) expression.deleteCharAt(expression.length() - 1);
            } else {
                if (evaluated[0]) {
                    String previousResult = result.getText().toString();
                    expression.setLength(0);
                    if ("+-×÷^%".contains(value)) expression.append(previousResult);
                }
                if (expression.length() + value.length() <= 256) expression.append(value);
            }
            evaluated[0] = false;
            display.setText(expression.length() == 0 ? "0" : expression.toString());
            updatePreview.run();
        };
        backspace.setOnClickListener(v -> key.accept("⌫"));
        MaterialButton angle = calculatorButton("DEG", ACCENT_SURFACE, INK);
        angle.setOnClickListener(v -> {
            degrees[0] = !degrees[0];
            angle.setText(degrees[0] ? "DEG" : "RAD");
            evaluated[0] = false;
            updatePreview.run();
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
        show(header, content);
    }

    private static String calculatorResult(String expression, boolean degrees) {
        double result = CalculatorExpression.evaluate(expression, degrees);
        return BigDecimal.valueOf(result).round(MathContext.DECIMAL64)
                .stripTrailingZeros().toPlainString();
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

        Calendar selection = Calendar.getInstance();
        boolean[] repeating = {false};
        MaterialButton[] days = new MaterialButton[7];
        TextView summary = new TextView(context);
        summary.setTextColor(INK);
        Runnable updateSummary = () -> {
            ArrayList<Integer> selected = alarmDays(days);
            if (repeating[0]) {
                String[] dayNames = new DateFormatSymbols().getShortWeekdays();
                ArrayList<String> names = new ArrayList<>();
                for (int day : selected) names.add(dayNames[day]);
                summary.setText(names.isEmpty() ? "Choose repeat days" : "Every " + String.join(", ", names));
            } else {
                int day = selected.isEmpty() ? 0 : selected.get(0);
                Calendar next = nextAlarmDate(Calendar.getInstance(), selection, day);
                summary.setText("One time · " + new java.text.SimpleDateFormat(
                        "EEE, d MMM yyyy", java.util.Locale.getDefault()).format(next.getTime()));
            }
        };
        MaterialButton time = calculatorButton(alarmTimeLabel(selection), INK, Color.WHITE);
        time.setTextSize(26);
        time.setOnClickListener(v -> {
            alarmTimePicker = new TimePickerDialog(context, (picker, hour, minute) -> {
                selection.set(Calendar.HOUR_OF_DAY, hour);
                selection.set(Calendar.MINUTE, minute);
                time.setText(alarmTimeLabel(selection));
                updateSummary.run();
            }, selection.get(Calendar.HOUR_OF_DAY), selection.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context));
            alarmTimePicker.setOnDismissListener(dialog -> alarmTimePicker = null);
            alarmTimePicker.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            alarmTimePicker.show();
        });
        LinearLayout timeCard = column();
        timeCard.addView(time, new LinearLayout.LayoutParams(-1, dp(66)));
        addCalculatorSection(content, timeCard, 6);

        LinearLayout modes = new LinearLayout(context);
        MaterialButton once = calculatorButton("Once", BLUE, Color.WHITE);
        MaterialButton repeatMode = calculatorButton("Repeat", ACCENT_SURFACE, INK);
        once.setTextSize(15);
        repeatMode.setTextSize(15);
        modes.addView(once, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams repeatParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        repeatParams.setMarginStart(dp(6));
        modes.addView(repeatMode, repeatParams);
        addCalculatorSection(content, modes, 8);
        addCalculatorSection(content, summary, 4);

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
        repeat.setText("Choose one day");
        repeat.setTextColor(INK);
        repeat.setTypeface(null, Typeface.BOLD);
        addCalculatorSection(content, repeat, 10);
        String[] shortNames = new DateFormatSymbols().getShortWeekdays();
        String[] fullNames = new DateFormatSymbols().getWeekdays();
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
                button.setBackgroundTintList(glassTint(checked ? BLUE : ACCENT_SURFACE));
                button.setTextColor(checked ? Color.WHITE : INK);
                if (checked && !repeating[0]) {
                    for (MaterialButton other : days) {
                        if (other != null && other != button) other.setChecked(false);
                    }
                }
                updateSummary.run();
            });
            days[i] = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMarginEnd(dp(4));
            row.addView(chip, params);
        }

        once.setOnClickListener(v -> {
            repeating[0] = false;
            setCalculatorMode(once, repeatMode);
            repeat.setText("Choose one day");
            boolean found = false;
            for (MaterialButton day : days) {
                if (!day.isChecked()) continue;
                if (found) day.setChecked(false);
                found = true;
            }
            updateSummary.run();
        });
        repeatMode.setOnClickListener(v -> {
            repeating[0] = true;
            setCalculatorMode(repeatMode, once);
            repeat.setText("Repeat days");
            updateSummary.run();
        });
        updateSummary.run();

        MaterialButton save = calculatorButton("Set alarm", BLUE, Color.WHITE);
        save.setTextSize(16);
        save.setOnClickListener(v -> {
            ArrayList<Integer> selectedDays = alarmDays(days);
            String alarmLabel = label.getText().toString().trim();
            if (!repeating[0]) {
                int day = selectedDays.isEmpty() ? 0 : selectedDays.get(0);
                Calendar next = nextAlarmDate(Calendar.getInstance(), selection, day);
                Reminder reminder = new Reminder();
                reminder.title = alarmLabel.isEmpty() ? "Alarm" : alarmLabel;
                reminder.reminderAt = next.getTimeInMillis();
                reminder.priority = "URGENT";
                save.setEnabled(false);
                new ReminderRepository(context).save(reminder, saved -> {
                    Toast.makeText(context, "One-time alarm set in Family Hub Reminders",
                            Toast.LENGTH_SHORT).show();
                    dismiss();
                    if (ReminderAlarmAccessActivity.needsPermission(context)) {
                        ReminderAlarmAccessActivity.request(context, false, true);
                    }
                });
                return;
            }
            if (selectedDays.isEmpty()) {
                Toast.makeText(context, "Choose at least one repeat day", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, selection.get(Calendar.HOUR_OF_DAY))
                    .putExtra(AlarmClock.EXTRA_MINUTES, selection.get(Calendar.MINUTE))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, alarmLabel)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, selectedDays);
            openClock(intent);
        });
        addCalculatorSection(content, save, 10);
        MaterialButton manage = calculatorButton("Manage alarms in Clock", ACCENT_SURFACE, INK);
        manage.setTextSize(15);
        manage.setOnClickListener(v -> openClock(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        addCalculatorSection(content, manage, 4);
        show(header, content);
    }

    private static ArrayList<Integer> alarmDays(MaterialButton[] days) {
        ArrayList<Integer> selected = new ArrayList<>();
        for (int i = 0; i < days.length; i++) {
            if (days[i] != null && days[i].isChecked()) selected.add(Calendar.SUNDAY + i);
        }
        return selected;
    }

    private static Calendar nextAlarmDate(Calendar now, Calendar time, int day) {
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY));
        next.set(Calendar.MINUTE, time.get(Calendar.MINUTE));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (day == 0) {
            if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);
        } else {
            int daysAhead = (day - now.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            if (daysAhead == 0 && !next.after(now)) daysAhead = 7;
            next.add(Calendar.DAY_OF_YEAR, daysAhead);
        }
        return next;
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

    private void show(LinearLayout header, LinearLayout content) {
        dismiss();
        LinearLayout panel = column();
        int padding = dp(12);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundColor(PANEL);
        panel.addView(header);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        dialog = new MaterialAlertDialogBuilder(context).setView(panel).create();
        Window window = dialog.getWindow();
        window.setType(Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE);
        window.setGravity(Gravity.CENTER);
        attachDragHandle(header, window);
        dialog.show();
    }

    private void attachDragHandle(View header, Window window) {
        header.setOnTouchListener(new View.OnTouchListener() {
            private final Rect frame = new Rect();
            private final int[] location = new int[2];
            private float touchX, touchY;
            private int startX, startY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                View decor = window.getDecorView();
                WindowManager.LayoutParams params = window.getAttributes();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        decor.getLocationOnScreen(location);
                        decor.getWindowVisibleDisplayFrame(frame);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + clampDrag(Math.round(event.getRawX() - touchX),
                                location[0], decor.getWidth(), frame.left, frame.right);
                        params.y = startY + clampDrag(Math.round(event.getRawY() - touchY),
                                location[1], decor.getHeight(), frame.top, frame.bottom);
                        window.setAttributes(params);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private static int clampDrag(int change, int position, int size, int start, int end) {
        int min = Math.min(0, start - position);
        int max = Math.max(0, end - position - size);
        return Math.max(min, Math.min(max, change));
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
        selected.setBackgroundTintList(glassTint(BLUE));
        selected.setTextColor(Color.WHITE);
        other.setBackgroundTintList(glassTint(ACCENT_SURFACE));
        other.setTextColor(INK);
    }

    private MaterialButton calculatorButton(String title, int fill, int textColor) {
        MaterialButton key = button(title);
        key.setTextSize(20);
        key.setTypeface(null, Typeface.BOLD);
        key.setBackgroundTintList(glassTint(fill));
        key.setTextColor(textColor);
        key.setCornerRadius(dp(14));
        key.setMinWidth(0);
        key.setMinimumWidth(0);
        key.setInsetTop(0);
        key.setInsetBottom(0);
        key.setPadding(0, 0, 0, 0);
        key.setStrokeWidth(dp(1));
        key.setStrokeColor(ColorStateList.valueOf(0x6081aa9f));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22ffffff, 0x00ffffff, 0x0980b6a5});
        sheen.setCornerRadius(dp(14));
        key.setForeground(sheen);
        return key;
    }

    private ColorStateList glassTint(int color) {
        int pressed = androidx.core.graphics.ColorUtils.blendARGB(color, INK, 0.12f);
        return new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{pressed, color});
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
