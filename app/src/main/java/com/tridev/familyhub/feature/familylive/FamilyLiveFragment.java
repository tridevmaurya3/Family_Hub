package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.databinding.FragmentFamilyLiveBinding;
import com.tridev.familyhub.feature.familylive.adapter.FamilyLiveAdapter;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationSharingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Displays family profiles and transparent location-sharing controls. */
public class FamilyLiveFragment extends Fragment {

    private FragmentFamilyLiveBinding binding;
    private FamilyLiveAdapter familyLiveAdapter;
    private FamilyLiveRepository repository;
    private ActivityResultLauncher<String[]> foregroundPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean retryStartOnResume;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        foregroundPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::handleForegroundPermissionResult
        );
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        continueStartFlow();
                    } else {
                        showAppSettingsGuidance(
                                R.string.family_live_notification_denied
                        );
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFamilyLiveBinding.inflate(
                inflater,
                container,
                false
        );
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        repository = new FamilyLiveRepository(requireContext());
        familyLiveAdapter = new FamilyLiveAdapter();

        binding.recyclerFamilyLive.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.recyclerFamilyLive.setAdapter(familyLiveAdapter);
        binding.buttonLocationSharing.setOnClickListener(ignored -> {
            if (LocationSharingStore.isSharingEnabled(requireContext())) {
                stopSharing();
            } else {
                showSharingEducation();
            }
        });

        updateSharingUi();
        loadFamilyLiveMembers();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSharingUi();
        if (retryStartOnResume) {
            retryStartOnResume = false;
            startAfterSettingsIfReady();
        }
        if (repository != null) {
            loadFamilyLiveMembers();
        }
    }

    private void showSharingEducation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.family_live_permission_title)
                .setMessage(R.string.family_live_permission_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.family_live_continue,
                        (dialog, which) -> continueStartFlow()
                )
                .show();
    }

    private void continueStartFlow() {
        if (!hasForegroundLocationPermission()) {
            foregroundPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
            showBackgroundLocationGuidance();
            return;
        }

        if (!isLocationEnabled()) {
            showLocationSettingsGuidance();
            return;
        }

        startSharing();
    }

    private void handleForegroundPermissionResult(
            @NonNull Map<String, Boolean> permissions
    ) {
        boolean fineGranted = Boolean.TRUE.equals(
                permissions.get(Manifest.permission.ACCESS_FINE_LOCATION)
        );
        boolean coarseGranted = Boolean.TRUE.equals(
                permissions.get(Manifest.permission.ACCESS_COARSE_LOCATION)
        );
        if (fineGranted || coarseGranted) {
            continueStartFlow();
            return;
        }

        showAppSettingsGuidance(R.string.family_live_location_denied);
    }

    private void showBackgroundLocationGuidance() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.family_live_background_title)
                .setMessage(R.string.family_live_background_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.family_live_open_app_settings,
                        (dialog, which) -> openAppSettings()
                )
                .show();
    }

    private void showLocationSettingsGuidance() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.family_live_gps_title)
                .setMessage(R.string.family_live_gps_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.family_live_open_location_settings,
                        (dialog, which) -> {
                            retryStartOnResume = true;
                            startActivity(new Intent(
                                    Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            ));
                        }
                )
                .show();
    }

    private void showAppSettingsGuidance(int messageRes) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.family_live_permission_required)
                .setMessage(messageRes)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.family_live_open_app_settings,
                        (dialog, which) -> openAppSettings()
                )
                .show();
    }

    private void openAppSettings() {
        retryStartOnResume = true;
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null)
        );
        startActivity(intent);
    }

    private void startAfterSettingsIfReady() {
        boolean backgroundReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean notificationsReady =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;

        if (hasForegroundLocationPermission()
                && backgroundReady
                && notificationsReady
                && isLocationEnabled()) {
            startSharing();
        } else {
            Toast.makeText(
                    requireContext(),
                    R.string.family_live_settings_not_ready,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void startSharing() {
        ContextCompat.startForegroundService(
                requireContext(),
                FamilyLocationService.startIntent(requireContext())
        );
        binding.tvSharingStatus.setText(
                R.string.family_live_sharing_starting
        );
        binding.buttonLocationSharing.setEnabled(false);
        binding.getRoot().postDelayed(() -> {
            if (binding != null) {
                binding.buttonLocationSharing.setEnabled(true);
                updateSharingUi();
            }
        }, 1200L);
    }

    private void stopSharing() {
        requireContext().startService(
                FamilyLocationService.stopIntent(requireContext())
        );
        LocationSharingStore.setSharingEnabled(requireContext(), false);
        updateSharingUi();
    }

    private boolean hasForegroundLocationPermission() {
        return ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        LocationManager manager = (LocationManager) requireContext()
                .getSystemService(android.content.Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
        );
    }

    private void updateSharingUi() {
        if (binding == null) {
            return;
        }
        boolean enabled =
                LocationSharingStore.isSharingEnabled(requireContext());
        binding.tvSharingStatus.setText(enabled
                ? R.string.family_live_sharing_on
                : R.string.family_live_sharing_off);
        binding.tvSharingDetail.setText(enabled
                ? R.string.family_live_sharing_on_detail
                : R.string.family_live_sharing_off_detail);
        binding.buttonLocationSharing.setText(enabled
                ? R.string.family_live_stop_sharing
                : R.string.family_live_start_sharing);
    }

    private void loadFamilyLiveMembers() {
        repository.loadMemberStatuses(memberStatuses -> {
            if (binding == null) {
                return;
            }

            List<FamilyLiveMemberUiModel> uiModels =
                    mapToUiModels(memberStatuses);

            familyLiveAdapter.submitList(uiModels);
            binding.tvMemberCount.setText(getString(
                    R.string.family_live_member_count,
                    uiModels.size()
            ));

            boolean isEmpty = uiModels.isEmpty();
            binding.recyclerFamilyLive.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.familyLiveEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        });
    }

    @NonNull
    private List<FamilyLiveMemberUiModel> mapToUiModels(
            @NonNull List<FamilyLiveMemberData> memberStatuses
    ) {
        List<FamilyLiveMemberUiModel> uiModels = new ArrayList<>();

        for (FamilyLiveMemberData data : memberStatuses) {
            boolean locationVisible =
                    data.isLocationSharingEnabled && data.hasLocation;

            String location = locationVisible
                    ? data.currentPlaceName
                    : getString(R.string.family_live_location_off);

            uiModels.add(new FamilyLiveMemberUiModel(
                    data.familyMemberId,
                    data.memberName,
                    location,
                    data.onlineStatus,
                    data.batteryPercentage,
                    data.isCharging,
                    data.hasInternet,
                    data.movementType,
                    data.lastUpdatedAt
            ));
        }

        return uiModels;
    }

    @Override
    public void onDestroyView() {
        binding.recyclerFamilyLive.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}
