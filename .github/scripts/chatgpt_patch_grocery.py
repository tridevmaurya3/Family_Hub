from pathlib import Path

path = Path('app/src/main/java/com/tridev/familyhub/feature/grocery/overlay/GroceryOverlayService.java')
text = path.read_text(encoding='utf-8')

# Restore normal Daily/Monthly/2 Monthly/3 Monthly radio controls.
if 'import android.widget.RadioButton;' not in text:
    marker = 'import android.widget.PopupMenu;\n'
    if marker not in text:
        raise SystemExit('PopupMenu import marker not found')
    text = text.replace(marker, marker + 'import android.widget.RadioButton;\nimport android.widget.RadioGroup;\n', 1)

header_marker = '        header.addView(titleStack, new LinearLayout.LayoutParams(0, dp(48), 1f));\n\n'
header_block = '''        header.addView(titleStack, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button shoppingModeDropdown = compactAction("Shopping Mode  ▾",
                Color.rgb(15, 108, 89), Color.argb(230, 226, 244, 238));
        shoppingModeDropdown.setTextSize(8.5f);
        shoppingModeDropdown.setSingleLine(true);
        shoppingModeDropdown.setPadding(dp(4), 0, dp(4), 0);
        shoppingModeDropdown.setElevation(dp(2));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                shoppingModeDropdown, 7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        shoppingModeDropdown.setContentDescription("Floating Shopping Mode menu");
        LinearLayout.LayoutParams shoppingHeaderParams = new LinearLayout.LayoutParams(dp(76), dp(32));
        shoppingHeaderParams.setMarginStart(dp(4));
        header.addView(shoppingModeDropdown, shoppingHeaderParams);

'''
if 'Button shoppingModeDropdown = compactAction' not in text:
    if header_marker not in text:
        raise SystemExit('Header marker not found')
    text = text.replace(header_marker, header_block, 1)

start = text.index('        final String[] selectedListType = {visibleListType};')
end = text.index('        LinearLayout opacityPanel = new LinearLayout(this);', start)
controls = '''        final String[] selectedListType = {visibleListType};
        RadioGroup listTypeGroup = new RadioGroup(this);
        listTypeGroup.setOrientation(RadioGroup.HORIZONTAL);
        listTypeGroup.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton daily = new RadioButton(this);
        daily.setId(View.generateViewId());
        daily.setText("Daily");
        daily.setTextSize(8.5f);
        daily.setSingleLine(true);
        daily.setMinWidth(0);
        daily.setMinimumWidth(0);
        daily.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(daily, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        daily.setChecked(GroceryItem.LIST_DAILY.equals(visibleListType));

        RadioButton monthly = new RadioButton(this);
        monthly.setId(View.generateViewId());
        monthly.setText("Monthly");
        monthly.setTextSize(8.5f);
        monthly.setSingleLine(true);
        monthly.setMinWidth(0);
        monthly.setMinimumWidth(0);
        monthly.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(monthly, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        monthly.setChecked(GroceryItem.LIST_MONTHLY.equals(visibleListType));

        RadioButton twoMonth = new RadioButton(this);
        twoMonth.setId(View.generateViewId());
        twoMonth.setText("2 Monthly");
        twoMonth.setTextSize(8.5f);
        twoMonth.setSingleLine(true);
        twoMonth.setMinWidth(0);
        twoMonth.setMinimumWidth(0);
        twoMonth.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(twoMonth, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        twoMonth.setChecked(GroceryItem.LIST_TWO_MONTH.equals(visibleListType));

        RadioButton threeMonth = new RadioButton(this);
        threeMonth.setId(View.generateViewId());
        threeMonth.setText("3 Monthly");
        threeMonth.setTextSize(8.5f);
        threeMonth.setSingleLine(true);
        threeMonth.setMinWidth(0);
        threeMonth.setMinimumWidth(0);
        threeMonth.setPadding(0, 0, 0, 0);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(threeMonth, 6, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        threeMonth.setChecked(GroceryItem.LIST_THREE_MONTH.equals(visibleListType));

        listTypeGroup.addView(daily, new RadioGroup.LayoutParams(0, dp(34), 0.72f));
        listTypeGroup.addView(monthly, new RadioGroup.LayoutParams(0, dp(34), 0.92f));
        listTypeGroup.addView(twoMonth, new RadioGroup.LayoutParams(0, dp(34), 1.18f));
        listTypeGroup.addView(threeMonth, new RadioGroup.LayoutParams(0, dp(34), 1.18f));
        listTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == -1) return;
            if (checkedId == threeMonth.getId()) selectedListType[0] = GroceryItem.LIST_THREE_MONTH;
            else if (checkedId == twoMonth.getId()) selectedListType[0] = GroceryItem.LIST_TWO_MONTH;
            else if (checkedId == monthly.getId()) selectedListType[0] = GroceryItem.LIST_MONTHLY;
            else selectedListType[0] = GroceryItem.LIST_DAILY;
            visibleListType = selectedListType[0];
            refreshPanel();
        });

        screenOn.setOnClickListener(v -> {
            FamilyHubAppLockManager.noteTrustedOverlayInteraction();
            if (!overlayShoppingMode) return;
            overlayShoppingScreenOn = !overlayShoppingScreenOn;
            applyOverlayScreenOn(overlayShoppingScreenOn);
            updateOverlayShoppingModeUi(shoppingModeDropdown, screenOn, shoppingSubtitle);
        });

        shoppingModeDropdown.setOnClickListener(v -> showOverlayShoppingMenu(
                shoppingModeDropdown, screenOn, shoppingSubtitle));

        Button opacityToggle = compactAction("◐",
                Color.rgb(15, 108, 189), Color.argb(220, 232, 243, 252));
        opacityToggle.setTextSize(13f);
        opacityToggle.setSingleLine(true);
        opacityToggle.setPadding(0, 0, 0, 0);
        opacityToggle.setElevation(dp(1));
        opacityToggle.setContentDescription(getString(R.string.grocery_overlay_opacity));

        LinearLayout listTypeControls = new LinearLayout(this);
        listTypeControls.setGravity(Gravity.CENTER_VERTICAL);
        listTypeControls.addView(listTypeGroup, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams opacityRowParams = new LinearLayout.LayoutParams(dp(38), dp(32));
        opacityRowParams.setMarginStart(dp(6));
        listTypeControls.addView(opacityToggle, opacityRowParams);
        root.addView(listTypeControls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

'''
text = text[:start] + controls + text[end:]

