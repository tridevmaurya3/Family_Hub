package com.tridev.familyhub.feature.grocery;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Searchable, read-only smart category chooser for the Grocery Add/Edit dialog.
 *
 * The selected value is written back to the existing category input. No Room
 * schema, Grocery entity, Firebase path, recurrence or save/update contract is
 * changed. Popular categories are calculated from existing local Grocery rows;
 * recent choices are stored only as local presentation preferences.
 */
public final class GrocerySmartCategoryPicker {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "grocery_smart_category_picker";
    private static final int MAX_RECENT = 5;
    private static final int MAX_POPULAR = 5;

    private GrocerySmartCategoryPicker() { }

    public static void attach(@NonNull Context context,
                              @NonNull MaterialAutoCompleteTextView input) {
        Runnable open = () -> show(context,
                input.getText() == null ? "" : input.getText().toString(),
                value -> input.setText(value, false));
        input.setOnClickListener(v -> open.run());
        input.setOnLongClickListener(v -> {
            open.run();
            return true;
        });
        TextInputLayout layout = findTextInputLayout(input);
        if (layout != null) layout.setEndIconOnClickListener(v -> open.run());
        input.setContentDescription("Choose Grocery category with search, recent and popular categories");
    }

    public interface CategorySelectionHandler {
        void onSelected(@NonNull String value);
    }

