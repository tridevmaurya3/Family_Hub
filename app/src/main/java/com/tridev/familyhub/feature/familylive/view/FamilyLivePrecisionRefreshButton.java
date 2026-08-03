package com.tridev.familyhub.feature.familylive.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.tridev.familyhub.location.FamilyLivePrecisionSessionController;

/** Refresh button that also requests a short precision update from sharers. */
public final class FamilyLivePrecisionRefreshButton extends MaterialButton {

    public FamilyLivePrecisionRefreshButton(@NonNull Context context) {
        super(context);
    }

    public FamilyLivePrecisionRefreshButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
    }

    public FamilyLivePrecisionRefreshButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean performClick() {
        FamilyLivePrecisionSessionController.requestOneShot(getContext());
        return super.performClick();
    }
}
