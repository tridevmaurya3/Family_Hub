package com.tridev.familyhub.feature.more;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.BuildConfig;
import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.FragmentMoreBinding;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.familyaccount.FamilyManagementActivity;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.familylive.SafePlacesActivity;
import com.tridev.familyhub.feature.grocery.GroceryFragment;
import com.tridev.familyhub.feature.health.HealthFragment;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.notes.NotesFragment;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;
import com.tridev.familyhub.feature.planner.PlannerFragment;
import com.tridev.familyhub.feature.property.PropertyFragment;
import com.tridev.familyhub.feature.vehicle.VehicleFragment;

/** Fluent module hub for secondary features and essential settings. */
public class MoreFragment extends Fragment {

    private FragmentMoreBinding binding;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentMoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        applyModuleCardPalette();
        promoteSafePlacesEntry();

        binding.cardDocuments.setOnClickListener(
                clickedView -> openFeature(new DocumentsFragment())
        );
        binding.cardPasswordVault.setOnClickListener(
                clickedView -> openFeature(new PasswordVaultFragment())
        );
        binding.cardFamilyLive.setOnClickListener(
                clickedView -> openFeature(new FamilyLiveFragment())
        );
        binding.buttonSafePlaces.setOnClickListener(clickedView ->
                startActivity(new Intent(
                        requireContext(),
                        SafePlacesActivity.class
                ))
        );
        binding.cardHealth.setOnClickListener(
                clickedView -> openFeature(new HealthFragment())
        );
        binding.cardVehicle.setOnClickListener(
                clickedView -> openFeature(new VehicleFragment())
        );
        binding.cardProperty.setOnClickListener(
                clickedView -> openFeature(new PropertyFragment())
        );
        binding.cardGrocery.setOnClickListener(
                clickedView -> openFeature(new GroceryFragment())
        );
        binding.cardNotes.setOnClickListener(
                clickedView -> openFeature(new NotesFragment())
        );
        binding.cardPlanner.setOnClickListener(
                clickedView -> openFeature(new PlannerFragment())
        );

        boolean darkThemeEnabled =
                (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;

        binding.switchDarkTheme.setChecked(darkThemeEnabled);
        binding.switchDarkTheme.setOnCheckedChangeListener(
                (button, enabled) -> AppCompatDelegate.setDefaultNightMode(
                        enabled
                                ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO
                )
        );

        binding.cardBackupRestore.setOnClickListener(
                clickedView -> showBackupInformation()
        );
        binding.cardPrivacyAbout.setOnClickListener(
                clickedView -> showPrivacyInformation()
        );

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String email = user != null ? user.getEmail() : null;
        binding.tvSignedInEmail.setText(
                email == null || email.trim().isEmpty()
                        ? getString(R.string.auth_account_unknown)
                        : email
        );
        binding.buttonFamilySettings.setOnClickListener(clickedView ->
                startActivity(new Intent(
                        requireContext(),
                        FamilyManagementActivity.class
                )));
        binding.buttonLogout.setOnClickListener(clickedView -> confirmLogout());
    }

    /**
     * Keeps Safe Places visible without requiring the user to discover a small
     * button buried below the Family Live card. The existing bound button is
     * moved directly below the More page introduction and styled as a clear
     * primary family-safety action.
     */
    private void promoteSafePlacesEntry() {
        MaterialButton button = binding.buttonSafePlaces;
        ViewParent currentParent = button.getParent();
        if (currentParent instanceof ViewGroup) {
            ((ViewGroup) currentParent).removeView(button);
        }

        View rootChild = binding.getRoot().getChildAt(0);
        if (!(rootChild instanceof LinearLayout)) {
            return;
        }

        LinearLayout content = (LinearLayout) rootChild;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60)
        );
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        button.setLayoutParams(params);
        button.setMinHeight(dp(60));
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setIconResource(R.drawable.ic_family_map_recenter);
        button.setIconTintResource(R.color.fh_module_family);
        button.setIconSize(dp(22));
        button.setIconPadding(dp(12));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setTextColor(ContextCompat.getColor(
                requireContext(),
                R.color.fh_module_family
        ));
        button.setBackgroundTintList(ContextCompat.getColorStateList(
                requireContext(),
                R.color.fh_module_family_container
        ));
        button.setStrokeColor(ContextCompat.getColorStateList(
                requireContext(),
                R.color.fh_module_family
        ));
        button.setStrokeWidth(dp(1));
        button.setVisibility(View.VISIBLE);

        int insertionIndex = Math.min(2, content.getChildCount());
        content.addView(button, insertionIndex);
    }

    private void applyModuleCardPalette() {
        styleCard(
                binding.cardVehicle,
                R.color.fh_module_vehicle_container,
                R.color.fh_module_vehicle
        );
        styleCard(
                binding.cardProperty,
                R.color.fh_module_property_container,
                R.color.fh_module_property
        );
        styleCard(
                binding.cardPlanner,
                R.color.fh_module_planner_container,
                R.color.fh_module_planner
        );
        styleCard(
                binding.cardNotes,
                R.color.fh_module_notes_container,
                R.color.fh_module_notes
        );
        styleCard(
                binding.cardDocuments,
                R.color.fh_module_documents_container,
                R.color.fh_module_documents
        );
        styleCard(
                binding.cardPasswordVault,
                R.color.fh_module_vault_container,
                R.color.fh_module_vault
        );
        styleCard(
                binding.cardFamilyLive,
                R.color.fh_module_family_container,
                R.color.fh_module_family
        );
        styleCard(
                binding.cardHealth,
                R.color.fh_module_health_container,
                R.color.fh_module_health
        );
        styleCard(
                binding.cardGrocery,
                R.color.fh_module_grocery_container,
                R.color.fh_module_grocery
        );
        styleCard(
                binding.cardBackupRestore,
                R.color.fh_primary_container,
                R.color.fh_primary
        );
        styleCard(
                binding.cardPrivacyAbout,
                R.color.fh_secondary_container,
                R.color.fh_secondary
        );
    }

    private void styleCard(
            @NonNull MaterialCardView card,
            int containerColor,
            int accentColor
    ) {
        card.setCardBackgroundColor(ContextCompat.getColor(
                requireContext(),
                containerColor
        ));
        card.setStrokeColor(ContextCompat.getColor(
                requireContext(),
                accentColor
        ));
        card.setStrokeWidth(getResources().getDimensionPixelSize(
                R.dimen.border_width
        ));
    }

    private void openFeature(@NonNull Fragment fragment) {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openFeature(fragment);
        }
    }

    private void showBackupInformation() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.more_backup_title)
                .setMessage(R.string.more_backup_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showPrivacyInformation() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage(getString(
                        R.string.more_privacy_message,
                        BuildConfig.VERSION_NAME
                ))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.auth_logout)
                .setMessage(R.string.auth_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(
                        R.string.auth_logout,
                        (dialog, which) -> logout()
                )
                .show();
    }

    private void logout() {
        FirebaseAuth.getInstance().signOut();

        Intent intent = new Intent(requireContext(), AuthActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );
        startActivity(intent);
        requireActivity().finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
