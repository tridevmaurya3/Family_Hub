package com.tridev.familyhub.feature.main;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.automation.FamilyAutomationActivity;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.diagnostics.AppDiagnosticsActivity;
import com.tridev.familyhub.feature.familyaccount.FamilyManagementActivity;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.familylive.SafePlacesActivity;
import com.tridev.familyhub.feature.grocery.GroceryFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.help.HelpActivity;
import com.tridev.familyhub.feature.insights.FamilyLocationReportsActivity;
import com.tridev.familyhub.feature.journey.FamilyJourneyActivity;
import com.tridev.familyhub.feature.notes.NotesFragment;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;
import com.tridev.familyhub.feature.planner.PlannerFragment;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;
import com.tridev.familyhub.feature.property.PropertyFragment;
import com.tridev.familyhub.feature.safety.FamilySafetyCenterActivity;
import com.tridev.familyhub.feature.sos.FamilySosActivity;
import com.tridev.familyhub.feature.search.GlobalSearchActivity;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;

/** A categorized hamburger menu for all Family Hub features. */
public final class FamilyFeatureMenu {

    private FamilyFeatureMenu() {
    }

    public static void show(@NonNull MainActivity activity) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        NestedScrollView scrollView = new NestedScrollView(activity);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 12), dp(activity, 10),
                dp(activity, 12), dp(activity, 18));
        GradientDrawable drawerSurface = new GradientDrawable();
        drawerSurface.setColor(ContextCompat.getColor(activity, R.color.fh_surface));
        drawerSurface.setCornerRadii(new float[]{
                0F, 0F, dp(activity, 22), dp(activity, 22),
                dp(activity, 22), dp(activity, 22), 0F, 0F
        });
        content.setBackground(drawerSurface);
        scrollView.addView(content, new NestedScrollView.LayoutParams(
                NestedScrollView.LayoutParams.MATCH_PARENT,
                NestedScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text(activity, activity.getString(
                R.string.feature_menu_title), 22F, true,
                R.color.fh_text_primary);
        content.addView(title);

        TextView subtitle = text(activity, activity.getString(
                R.string.feature_menu_subtitle), 13F, false,
                R.color.fh_text_secondary);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(activity, 2);
        content.addView(subtitle, subtitleParams);

        addAccountHeader(activity, dialog, content);

        addSection(activity, content, R.string.feature_menu_safety);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_safety_center,
                R.drawable.ic_safe_place_shield,
                R.color.fh_primary,
                R.color.fh_primary_container,
                FamilySafetyCenterActivity.class);
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_family_live,
                R.drawable.ic_family,
                R.color.fh_module_family,
                R.color.fh_module_family_container,
                new FamilyLiveFragment());
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_sos,
                R.drawable.ic_family_sos,
                R.color.fh_error,
                R.color.fh_error_container,
                FamilySosActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_safe_places,
                R.drawable.ic_safe_place_pin,
                R.color.fh_success,
                R.color.fh_success_container,
                SafePlacesActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_journey,
                R.drawable.ic_family_map_route,
                R.color.fh_secondary,
                R.color.fh_secondary_container,
                FamilyJourneyActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_reports,
                R.drawable.ic_family_map_route,
                R.color.fh_info,
                R.color.fh_info_container,
                FamilyLocationReportsActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_routines,
                R.drawable.ic_family_automation,
                R.color.fh_warning,
                R.color.fh_warning_container,
                FamilyAutomationActivity.class);

        addSection(activity, content, R.string.feature_menu_family);
        addTabButton(activity, dialog, content,
                R.string.feature_menu_members,
                R.drawable.ic_family,
                R.color.fh_module_family,
                R.color.fh_module_family_container,
                R.id.nav_family);
        addTabButton(activity, dialog, content,
                R.string.feature_menu_reminders,
                R.drawable.ic_reminder,
                R.color.fh_module_reminders,
                R.color.fh_module_reminders_container,
                R.id.nav_reminders);
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_planner,
                R.drawable.ic_planner,
                R.color.fh_module_planner,
                R.color.fh_module_planner_container,
                new PlannerFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_grocery,
                R.drawable.ic_grocery,
                R.color.fh_module_grocery,
                R.color.fh_module_grocery_container,
                new GroceryFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_notes,
                R.drawable.ic_note,
                R.color.fh_module_notes,
                R.color.fh_module_notes_container,
                new NotesFragment());
        addTabButton(activity, dialog, content,
                R.string.feature_menu_finance,
                R.drawable.ic_wallet,
                R.color.fh_module_finance,
                R.color.fh_module_finance_container,
                R.id.nav_finance);

        addSection(activity, content, R.string.feature_menu_records);
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_documents,
                R.drawable.ic_document,
                R.color.fh_module_documents,
                R.color.fh_module_documents_container,
                new DocumentsFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_health,
                R.drawable.ic_health,
                R.color.fh_module_health,
                R.color.fh_module_health_container,
                new HealthFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_vehicle,
                R.drawable.ic_vehicle,
                R.color.fh_module_vehicle,
                R.color.fh_module_vehicle_container,
                new VehicleFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_property,
                R.drawable.ic_property,
                R.color.fh_module_property,
                R.color.fh_module_property_container,
                new PropertyFragment());
        addFragmentButton(activity, dialog, content,
                R.string.feature_menu_vault,
                R.drawable.ic_lock,
                R.color.fh_module_vault,
                R.color.fh_module_vault_container,
                new PasswordVaultFragment());

        addSection(activity, content, R.string.feature_menu_account);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_global_search,
                R.drawable.ic_search,
                R.color.fh_info,
                R.color.fh_info_container,
                GlobalSearchActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_diagnostics,
                R.drawable.ic_health,
                R.color.fh_success,
                R.color.fh_success_container,
                AppDiagnosticsActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_help,
                R.drawable.ic_help,
                R.color.fh_secondary,
                R.color.fh_secondary_container,
                HelpActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_profile,
                R.drawable.ic_profile_person,
                R.color.fh_primary,
                R.color.fh_primary_container,
                ProfileSettingsActivity.class);
        addActivityButton(activity, dialog, content,
                R.string.feature_menu_family_settings,
                R.drawable.ic_family,
                R.color.fh_module_family,
                R.color.fh_module_family_container,
                FamilyManagementActivity.class);

        dialog.setContentView(scrollView);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.START | Gravity.TOP);
            window.setDimAmount(0.22F);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(R.style.Animation_FamilyHub_SideDrawer);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(
                    dp(activity, 360),
                    Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.88F)
            );
            window.setLayout(width, android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private static void addAccountHeader(
            @NonNull MainActivity activity,
            @NonNull Dialog dialog,
            @NonNull LinearLayout parent
    ) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String name = user == null ? null : user.getDisplayName();
        String email = user == null ? null : user.getEmail();
        if (name == null || name.trim().isEmpty()) {
            name = activity.getString(R.string.feature_menu_profile);
        }
        if (email == null || email.trim().isEmpty()) {
            email = activity.getString(R.string.profile_unknown);
        }

        MaterialCardView card = new MaterialCardView(activity);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(activity, 10);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(activity, 18));
        card.setCardElevation(0F);
        card.setStrokeWidth(dp(activity, 1));
        card.setStrokeColor(lighten(activity, R.color.fh_primary, 0.68F));
        card.setCardBackgroundColor(lighten(
                activity, R.color.fh_primary_container, 0.48F));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(activity, 12), dp(activity, 8),
                dp(activity, 12), dp(activity, 8));

        TextView avatar = text(activity, firstLetter(name), 20F, true,
                R.color.fh_primary);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.bg_placeholder_icon);
        row.addView(avatar, new LinearLayout.LayoutParams(
                dp(activity, 40),
                dp(activity, 40)
        ));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        labelParams.leftMargin = dp(activity, 12);
        row.addView(labels, labelParams);
        labels.addView(text(activity, name.trim(), 16F, true,
                R.color.fh_text_primary));
        labels.addView(text(activity, email.trim(), 12F, false,
                R.color.fh_text_secondary));

        card.addView(row);
        card.setOnClickListener(v -> {
            dialog.dismiss();
            activity.openProfile();
        });
        parent.addView(card);
    }

    private static void addSection(
            @NonNull MainActivity activity,
            @NonNull LinearLayout parent,
            int titleRes
    ) {
        TextView title = text(activity, activity.getString(titleRes),
                13F, true, R.color.fh_primary);
        title.setAllCaps(true);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(activity, 12);
        params.bottomMargin = dp(activity, 2);
        parent.addView(title, params);
    }

    private static void addActivityButton(
            @NonNull MainActivity activity,
            @NonNull Dialog dialog,
            @NonNull LinearLayout parent,
            int titleRes,
            @DrawableRes int iconRes,
            @ColorRes int accentColor,
            @ColorRes int containerColor,
            @NonNull Class<?> activityClass
    ) {
        addButton(activity, parent, titleRes, iconRes, accentColor,
                containerColor, v -> {
                    dialog.dismiss();
                    activity.startActivity(new Intent(activity, activityClass));
                });
    }

    private static void addTabButton(
            @NonNull MainActivity activity,
            @NonNull Dialog dialog,
            @NonNull LinearLayout parent,
            int titleRes,
            @DrawableRes int iconRes,
            @ColorRes int accentColor,
            @ColorRes int containerColor,
            int tabId
    ) {
        addButton(activity, parent, titleRes, iconRes, accentColor,
                containerColor, v -> {
                    dialog.dismiss();
                    activity.openTab(tabId);
                });
    }

    private static void addFragmentButton(
            @NonNull MainActivity activity,
            @NonNull Dialog dialog,
            @NonNull LinearLayout parent,
            int titleRes,
            @DrawableRes int iconRes,
            @ColorRes int accentColor,
            @ColorRes int containerColor,
            @NonNull androidx.fragment.app.Fragment fragment
    ) {
        addButton(activity, parent, titleRes, iconRes, accentColor,
                containerColor, v -> {
                    dialog.dismiss();
                    activity.openFeature(fragment);
                });
    }

    private static void addButton(
            @NonNull MainActivity activity,
            @NonNull LinearLayout parent,
            int titleRes,
            @DrawableRes int iconRes,
            @ColorRes int accentColor,
            @ColorRes int containerColor,
            @NonNull View.OnClickListener listener
    ) {
        MaterialButton button = new MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(activity, 3);
        params.height = dp(activity, 46);
        button.setLayoutParams(params);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(activity, 12), 0, dp(activity, 10), 0);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setText(titleRes);
        button.setTextSize(13F);
        button.setAllCaps(false);
        button.setIconResource(iconRes);
        button.setIconSize(dp(activity, 19));
        button.setIconPadding(dp(activity, 10));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setTextColor(ContextCompat.getColor(activity, accentColor));
        button.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(activity, accentColor)
        ));
        button.setBackgroundTintList(ColorStateList.valueOf(
                lighten(activity, containerColor, 0.48F)
        ));
        button.setStrokeColor(ColorStateList.valueOf(
                lighten(activity, accentColor, 0.62F)
        ));
        button.setStrokeWidth(dp(activity, 1));
        button.setCornerRadius(dp(activity, 12));
        button.setOnClickListener(listener);
        parent.addView(button);
    }

    @NonNull
    private static TextView text(
            @NonNull MainActivity activity,
            @NonNull String value,
            float size,
            boolean bold,
            @ColorRes int colorRes
    ) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(activity, colorRes));
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return text;
    }

    @NonNull
    private static String firstLetter(@NonNull String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty()
                ? "F"
                : trimmed.substring(0, 1).toUpperCase();
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(@NonNull MainActivity activity, int value) {
        return Math.round(value * activity.getResources()
                .getDisplayMetrics().density);
    }

    private static int lighten(
            @NonNull MainActivity activity,
            @ColorRes int colorRes,
            float whiteRatio
    ) {
        return ColorUtils.blendARGB(
                ContextCompat.getColor(activity, colorRes),
                Color.WHITE,
                whiteRatio
        );
    }
}