start = text.index('    private void showOverlayShoppingMenu(')
end = text.index('    private void showCategoryFilterPopup(', start)
shopping_methods = '''    private void showOverlayShoppingMenu(Button anchor,
                                         Button screenOn, TextView shoppingSubtitle) {
        FamilyHubAppLockManager.noteTrustedOverlayInteraction();

        LinearLayout popupRoot = new LinearLayout(this);
        popupRoot.setOrientation(LinearLayout.VERTICAL);
        popupRoot.setPadding(dp(6), dp(6), dp(6), dp(6));
        popupRoot.setBackground(premiumDropdownBackground());
        popupRoot.setElevation(dp(12));

        android.widget.PopupWindow popup = new android.widget.PopupWindow(this);
        popup.setContentView(popupRoot);
        popup.setWidth(Math.min(dp(230), getResources().getDisplayMetrics().widthPixels - dp(32)));
        popup.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOverlapAnchor(false);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dp(12), dp(4), dp(8), dp(4));
        modeRow.setBackground(rounded(
                overlayShoppingMode ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                overlayShoppingMode ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));

        TextView modeLabel = text("Shopping Mode", 12, true);
        modeLabel.setTextColor(Color.rgb(15, 108, 89));
        modeLabel.setSingleLine(true);
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, dp(32), 1f));

        androidx.appcompat.widget.SwitchCompat modeSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        modeSwitch.setChecked(overlayShoppingMode);
        modeSwitch.setShowText(false);
        modeSwitch.setMinWidth(dp(48));
        modeSwitch.setMinimumWidth(dp(48));
        modeSwitch.setPadding(dp(2), 0, dp(2), 0);
        int[][] switchStates = new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{-android.R.attr.state_checked}};
        modeSwitch.setThumbTintList(new android.content.res.ColorStateList(
                switchStates, new int[]{Color.WHITE, Color.WHITE}));
        modeSwitch.setTrackTintList(new android.content.res.ColorStateList(
                switchStates, new int[]{Color.rgb(15, 153, 103), Color.rgb(188, 198, 202)}));
        modeRow.addView(modeSwitch, new LinearLayout.LayoutParams(dp(52), dp(36)));
        popupRoot.addView(modeRow, shoppingMenuRowParams(false));

        LinearLayout selectionContainer = new LinearLayout(this);
        selectionContainer.setOrientation(LinearLayout.VERTICAL);
        selectionContainer.setVisibility(overlayShoppingMode ? View.VISIBLE : View.GONE);
        popupRoot.addView(selectionContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, SHOPPING_ALL, "All", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_DAILY, "Daily", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_MONTHLY, "Monthly", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_TWO_MONTH, "2 Monthly", false);
        addShoppingSelectionRow(selectionContainer, popup, anchor, screenOn, shoppingSubtitle, GroceryItem.LIST_THREE_MONTH, "3 Monthly", true);

        modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            overlayShoppingMode = checked;
            if (!checked) {
                overlayShoppingScreenOn = false;
                overlayShoppingSelection = SHOPPING_ALL;
                applyOverlayScreenOn(false);
            }
            selectionContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            modeRow.setBackground(rounded(
                    checked ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                    checked ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));
            updateOverlayShoppingModeUi(anchor, screenOn, shoppingSubtitle);
            refreshPanel();
        });
        modeRow.setOnClickListener(v -> modeSwitch.toggle());

        int xOffset = -(popup.getWidth() - Math.max(anchor.getWidth(), dp(120)));
        popup.showAsDropDown(anchor, xOffset, dp(5));
    }

    private void addShoppingSelectionRow(LinearLayout popupRoot,
                                         android.widget.PopupWindow popup,
                                         Button anchor,
                                         Button screenOn,
                                         TextView shoppingSubtitle,
                                         String value,
                                         String label,
                                         boolean last) {
        boolean selected = value.equals(overlayShoppingSelection);
        LinearLayout row = premiumShoppingMenuRow(label, selected, false);
        popupRoot.addView(row, shoppingMenuRowParams(last));
        row.setOnClickListener(v -> {
            overlayShoppingSelection = value;
            updateOverlayShoppingModeUi(anchor, screenOn, shoppingSubtitle);
            refreshPanel();
            popup.dismiss();
        });
    }

    private LinearLayout premiumShoppingMenuRow(String label, boolean selected, boolean modeRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(5), dp(8), dp(5));
        row.setBackground(rounded(
                selected ? Color.argb(242, 229, 247, 239) : Color.argb(248, 255, 255, 255),
                selected ? Color.argb(210, 126, 190, 165) : Color.argb(125, 212, 222, 226), 10));
        TextView labelView = text(label, modeRow ? 12 : 11, modeRow || selected);
        labelView.setTextColor(selected ? Color.rgb(15, 108, 89) : Color.rgb(31, 42, 49));
        labelView.setSingleLine(true);
        row.addView(labelView, new LinearLayout.LayoutParams(0, dp(30), 1f));
        if (selected) {
            TextView check = text("✓", 13, true);
            check.setTextColor(Color.rgb(15, 108, 89));
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(30)));
        }
        return row;
    }

    private LinearLayout.LayoutParams shoppingMenuRowParams(boolean last) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        params.bottomMargin = last ? 0 : dp(4);
        return params;
    }

'''
text = text[:start] + shopping_methods + text[end:]

