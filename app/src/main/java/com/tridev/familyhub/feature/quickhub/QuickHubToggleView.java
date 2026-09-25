package com.tridev.familyhub.feature.quickhub;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.tridev.familyhub.R;

/** The single control for the app-wide floating Grocery and To-Do hub. */
public final class QuickHubToggleView extends SwitchCompat {
    private boolean refreshing;

    public QuickHubToggleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setText(R.string.quick_hub_toggle);
        setContentDescription(getContext().getString(R.string.quick_hub_toggle));
        setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (!checked) {
                UniversalQuickHubController.stop(getContext());
            } else if (Settings.canDrawOverlays(getContext())) {
                UniversalQuickHubController.start(getContext(), true);
            } else {
                UniversalQuickHubController.setRequested(getContext(), true);
                getContext().startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName())));
            }
            refresh();
        });
        refresh();
    }

    public void refresh() {
        refreshing = true;
        setChecked(UniversalQuickHubController.isEnabled(getContext()));
        refreshing = false;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }
}
