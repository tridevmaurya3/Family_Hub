package com.tridev.familyhub.feature.security;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;

/** Injects one isolated App Security card without changing existing profile logic. */
public final class ProfileAppSecurityInjector implements Application.ActivityLifecycleCallbacks {

    private static final String CARD_TAG = "family_hub_app_security_card";
    private final Application application;

    private ProfileAppSecurityInjector(@NonNull Application application) {
        this.application = application;
    }

    public static void register(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(
                new ProfileAppSecurityInjector(application));
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (!(activity instanceof ProfileSettingsActivity)) return;
        inject((ProfileSettingsActivity) activity);
    }

    private void inject(@NonNull ProfileSettingsActivity activity) {
        View existing = activity.getWindow().getDecorView().findViewWithTag(CARD_TAG);
        if (existing != null) return;

        View darkSwitch = activity.findViewById(R.id.switchProfileDarkTheme);
        if (darkSwitch == null) return;
        View parent = (View) darkSwitch.getParent();
        if (!(parent instanceof MaterialCardView)) return;
        ViewGroup container = (ViewGroup) parent.getParent();
        if (!(container instanceof LinearLayout)) return;

        MaterialCardView card = new MaterialCardView(activity);
        card.setTag(CARD_TAG);
        card.setClickable(true);
        card.setFocusable(true);
        card.setRadius(dp(activity, 18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(ContextCompat.getColor(activity,
                R.color.fh_module_vault_container));
        card.setStrokeColor(ContextCompat.getColor(activity, R.color.fh_module_vault));
        card.setStrokeWidth(dp(activity, 1));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(activity, 14), dp(activity, 12),
                dp(activity, 14), dp(activity, 12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(ContextCompat.getColor(activity, R.color.fh_module_vault));
        icon.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));

        LinearLayout textBlock = new LinearLayout(activity);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(activity, 10);
        row.addView(textBlock, textParams);

        TextView title = new TextView(activity);
        title.setText("App Security");
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(activity, R.color.fh_module_vault));
        textBlock.addView(title);

        TextView detail = new TextView(activity);
        detail.setText("PIN • Fingerprint • User ID/password • Auto-lock timer");
        detail.setTextSize(10.5f);
        detail.setTextColor(ContextCompat.getColor(activity, R.color.fh_text_secondary));
        textBlock.addView(detail);

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextSize(24f);
        arrow.setTextColor(ContextCompat.getColor(activity, R.color.fh_module_vault));
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(row);
        card.setOnClickListener(v -> activity.startActivity(
                new Intent(activity, AppSecurityActivity.class)));

        int index = container.indexOfChild(parent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 8);
        container.addView(card, Math.max(0, index + 1), params);
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityCreated(@NonNull Activity activity,
                                             @Nullable Bundle state) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                       @NonNull Bundle outState) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
