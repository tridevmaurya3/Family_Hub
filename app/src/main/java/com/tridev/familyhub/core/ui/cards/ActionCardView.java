package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;

/** Reusable Office-style dashboard action card with live module values. */
public class ActionCardView extends FrameLayout {

    private MaterialCardView card;
    private MaterialCardView iconContainer;
    private ImageView icon;
    private ImageView arrow;
    private TextView title;
    private TextView primaryValue;
    private TextView secondaryValue;

    public ActionCardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ActionCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        init(context);
    }

    public ActionCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        LayoutInflater.from(context).inflate(
                R.layout.view_action_card,
                this,
                true
        );
        card = findViewById(R.id.action_card_surface);
        iconContainer = findViewById(R.id.action_card_icon_container);
        icon = findViewById(R.id.action_card_icon);
        arrow = findViewById(R.id.action_card_arrow);
        title = findViewById(R.id.action_card_title);
        primaryValue = findViewById(R.id.action_card_primary);
        secondaryValue = findViewById(R.id.action_card_secondary);
        setClickable(true);
        setFocusable(true);
        card.setClickable(false);
    }

    public void setModel(@NonNull ActionCardModel model) {
        int accent = ContextCompat.getColor(
                getContext(),
                model.getAccentColorResId()
        );
        int container = ContextCompat.getColor(
                getContext(),
                model.getContainerColorResId()
        );
        int surface = ContextCompat.getColor(
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
        int onSurface = ContextCompat.getColor(
                getContext(),
                R.color.fh_on_surface
        );
        int secondary = ContextCompat.getColor(
                getContext(),
                R.color.fh_text_secondary
        );

        title.setText(model.getTitle());
        primaryValue.setText(model.getPrimaryValue());
        secondaryValue.setText(model.getSecondaryValue());
        icon.setImageResource(model.getIconResId());

        card.setCardBackgroundColor(surface);
        card.setStrokeColor(outline);
        iconContainer.setCardBackgroundColor(container);
        iconContainer.setStrokeColor(outlineVariant);

        icon.setImageTintList(ColorStateList.valueOf(accent));
        arrow.setImageTintList(ColorStateList.valueOf(accent));
        title.setTextColor(onSurface);
        primaryValue.setTextColor(accent);
        secondaryValue.setTextColor(secondary);
    }
}
