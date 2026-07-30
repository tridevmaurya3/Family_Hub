package com.tridev.familyhub.core.ui;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;

/** Applies consistent semantic colours to signed numeric values. */
public final class SemanticValueStyler {

    private SemanticValueStyler() {
    }

    public static void apply(
            @NonNull TextView textView,
            double signedValue
    ) {
        int color;
        if (!Double.isFinite(signedValue) || signedValue == 0D) {
            color = R.color.fh_text_primary;
        } else if (signedValue < 0D) {
            color = R.color.fh_error;
        } else {
            color = R.color.fh_success;
        }
        textView.setTextColor(ContextCompat.getColor(
                textView.getContext(),
                color
        ));
    }
}
