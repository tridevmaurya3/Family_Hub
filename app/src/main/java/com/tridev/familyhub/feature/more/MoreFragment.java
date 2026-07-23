package com.tridev.familyhub.feature.more;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.BuildConfig;
import com.tridev.familyhub.R;
import com.tridev.familyhub.databinding.FragmentMoreBinding;
import com.tridev.familyhub.feature.documents.DocumentsFragment;
import com.tridev.familyhub.feature.familylive.FamilyLiveFragment;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.passwordvault.PasswordVaultFragment;

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

        binding.cardDocuments.setOnClickListener(
                clickedView -> openFeature(new DocumentsFragment())
        );
        binding.cardPasswordVault.setOnClickListener(
                clickedView -> openFeature(new PasswordVaultFragment())
        );
        binding.cardFamilyLive.setOnClickListener(
                clickedView -> openFeature(new FamilyLiveFragment())
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

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
