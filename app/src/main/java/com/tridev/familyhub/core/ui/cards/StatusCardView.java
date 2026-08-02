package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ui.SemanticValueStyler;

/**
 * Reusable compact Status Card used throughout Family Hub.
 */
public class StatusCardView extends FrameLayout {

    private MaterialCardView surface;
    private MaterialCardView iconContainer;
    private ImageView icon;
    private ImageView arrow;
    private TextView title;
    private TextView value;
    private TextView subtitle;

    public StatusCardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public StatusCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        init(context);
    }

    public StatusCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        LayoutInflater.from(context).inflate(
                R.layout.view_status_card,
                this,
                true
        );

        surface = findViewById(R.id.status_card_surface);
        iconContainer = findViewById(R.id.status_icon_container);
        icon = findViewById(R.id.imgStatusIcon);
        arrow = findViewById(R.id.status_card_arrow);
        title = findViewById(R.id.txtStatusTitle);
        value = findViewById(R.id.txtStatusValue);
        subtitle = findViewById(R.id.txtStatusSubtitle);
    }

    public void setModel(@NonNull StatusCardModel model) {
        title.setText(model.getTitle());
        value.setText(model.getValue());
        subtitle.setText(model.getSubtitle());

        if (model.getIconResId() != 0) {
            icon.setImageResource(model.getIconResId());
        }

        applyModulePalette(model.getIconResId());
    }

    private void applyModulePalette(int iconResId) {
        @ColorRes int accentRes = R.color.fh_primary;
        @ColorRes int containerRes = R.color.fh_primary_container;

        if (iconResId == R.drawable.ic_wallet) {
            accentRes = R.color.fh_module_finance;
            containerRes = R.color.fh_module_finance_container;
        } else if (iconResId == R.drawable.ic_health) {
            accentRes = R.color.fh_module_health;
            containerRes = R.color.fh_module_health_container;
        } else if (iconResId == R.drawable.ic_family) {
            accentRes = R.color.fh_module_family;
            containerRes = R.color.fh_module_family_container;
        } else if (iconResId == R.drawable.ic_document) {
            accentRes = R.color.fh_module_documents;
            containerRes = R.color.fh_module_documents_container;
        }

        int accent = ContextCompat.getColor(getContext(), accentRes);
        int container = ContextCompat.getColor(getContext(), containerRes);
        int surfaceColor = ContextCompat.getColor(
                getContext(),
                R.color.fh_surface
        );
        int outline = ContextCompat.getColor(
                getContext(),
                R.color.fh_outline
        );
        int outlineVariant = ContextCompat.getColor(
                getContext(),
                R.color.fh_outline_variant
        );

        surface.setCardBackgroundColor(surfaceColor);
        surface.setStrokeColor(outline);
        iconContainer.setCardBackgroundColor(container);
        iconContainer.setStrokeColor(outlineVariant);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        arrow.setImageTintList(ColorStateList.valueOf(accent));
        value.setTextColor(accent);
    }

    public void setValueColorBySign(double signedValue) {
        SemanticValueStyler.apply(value, signedValue);
    }
}
