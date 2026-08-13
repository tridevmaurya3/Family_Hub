package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

/**
 * Reusable compact overview surface used at the top of Family Hub modules.
 */
public final class ModuleOverviewView extends FrameLayout {

    private MaterialCardView surface;
    private MaterialCardView iconContainer;
    private ImageView iconView;
    private TextView titleView;
    private TextView detailView;

    public ModuleOverviewView(@NonNull Context context) {
        this(context, null);
    }

    public ModuleOverviewView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public ModuleOverviewView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize(context, attrs, defStyleAttr);
    }

    private void initialize(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        LayoutInflater.from(context).inflate(
                R.layout.view_module_overview,
                this,
                true
        );

        surface = findViewById(R.id.module_overview_surface);
        iconContainer = findViewById(R.id.module_overview_icon_container);
        iconView = findViewById(R.id.module_overview_icon);
        titleView = findViewById(R.id.module_overview_title);
        detailView = findViewById(R.id.module_overview_detail);

        int defaultAccent = ContextCompat.getColor(context, R.color.fh_primary);
        int defaultContainer = ContextCompat.getColor(
                context,
                R.color.fh_primary_container
        );

        if (attrs == null) {
            applyColors(defaultAccent, defaultContainer);
            enableDefaultBackNavigation();
            return;
        }

        TypedArray values = context.obtainStyledAttributes(
                attrs,
                R.styleable.ModuleOverviewView,
                defStyleAttr,
                0
        );
        try {
            titleView.setText(values.getText(
                    R.styleable.ModuleOverviewView_overviewTitle
            ));
            detailView.setText(values.getText(
                    R.styleable.ModuleOverviewView_overviewDetail
            ));

            int iconResource = values.getResourceId(
                    R.styleable.ModuleOverviewView_overviewIcon,
                    0
            );
            if (iconResource != 0) {
                iconView.setImageResource(iconResource);
            }

            int accent = values.getColor(
                    R.styleable.ModuleOverviewView_overviewAccentColor,
                    defaultAccent
            );
            int container = values.getColor(
                    R.styleable.ModuleOverviewView_overviewContainerColor,
                    defaultContainer
            );
            applyColors(accent, container);
        } finally {
            values.recycle();
        }
        enableDefaultBackNavigation();
    }

    /** Gives every module overview the same compact back action as Grocery. */
    private void enableDefaultBackNavigation() {
        setNavigationAction(R.drawable.ic_arrow_back, R.string.back, view -> {
            FragmentActivity activity = findActivity(getContext());
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).openHome();
            } else if (activity != null) {
                activity.getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Nullable
    private static FragmentActivity findActivity(@NonNull Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof FragmentActivity) return (FragmentActivity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return null;
    }

    private void applyColors(
            @ColorInt int accent,
            @ColorInt int container
    ) {
        surface.setCardBackgroundColor(container);
        surface.setStrokeColor(accent);
        iconContainer.setCardBackgroundColor(
                ColorStateList.valueOf(Color.WHITE)
        );
        iconView.setImageTintList(ColorStateList.valueOf(accent));
        titleView.setTextColor(accent);
    }

    /** Turns the leading icon tile into an accessible navigation action. */
    public void setNavigationAction(
            int iconResource,
            int contentDescriptionResource,
            @NonNull View.OnClickListener listener
    ) {
        iconView.setImageResource(iconResource);
        iconView.setContentDescription(getContext().getString(
                contentDescriptionResource));
        iconContainer.setClickable(true);
        iconContainer.setFocusable(true);
        iconContainer.setOnClickListener(listener);
    }
}
