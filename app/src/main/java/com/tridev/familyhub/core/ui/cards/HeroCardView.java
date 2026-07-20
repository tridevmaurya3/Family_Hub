package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.R;

/**
 * Reusable Hero Card component for Family Hub screens.
 */
public class HeroCardView extends FrameLayout {

    private ImageView heroIcon;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private Button heroActionButton;

    private OnActionClickListener actionClickListener;

    public HeroCardView(@NonNull Context context) {
        super(context);
        initialize(context);
    }

    public HeroCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        initialize(context);
    }

    public HeroCardView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(@NonNull Context context) {
        LayoutInflater.from(context).inflate(
                R.layout.view_hero_card,
                this,
                true
        );

        heroIcon = findViewById(R.id.imgHeroIcon);
        heroTitle = findViewById(R.id.txtHeroTitle);
        heroSubtitle = findViewById(R.id.txtHeroSubtitle);
        heroActionButton = findViewById(R.id.btnHeroAction);

        heroActionButton.setOnClickListener(view -> {
            if (actionClickListener != null) {
                actionClickListener.onActionClick();
            }
        });
    }

    /**
     * Binds complete Hero Card data.
     */
    public void setModel(@NonNull HeroCardModel model) {
        setTitle(model.getTitle());
        setSubtitle(model.getSubtitle());
        setIcon(model.getIconResId());
        setActionText(model.getActionText());
    }

    public void setTitle(@Nullable String title) {
        heroTitle.setText(
                isEmpty(title)
                        ? getContext().getString(R.string.app_name)
                        : title
        );
    }

    public void setSubtitle(@Nullable String subtitle) {
        if (isEmpty(subtitle)) {
            heroSubtitle.setVisibility(View.GONE);
        } else {
            heroSubtitle.setVisibility(View.VISIBLE);
            heroSubtitle.setText(subtitle);
        }
    }

    public void setIcon(int iconResId) {
        if (iconResId == 0) {
            heroIcon.setVisibility(View.GONE);
        } else {
            heroIcon.setVisibility(View.VISIBLE);
            heroIcon.setImageResource(iconResId);
        }
    }

    public void setActionText(@Nullable String actionText) {
        if (isEmpty(actionText)) {
            heroActionButton.setVisibility(View.GONE);
        } else {
            heroActionButton.setVisibility(View.VISIBLE);
            heroActionButton.setText(actionText);
        }
    }

    public void setOnActionClickListener(
            @Nullable OnActionClickListener listener
    ) {
        this.actionClickListener = listener;
    }

    private boolean isEmpty(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    public interface OnActionClickListener {
        void onActionClick();
    }
}