package com.tridev.familyhub.core.ui.navigation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable five-item navigation independent of OEM Material measurements. */
public final class ResponsiveBottomNavigationView extends LinearLayout {

    public interface OnItemSelectedListener {
        boolean onItemSelected(@IdRes int destinationId);
    }

    private final Map<Integer, NavItem> items = new LinkedHashMap<>();
    @Nullable private OnItemSelectedListener listener;
    @IdRes private int selectedItemId = View.NO_ID;

    public ResponsiveBottomNavigationView(@NonNull Context context) {
        this(context, null);
    }

    public ResponsiveBottomNavigationView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public ResponsiveBottomNavigationView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setClipChildren(false);
        setClipToPadding(false);

        addItem(R.id.nav_home, R.drawable.ic_nav_home_color, "Home");
        addItem(R.id.nav_family, R.drawable.ic_nav_family_color, "Family");
        addItem(R.id.nav_reminders, R.drawable.ic_nav_reminder_color,
                "Reminders");
        addItem(R.id.nav_finance, R.drawable.ic_nav_finance_color, "Finance");
        addItem(R.id.nav_more, R.drawable.ic_nav_settings_color,
                getResources().getString(R.string.nav_menu_title));
    }

    public void setOnItemSelectedListener(
            @Nullable OnItemSelectedListener listener
    ) {
        this.listener = listener;
    }

    public void setSelectedItemId(@IdRes int destinationId) {
        if (!items.containsKey(destinationId)) {
            return;
        }
        selectedItemId = destinationId;
        for (Map.Entry<Integer, NavItem> entry : items.entrySet()) {
            boolean selected = entry.getKey() == destinationId;
            NavItem navItem = entry.getValue();
            navItem.container.setSelected(selected);
            navItem.icon.setSelected(selected);
            navItem.label.setSelected(selected);
            navItem.label.setVisibility(selected ? VISIBLE : GONE);
        }
    }

    @IdRes
    public int getSelectedItemId() {
        return selectedItemId;
    }

    private void addItem(
            @IdRes int destinationId,
            int iconResource,
            @NonNull String labelText
    ) {
        LinearLayout item = new LinearLayout(getContext());
        item.setId(destinationId);
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(labelText);
        item.setBackgroundResource(R.drawable.bg_responsive_nav_item);
        item.setPadding(dp(4), dp(4), dp(4), dp(3));
        LayoutParams itemParams = new LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1F
        );
        itemParams.setMargins(dp(3), dp(4), dp(3), dp(4));
        addView(item, itemParams);

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconResource);
        icon.setImageTintList(navColors());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        item.addView(icon, new LayoutParams(dp(23), dp(23)));

        TextView label = new TextView(getContext());
        label.setText(labelText);
        label.setTextColor(navColors());
        label.setTextSize(10F);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setMaxLines(1);
        label.setVisibility(GONE);
        LayoutParams labelParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(2);
        item.addView(label, labelParams);

        item.setOnClickListener(view -> {
            OnItemSelectedListener activeListener = listener;
            if (activeListener == null
                    || activeListener.onItemSelected(destinationId)) {
                setSelectedItemId(destinationId);
            }
        });
        items.put(destinationId, new NavItem(item, icon, label));
    }

    @NonNull
    private ColorStateList navColors() {
        return ContextCompat.getColorStateList(
                getContext(), R.color.bottom_navigation_item
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class NavItem {
        @NonNull final LinearLayout container;
        @NonNull final ImageView icon;
        @NonNull final TextView label;

        NavItem(
                @NonNull LinearLayout container,
                @NonNull ImageView icon,
                @NonNull TextView label
        ) {
            this.container = container;
            this.icon = icon;
            this.label = label;
        }
    }
}
