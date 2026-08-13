package com.tridev.familyhub.feature.more;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.tridev.familyhub.backup.BackupRestoreActivity;
import com.tridev.familyhub.databinding.FragmentMoreBinding;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.familyaccount.FamilyManagementActivity;
import com.tridev.familyhub.MainActivity;

/** Compact account, settings, encrypted backup and privacy page. */
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

        hideModuleCards();
        addProfileEntry();
        styleSettingsCards();

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
                clickedView -> startActivity(new Intent(
                        requireContext(),
                        BackupRestoreActivity.class
                ))
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

    /** Removes feature duplicates; modules are available from the hamburger. */
    private void hideModuleCards() {
        binding.cardVehicle.setVisibility(View.GONE);
        binding.cardProperty.setVisibility(View.GONE);
        binding.cardPlanner.setVisibility(View.GONE);
        binding.cardNotes.setVisibility(View.GONE);
        binding.cardDocuments.setVisibility(View.GONE);
        binding.cardPasswordVault.setVisibility(View.GONE);
        binding.cardFamilyLive.setVisibility(View.GONE);
        binding.cardHealth.setVisibility(View.GONE);
        binding.cardGrocery.setVisibility(View.GONE);
        binding.buttonSafePlaces.setVisibility(View.GONE);

        View rootChild = binding.getRoot().getChildAt(0);
        if (!(rootChild instanceof LinearLayout)) {
            return;
        }
        LinearLayout content = (LinearLayout) rootChild;
        String modulesTitle = getString(R.string.more_modules_title);
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child instanceof TextView
                    && modulesTitle.contentEquals(((TextView) child).getText())) {
                child.setVisibility(View.GONE);
            }
        }
    }

    /** Adds a clear account entry directly below the page description. */
    private void addProfileEntry() {
        View rootChild = binding.getRoot().getChildAt(0);
        if (!(rootChild instanceof LinearLayout)) {
            return;
        }
        LinearLayout content = (LinearLayout) rootChild;
        MaterialButton profile = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(68)
        );
        params.bottomMargin = dp(4);
        profile.setLayoutParams(params);
        profile.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        profile.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        profile.setPadding(dp(18), 0, dp(18), 0);
        profile.setText(getString(R.string.more_profile_title)
                + "\n" + getString(R.string.more_profile_detail));
        profile.setTextSize(14F);
        profile.setAllCaps(false);
        profile.setMaxLines(2);
        profile.setIconResource(R.drawable.ic_arrow_back);
        profile.setIconTintResource(R.color.fh_primary);
        profile.setIconSize(dp(24));
        profile.setIconPadding(dp(12));
        profile.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        profile.setTextColor(ContextCompat.getColor(
                requireContext(),
                R.color.fh_primary
        ));
        profile.setBackgroundTintList(ContextCompat.getColorStateList(
                requireContext(),
                R.color.fh_primary_container
        ));
        profile.setStrokeColor(ContextCompat.getColorStateList(
                requireContext(),
                R.color.fh_primary
        ));
        profile.setStrokeWidth(dp(1));
        profile.setCornerRadius(dp(20));
        profile.setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openHome();
            } else {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });
        content.addView(profile, 0);
    }

    private void styleSettingsCards() {
        styleCard(binding.cardBackupRestore,
                R.color.fh_primary_container,
                R.color.fh_primary);
        styleCard(binding.cardPrivacyAbout,
                R.color.fh_secondary_container,
                R.color.fh_secondary);
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
