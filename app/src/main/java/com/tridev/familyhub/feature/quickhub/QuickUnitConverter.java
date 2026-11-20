package com.tridev.familyhub.feature.quickhub;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

final class QuickUnitConverter extends LinearLayout {
    private static final ExecutorService RATES = Executors.newSingleThreadExecutor();
    private final Function<String, MaterialButton> button;
    private final Map<String, Rate> rates = new HashMap<>();

    QuickUnitConverter(Context context, Function<String, MaterialButton> button) {
        super(context);
        this.button = button;
        setOrientation(VERTICAL);
        showCategories();
    }

    private void showCategories() {
        removeAllViews();
        String[] names = QuickUnitValues.categories();
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(getContext());
                addView(row);
            }
            String name = names[i];
            MaterialButton tile = button.apply(name);
            tile.setTextSize(12);
            tile.setMaxLines(2);
            LayoutParams params = new LayoutParams(0, dp(52), 1);
            params.setMargins(dp(2), dp(4), dp(2), 0);
            row.addView(tile, params);
            tile.setOnClickListener(v -> {
                removeAllViews();
                new Conversion(name);
            });
        }
    }

    private final class Conversion {
        private final String category;
        private final String[] units;
        private final EditText input;
        private final Spinner from;
        private final Spinner to;
        private final TextView output;
        private final TextView status;
        private final MaterialButton copy;
        private String pendingPair;
        private String failedPair;
        private String displayed;

        Conversion(String category) {
            this.category = category;
            units = QuickUnitValues.units(category);
            MaterialButton back = button.apply("‹  " + category);
            back.setTextSize(17);
            back.setOnClickListener(v -> showCategories());
            addView(back);

            input = new EditText(getContext());
            input.setSingleLine(true);
            input.setHint("Amount");
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
            if (!QuickUnitValues.BASES.equals(category)) {
                input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else {
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            input.setText("1");
            from = spinner("From unit");
            to = spinner("To unit");
            to.setSelection(1);
            addView(field(input, from));

            MaterialButton swap = button.apply("⇅");
            swap.setContentDescription("Swap units");
            addView(swap, new LayoutParams(-1, dp(48)));
            output = new TextView(getContext());
            output.setTextSize(23);
            output.setGravity(Gravity.CENTER_VERTICAL);
            output.setTypeface(null, Typeface.BOLD);
            output.setTextIsSelectable(true);
            output.setPadding(dp(8), 0, dp(4), 0);
            addView(field(output, to));

            status = new TextView(getContext());
            status.setPadding(dp(6), dp(4), dp(6), dp(4));
            addView(status);
            if (QuickUnitValues.CURRENCY.equals(category)) {
                MaterialButton refresh = button.apply("Refresh rate");
                refresh.setTextSize(14);
                refresh.setOnClickListener(v -> {
                    rates.remove(pair());
                    failedPair = null;
                    update();
                });
                addView(refresh, new LayoutParams(-1, dp(48)));
            }
            copy = button.apply("Copy result");
            copy.setTextSize(15);
            addView(copy, new LayoutParams(-1, dp(48)));
            copy.setOnClickListener(v -> {
                if (displayed == null) return;
                ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(category, displayed));
            });

            swap.setOnClickListener(v -> {
                int previousFrom = from.getSelectedItemPosition();
                String previousResult = displayed;
                from.setSelection(to.getSelectedItemPosition());
                to.setSelection(previousFrom);
                if (previousResult != null) input.setText(previousResult);
                update();
            });
            AdapterView.OnItemSelectedListener selection = new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { update(); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            };
            from.setOnItemSelectedListener(selection);
            to.setOnItemSelectedListener(selection);
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update(); }
                @Override public void afterTextChanged(Editable s) {}
            });
            update();
        }

        private Spinner spinner(String description) {
            Spinner view = new Spinner(getContext(), Spinner.MODE_DROPDOWN);
            view.setContentDescription(description);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_item, units);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            view.setAdapter(adapter);
            return view;
        }

        private String pair() {
            return from.getSelectedItem() + "/" + to.getSelectedItem();
        }

        private void update() {
            if (input.getParent() == null || output.getParent() == null
                    || output.getParent().getParent() != QuickUnitConverter.this) return;
            String source = (String) from.getSelectedItem();
            String target = (String) to.getSelectedItem();
            if (source == null || target == null) return;
            String value = input.getText().toString().trim();
            displayed = null;
            output.setText("—");
            status.setText("");
            copy.setEnabled(false);
            if (value.isEmpty()) return;
            try {
                if (QuickUnitValues.CURRENCY.equals(category) && !source.equals(target)) {
                    BigDecimal amount = new BigDecimal(value);
                    String key = pair();
                    Rate rate = rates.get(key);
                    if (rate == null) {
                        if (key.equals(failedPair)) {
                            status.setText("Rate unavailable. Tap Refresh rate to retry.");
                        } else {
                            status.setText("Fetching reference rate…");
                            requestRate(key, source, target);
                        }
                        return;
                    }
                    displayed = QuickUnitValues.format(amount.multiply(rate.value));
                    status.setText("Frankfurter reference rate · " + rate.date);
                } else {
                    displayed = QuickUnitValues.convert(category, value, source, target);
                }
                output.setText(displayed);
                copy.setEnabled(true);
            } catch (IllegalArgumentException | ArithmeticException e) {
                status.setText("Enter a valid value");
            }
        }

        private void requestRate(String key, String source, String target) {
            if (key.equals(pendingPair)) return;
            pendingPair = key;
            RATES.execute(() -> {
                Rate rate = fetchRate(source, target);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!key.equals(pendingPair)) return;
                    pendingPair = null;
                    if (rate == null) failedPair = key;
                    else rates.put(key, rate);
                    if (key.equals(pair())) update();
                });
            });
        }
    }

    private static Rate fetchRate(String source, String target) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.frankfurter.dev/v2/rate/" + source + "/" + target);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            try (Scanner body = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
                JSONObject item = new JSONObject(body.useDelimiter("\\A").next());
                if (!source.equals(item.getString("base")) || !target.equals(item.getString("quote"))) return null;
                BigDecimal rate = new BigDecimal(item.getString("rate"));
                return rate.signum() > 0 ? new Rate(rate, item.getString("date")) : null;
            }
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private LinearLayout field(View value, Spinner unit) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xfff8faff);
        border.setCornerRadius(dp(12));
        border.setStroke(dp(1), 0xffbed0dd);
        row.setBackground(border);
        if (value instanceof EditText) {
            value.setBackground(null);
            value.setPadding(dp(8), 0, dp(4), 0);
        }
        row.addView(value, new LayoutParams(0, dp(54), 1));
        row.addView(unit, new LayoutParams(dp(108), dp(54)));
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Rate {
        final BigDecimal value;
        final String date;
        Rate(BigDecimal value, String date) {
            this.value = value;
            this.date = date;
        }
    }
}