    /**
     * Reuses the same searchable chooser for the system-overlay Grocery Spinner.
     * The caller keeps ownership of the Spinner adapter and save contract.
     */
    public static void attach(@NonNull Context context,
                              @NonNull Spinner input,
                              @NonNull CategorySelectionHandler handler) {
        Runnable open = () -> {
            Object selected = input.getSelectedItem();
            String current = selected == null ? "" : selected.toString();
            if (GroceryOptionCatalog.ADD_CATEGORY_LABEL.equals(current)) current = "";
            Context themedContext = new android.view.ContextThemeWrapper(
                    context, com.tridev.familyhub.R.style.Theme_FamilyHub);
            show(themedContext, current, handler::onSelected);
        };
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                view.setPressed(false);
                open.run();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                return true;
            }
            return true;
        });
        input.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_UP) return false;
            if (keyCode != android.view.KeyEvent.KEYCODE_ENTER
                    && keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER) return false;
            open.run();
            return true;
        });
        input.setContentDescription(
                "Choose Grocery category with search, recent and popular categories");
    }

    private interface SelectionListener {
        void onSelected(@NonNull String value);
    }

    private static void show(@NonNull Context context,
                             @NonNull String current,
                             @NonNull SelectionListener listener) {
        int gap = dp(context, 3);
        int side = dp(context, 8);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToPadding(false);
        root.setPadding(side, gap, side, dp(context, 10));
        root.setBackground(roundedBackground(
                Color.rgb(250, 252, 253), Color.rgb(195, 218, 211), dp(context, 18)));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("Choose category");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(22, 52, 44));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0, dp(context, 34), 1f));
        TextView hint = new TextView(context);
        hint.setText("Search & select");
        hint.setTextSize(9.5f);
        hint.setTextColor(Color.rgb(82, 103, 96));
        hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 34)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34)));

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(12f);
        search.setHint("Search category");
        search.setBackground(roundedBackground(
                Color.WHITE, Color.rgb(196, 215, 220), dp(context, 12)));
        search.setPadding(dp(context, 11), 0, dp(context, 11), 0);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34)));

        TextView recentTitle = sectionTitle(context, "Recent");
        root.addView(recentTitle, sectionTitleParams(context));
        HorizontalScrollView recentScroll = new HorizontalScrollView(context);
        recentScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout recentRow = chipRow(context);
        recentScroll.addView(recentRow);
        root.addView(recentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 28)));

        TextView popularTitle = sectionTitle(context, "Popular");
        root.addView(popularTitle, sectionTitleParams(context));
        HorizontalScrollView popularScroll = new HorizontalScrollView(context);
        popularScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout popularRow = chipRow(context);
        popularScroll.addView(popularRow);
        root.addView(popularScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 28)));

        LinearLayout listHeader = new LinearLayout(context);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView allTitle = sectionTitle(context, "All categories");
        listHeader.addView(allTitle, new LinearLayout.LayoutParams(
                0, dp(context, 26), 1f));
        MaterialButton add = smallAction(context, "+ Add category");
        listHeader.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 28)));
        root.addView(listHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 30)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int availableListHeight = context.getResources()
                .getDisplayMetrics().heightPixels - dp(context, 214);
        int maxListHeight = Math.max(dp(context, 96),
                Math.min(dp(context, 210), availableListHeight));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight);
        root.addView(scroll, scrollParams);

        TextView status = new TextView(context);
        status.setText("Loading saved categories…");
        status.setTextSize(9.5f);
        status.setTextColor(Color.rgb(94, 104, 114));
        status.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), 0);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton cancel = smallAction(context, "Cancel");
        cancel.setTextColor(Color.rgb(88, 58, 130));
        cancel.setStrokeColor(android.content.res.ColorStateList.valueOf(
                Color.rgb(210, 195, 229)));
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(247, 241, 253)));
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                dp(context, 84), dp(context, 32));
        cancelParams.bottomMargin = dp(context, 2);
        footer.addView(cancel, cancelParams);
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 40)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(root)
                .create();
        cancel.setOnClickListener(v -> dialog.dismiss());

        List<String> allCategories = new ArrayList<>();
        final boolean[] loaded = {false};

        SelectionListener choose = value -> {
            String clean = value == null ? "" : value.trim();
            if (clean.isEmpty()) return;
            rememberRecent(context, clean);
            listener.onSelected(clean);
            dialog.dismiss();
        };

        Runnable renderFiltered = () -> {
            String query = search.getText() == null
                    ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            renderCategoryRows(context, rows, allCategories, query, current, choose);
            if (loaded[0]) {
                int shown = countMatches(allCategories, query);
                int contentHeight = shown == 0
                        ? dp(context, 56)
                        : shown * dp(context, 34);
                scrollParams.height = Math.min(maxListHeight,
                        Math.max(dp(context, 34), contentHeight));
                scroll.setLayoutParams(scrollParams);
                status.setText(shown + " categor" + (shown == 1 ? "y" : "ies")
                        + " • type to filter");
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderFiltered.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        add.setOnClickListener(v -> showAddCategoryDialog(context, value -> {
            if (!GroceryOptionCatalog.addCustomCategory(context, value)) return;
            choose.onSelected(value.trim());
        }));

        dialog.setOnShowListener(ignored -> {
            search.requestFocus();
            EXECUTOR.execute(() -> {
                List<String> categories = new ArrayList<>();
                Collections.addAll(categories, GroceryOptionCatalog.categoryLabels(context));
                List<String> recent = recentCategories(context, categories);
                List<String> popular = popularCategories(context, categories);
                search.post(() -> {
                    if (!dialog.isShowing()) return;
                    allCategories.clear();
                    allCategories.addAll(categories);
                    loaded[0] = true;
                    renderChips(context, recentRow, recent, choose);
                    renderChips(context, popularRow, popular, choose);
                    recentTitle.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
                    recentScroll.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
                    popularTitle.setVisibility(popular.isEmpty() ? View.GONE : View.VISIBLE);
                    popularScroll.setVisibility(popular.isEmpty() ? View.GONE : View.VISIBLE);
                    renderFiltered.run();
                });
            });
        });
        configureOverlayWindow(context, dialog);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().getDecorView().setPadding(0, 0, 0, 0);
        }
    }

    private static void showAddCategoryDialog(@NonNull Context context,
                                              @NonNull SelectionListener listener) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint("Category name");
        int padding = dp(context, 18);
        input.setPadding(padding, dp(context, 8), padding, dp(context, 8));
        AlertDialog addDialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Add grocery category")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add", (dialog, which) -> {
                    String value = input.getText() == null
                            ? "" : input.getText().toString().trim();
                    if (!value.isEmpty()) listener.onSelected(value);
                })
                .create();
        configureOverlayWindow(context, addDialog);
        addDialog.show();
    }

    @NonNull
    private static List<String> recentCategories(@NonNull Context context,
                                                  @NonNull List<String> valid) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("recent_" + i, "");
            if (value == null || value.trim().isEmpty()) continue;
            String canonical = findIgnoreCase(valid, value);
            if (canonical != null && !containsIgnoreCase(result, canonical)) result.add(canonical);
        }
        return result;
    }

    private static void rememberRecent(@NonNull Context context, @NonNull String value) {
        List<String> current = recentCategories(context,
                java.util.Arrays.asList(GroceryOptionCatalog.categoryLabels(context)));
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i).equalsIgnoreCase(value)) current.remove(i);
        }
        current.add(0, value);
        while (current.size() > MAX_RECENT) current.remove(current.size() - 1);
        android.content.SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
        for (int i = 0; i < current.size(); i++) editor.putString("recent_" + i, current.get(i));
        editor.apply();
    }

    @NonNull
    private static List<String> popularCategories(@NonNull Context context,
                                                   @NonNull List<String> valid) {
        List<GroceryItem> items = FamilyHubDatabase.getInstance(context)
                .groceryItemDao().getAll();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (GroceryItem item : items) {
            if (item == null || item.category == null || item.category.trim().isEmpty()) continue;
            String canonical = findIgnoreCase(valid, item.category.trim());
            if (canonical == null) continue;
            String key = canonical.toLowerCase(Locale.ROOT);
            labels.put(key, canonical);
            int weight = Math.max(1, item.purchaseCount + 1);
            counts.put(key, counts.getOrDefault(key, 0) + weight);
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort((left, right) -> {
            int byCount = Integer.compare(counts.getOrDefault(right, 0),
                    counts.getOrDefault(left, 0));
            if (byCount != 0) return byCount;
            return labels.get(left).compareToIgnoreCase(labels.get(right));
        });
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            result.add(labels.get(key));
            if (result.size() >= MAX_POPULAR) break;
        }
        return result;
    }

    private static void renderCategoryRows(@NonNull Context context,
                                           @NonNull LinearLayout rows,
                                           @NonNull List<String> categories,
                                           @NonNull String query,
                                           @NonNull String current,
                                           @NonNull SelectionListener listener) {
        rows.removeAllViews();
        for (String category : categories) {
            if (!query.isEmpty() && !category.toLowerCase(Locale.ROOT).contains(query)) continue;
            boolean selected = category.equalsIgnoreCase(current);
            MaterialButton row = new MaterialButton(context);
            row.setAllCaps(false);
            row.setText((selected ? "✓  " : "") + category);
            row.setTextSize(11.5f);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(context, 32));
            row.setMinimumHeight(dp(context, 32));
            row.setInsetTop(0);
            row.setInsetBottom(0);
            row.setCornerRadius(dp(context, 10));
            row.setStrokeWidth(dp(context, 1));
            row.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    selected ? Color.rgb(126, 190, 165) : Color.rgb(212, 222, 226)));
            row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    selected ? Color.rgb(229, 247, 239) : Color.rgb(241, 248, 252)));
            row.setTextColor(selected ? Color.rgb(15, 108, 89) : Color.rgb(31, 42, 49));
            row.setOnClickListener(v -> listener.onSelected(category));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 32));
            params.bottomMargin = dp(context, 2);
            rows.addView(row, params);
        }
        if (rows.getChildCount() == 0) {
            TextView none = new TextView(context);
            none.setText("No matching category");
            none.setGravity(Gravity.CENTER);
            none.setTextSize(12f);
            none.setTextColor(Color.rgb(94, 104, 114));
            rows.addView(none, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 56)));
        }
    }

    private static void renderChips(@NonNull Context context,
                                    @NonNull LinearLayout row,
                                    @NonNull List<String> values,
                                    @NonNull SelectionListener listener) {
        row.removeAllViews();
        for (String value : values) {
            MaterialButton chip = smallAction(context, value);
            chip.setOnClickListener(v -> listener.onSelected(value));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 26));
            params.setMarginEnd(dp(context, 3));
            row.addView(chip, params);
        }
    }

    @NonNull
    private static MaterialButton smallAction(@NonNull Context context, @NonNull String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(10f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 6), 0, dp(context, 6), 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(context, 11));
        button.setStrokeWidth(dp(context, 1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(187, 208, 201)));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(241, 249, 246)));
        button.setTextColor(Color.rgb(15, 108, 89));
        return button;
    }

    @NonNull
    private static TextView sectionTitle(@NonNull Context context, @NonNull String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextSize(10.5f);
        title.setTextColor(Color.rgb(84, 93, 105));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return title;
    }

    @NonNull
    private static LinearLayout.LayoutParams sectionTitleParams(@NonNull Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 17));
        params.topMargin = dp(context, 1);
        return params;
    }

    @NonNull
    private static LinearLayout chipRow(@NonNull Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static int countMatches(@NonNull List<String> categories, @NonNull String query) {
        if (query.isEmpty()) return categories.size();
        int count = 0;
        for (String category : categories) {
            if (category.toLowerCase(Locale.ROOT).contains(query)) count++;
        }
        return count;
    }

    @Nullable
    private static String findIgnoreCase(@NonNull List<String> values, @NonNull String wanted) {
        for (String value : values) {
            if (value.equalsIgnoreCase(wanted)) return value;
        }
        return null;
    }

    private static boolean containsIgnoreCase(@NonNull List<String> values, @NonNull String wanted) {
        return findIgnoreCase(values, wanted) != null;
    }

    @Nullable
    private static TextInputLayout findTextInputLayout(@NonNull View start) {
        View current = start;
        while (current != null) {
            if (current instanceof TextInputLayout) return (TextInputLayout) current;
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return null;
    }

    private static void configureOverlayWindow(@NonNull Context context,
                                               @NonNull AlertDialog dialog) {
        if (!isServiceContext(context) || dialog.getWindow() == null) return;
        dialog.getWindow().setType(
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }

    private static boolean isServiceContext(@NonNull Context context) {
        Context current = context;
        while (true) {
            if (current instanceof android.app.Service) return true;
            if (!(current instanceof android.content.ContextWrapper)) return false;
            Context base = ((android.content.ContextWrapper) current).getBaseContext();
            if (base == null || base == current) return false;
            current = base;
        }
    }

    @NonNull
    private static android.graphics.drawable.GradientDrawable roundedBackground(
            int fill, int stroke, int radiusPx) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radiusPx);
        drawable.setStroke(1, stroke);
        return drawable;
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
