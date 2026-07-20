package com.tridev.familyhub.core.design;

import android.content.Context;
import android.util.TypedValue;

/**
 * Converts dp values into pixels.
 */
public final class UiSpacing {

    private UiSpacing() {
    }

    public static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        );
    }
}