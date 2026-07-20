package com.tridev.familyhub.core.ui.cards;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.R;

/**
 * Reusable Status Card used throughout Family Hub.
 */
public class StatusCardView extends FrameLayout {

    private ImageView icon;
    private TextView title;
    private TextView value;
    private TextView subtitle;

    public StatusCardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public StatusCardView(@NonNull Context context,
                          @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StatusCardView(@NonNull Context context,
                          @Nullable AttributeSet attrs,
                          int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {

        LayoutInflater.from(context)
                .inflate(R.layout.view_status_card,
                        this,
                        true);

        icon = findViewById(R.id.imgStatusIcon);
        title = findViewById(R.id.txtStatusTitle);
        value = findViewById(R.id.txtStatusValue);
        subtitle = findViewById(R.id.txtStatusSubtitle);
    }

    public void setModel(StatusCardModel model) {

        title.setText(model.getTitle());
        value.setText(model.getValue());
        subtitle.setText(model.getSubtitle());

        if (model.getIconResId() != 0) {
            icon.setImageResource(model.getIconResId());
        }
    }
}