start = text.index('    private void updateOverlayShoppingModeUi(')
end = text.index('    private String shoppingSelectionLabel(', start)
update_ui = '''    private void updateOverlayShoppingModeUi(Button shoppingDropdown, Button screenOn,
                                             TextView shoppingSubtitle) {
        if (shoppingDropdown != null) {
            shoppingDropdown.setText("Shopping Mode  ▾");
            shoppingDropdown.setTextColor(overlayShoppingMode ? Color.WHITE : Color.rgb(15, 108, 89));
            shoppingDropdown.setBackground(roundedFill(overlayShoppingMode
                    ? Color.rgb(15, 108, 89) : Color.argb(230, 226, 244, 238), 16));
        }
        if (screenOn != null) {
            screenOn.setVisibility(overlayShoppingMode ? View.VISIBLE : View.GONE);
            screenOn.setText(overlayShoppingScreenOn ? "Screen On ✓" : "Screen On");
            screenOn.setTextColor(overlayShoppingScreenOn ? Color.WHITE : Color.rgb(15, 108, 189));
            screenOn.setBackground(roundedFill(overlayShoppingScreenOn
                    ? Color.rgb(15, 108, 189) : Color.argb(220, 232, 243, 252), 14));
        }
        if (shoppingSubtitle != null) {
            shoppingSubtitle.setText("(" + getString(R.string.grocery_list_type) + ")");
        }
    }

'''
text = text[:start] + update_ui + text[end:]
text = text.replace(
    'updateOverlayShoppingModeUi(shoppingListDropdown, screenOn, shoppingSubtitle);',
    'updateOverlayShoppingModeUi(shoppingModeDropdown, screenOn, shoppingSubtitle);')

if 'shoppingListDropdown' in text:
    raise SystemExit('Old shoppingListDropdown reference remains')
if 'RadioButton daily = new RadioButton(this);' not in text:
    raise SystemExit('Daily radio was not restored')
if 'SHOPPING_ALL, "All"' not in text:
    raise SystemExit('All option was not installed')

path.write_text(text, encoding='utf-8')
