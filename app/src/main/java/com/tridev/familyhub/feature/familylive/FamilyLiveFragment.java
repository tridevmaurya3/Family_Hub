package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
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

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.databinding.FragmentFamilyLiveBinding;
import com.tridev.familyhub.feature.familylive.adapter.FamilyLiveAdapter;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationSharingStore;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Displays family profiles and transparent location-sharing controls. */
public class FamilyLiveFragment extends Fragment {

    private static final long LIVE_FRESHNESS_MS = 3L * 60L * 1000L;
    private static final String STATE_MEMBER_FILTER =
            "family_live_member_filter";

    private FragmentFamilyLiveBinding binding;
    private FamilyLiveAdapter familyLiveAdapter;
    private FamilyLiveRepository repository;
    private ActivityResultLauncher<String[]> foregroundPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> activityRecognitionPermissionLauncher;
    private boolean retryStartOnResume;
    private boolean activityRecognitionPermissionHandled;
    private boolean cloudErrorShown;
    private boolean cloudDataReceived;
    private boolean mapViewSelected;
    private boolean fitMapOnNextRender = true;
    private boolean satelliteMap;
    private int selectedFilterId = R.id.chipFamilyLiveAll;
    @NonNull private String memberQuery = "";
    @Nullable private GoogleMap googleMap;
    @NonNull
    private List<FamilyLiveCloudMember> latestCloudMembers =
            new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedFilterId = savedInstanceState.getInt(
                    STATE_MEMBER_FILTER,
                    R.id.chipFamilyLiveAll
            );
        }

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
        activityRecognitionPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    activityRecognitionPermissionHandled = true;
                    continueStartFlow();
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
        familyLiveAdapter.setOnMemberClickListener(
                member -> {
                    if (member.getCloudMemberUid().isEmpty()) {
                        showMemberDetails(member);
                    } else {
                        startActivity(FamilyMemberDetailActivity.createIntent(
                                requireContext(),
                                member.getCloudMemberUid()
                        ));
                    }
                }
        );

        binding.recyclerFamilyLive.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );
        binding.recyclerFamilyLive.setAdapter(familyLiveAdapter);
        binding.familyLiveRefresh.setColorSchemeResources(
                R.color.fh_module_family,
                R.color.fh_primary,
                R.color.fh_secondary
        );
        binding.familyLiveRefresh.setOnRefreshListener(
                this::refreshFamilyLive
        );
        binding.viewToggle.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (!isChecked) {
                        return;
                    }
                    if (checkedId == R.id.buttonMapView) {
                        startActivity(new Intent(
                                requireContext(),
                                FamilyMapActivity.class
                        ));
                        binding.buttonListView.setChecked(true);
                    } else {
                        showListView();
                    }
                }
        );
        binding.buttonRecenter.setOnClickListener(ignored -> {
            fitMapOnNextRender = true;
            renderMapMarkers();
        });
        binding.buttonMapType.setOnClickListener(ignored -> toggleMapType());
        binding.familyLiveSearchInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence value,
                            int start,
                            int count,
                            int after
                    ) {
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence value,
                            int start,
                            int before,
                            int count
                    ) {
                        memberQuery = value == null
                                ? ""
                                : value.toString().trim();
                        if (cloudDataReceived) {
                            renderCloudMembers(latestCloudMembers);
                        }
                    }

                    @Override
                    public void afterTextChanged(Editable value) {
                    }
                }
        );
        binding.familyLiveFilterGroup.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    selectedFilterId = checkedIds.isEmpty()
                            ? R.id.chipFamilyLiveAll
                            : checkedIds.get(0);
                    if (cloudDataReceived) {
                        renderCloudMembers(latestCloudMembers);
                    }
                }
        );
        binding.familyLiveFilterGroup.check(selectedFilterId);
        binding.buttonLocationSharing.setOnClickListener(ignored -> {
            if (LocationSharingStore.isSharingEnabled(requireContext())) {
                stopSharing();
            } else {
                showSharingEducation();
            }
        });

        updateSharingUi();
        loadLocalFamilyLiveMembers();
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
            loadLocalFamilyLiveMembers();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        cloudErrorShown = false;
        cloudDataReceived = false;
        observeCloudMembers();
    }

    @Override
    public void onStop() {
        if (repository != null) {
            repository.stopObservingCloudMembers();
        }
        super.onStop();
    }

    private void renderCloudMembers(
            @NonNull List<FamilyLiveCloudMember> members
    ) {
        if (binding == null) {
            return;
        }
        binding.familyLiveRefresh.setRefreshing(false);
        cloudDataReceived = true;
        latestCloudMembers = new ArrayList<>(members);
        List<FamilyLiveMemberUiModel> uiModels = new ArrayList<>();
        long now = System.currentTimeMillis();

        String normalizedQuery =
                memberQuery.toLowerCase(Locale.ROOT);
        for (FamilyLiveCloudMember member : members) {
            String searchable = (
                    member.displayName
                            + " "
                            + member.role
                            + " "
                            + member.placeLabel
            )
                    .toLowerCase(Locale.ROOT);
            if (!normalizedQuery.isEmpty()
                    && !searchable.contains(normalizedQuery)) {
                continue;
            }
            String availabilityReason = FamilyLiveAvailability.resolve(
                    member,
                    now,
                    LIVE_FRESHNESS_MS
            );
            if (!matchesSelectedFilter(availabilityReason)) {
                continue;
            }
            boolean stale = FamilyLiveAvailability.NO_RECENT_UPDATE.equals(
                    availabilityReason
            );
            String location;
            if (!member.sharingEnabled) {
                location = getString(R.string.family_live_location_off);
            } else if (!member.hasLocation) {
                location = getString(
                        R.string.family_live_location_unavailable
                );
            } else if (stale) {
                location = getString(
                        R.string.family_live_location_last_known
                );
            } else if (!member.placeLabel.trim().isEmpty()) {
                location = getString(
                        R.string.family_live_place_with_accuracy,
                        member.placeLabel,
                        Math.round(member.accuracy)
                );
            } else {
                location = getString(
                        R.string.family_live_location_accuracy,
                        Math.round(member.accuracy)
                );
            }

            boolean currentlyOnline =
                    FamilyLiveAvailability.isAvailable(availabilityReason);
            String displayName = member.displayName.trim().isEmpty()
                    ? getString(R.string.family_account_member_fallback)
                    : member.displayName;

            uiModels.add(new FamilyLiveMemberUiModel(
                    Integer.toUnsignedLong(member.uid.hashCode()),
                    member.uid,
                    displayName,
                    location,
                    currentlyOnline ? "ONLINE" : "OFFLINE",
                    availabilityReason,
                    member.batteryPercentage,
                    member.charging,
                    currentlyOnline,
                    movementDisplay(member),
                    member.updatedAt
            ));
        }

        renderMemberList(uiModels);
        renderMapMarkers();
    }

    private boolean matchesSelectedFilter(
            @NonNull String availabilityReason
    ) {
        if (selectedFilterId == R.id.chipFamilyLiveLive) {
            return FamilyLiveAvailability.isAvailable(availabilityReason);
        }
        if (selectedFilterId == R.id.chipFamilyLiveStale) {
            return FamilyLiveAvailability.NO_RECENT_UPDATE.equals(
                    availabilityReason
            );
        }
        if (selectedFilterId == R.id.chipFamilyLiveAttention) {
            return !FamilyLiveAvailability.isAvailable(availabilityReason)
                    && !FamilyLiveAvailability.NO_RECENT_UPDATE.equals(
                            availabilityReason
                    );
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_MEMBER_FILTER, selectedFilterId);
        super.onSaveInstanceState(outState);
    }

    private void refreshFamilyLive() {
        if (binding == null || repository == null) {
            return;
        }
        cloudErrorShown = false;
        repository.stopObservingCloudMembers();
        loadLocalFamilyLiveMembers();
        observeCloudMembers();
    }

    private void observeCloudMembers() {
        if (repository == null) {
            if (binding != null) {
                binding.familyLiveRefresh.setRefreshing(false);
            }
            return;
        }
        repository.observeCloudMembers(
                this::renderCloudMembers,
                error -> {
                    if (binding == null) {
                        return;
                    }
                    binding.familyLiveRefresh.setRefreshing(false);
                    if (cloudErrorShown) {
                        return;
                    }
                    cloudErrorShown = true;
                    Toast.makeText(
                            requireContext(),
                            R.string.family_live_sync_error,
                            Toast.LENGTH_LONG
                    ).show();
                }
        );
    }

    private void showListView() {
        mapViewSelected = false;
        if (binding == null) {
            return;
        }
        binding.mapContainer.setVisibility(View.GONE);
        binding.familyLiveSearchLayout.setVisibility(View.VISIBLE);
        binding.listHeader.setVisibility(View.VISIBLE);
        boolean isEmpty = familyLiveAdapter == null
                || familyLiveAdapter.getItemCount() == 0;
        binding.recyclerFamilyLive.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.familyLiveEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );
    }

    private void showMapView() {
        mapViewSelected = true;
        if (binding == null) {
            return;
        }
        binding.familyLiveSearchLayout.setVisibility(View.GONE);
        binding.listHeader.setVisibility(View.GONE);
        binding.recyclerFamilyLive.setVisibility(View.GONE);
        binding.familyLiveEmptyState.setVisibility(View.GONE);
        binding.mapContainer.setVisibility(View.VISIBLE);
        ensureMap();
        renderMapMarkers();
    }

    private void ensureMap() {
        if (googleMap != null || binding == null) {
            return;
        }
        binding.mapLoading.setVisibility(View.VISIBLE);
        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager()
                        .findFragmentById(R.id.mapHost);
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.mapHost, mapFragment)
                    .commitNow();
        }
        mapFragment.getMapAsync(map -> {
            if (binding == null) {
                return;
            }
            googleMap = map;
            googleMap.getUiSettings().setCompassEnabled(true);
            googleMap.getUiSettings().setMapToolbarEnabled(false);
            binding.mapLoading.setVisibility(View.GONE);
            binding.mapError.setVisibility(View.GONE);
            renderMapMarkers();
        });
    }

    private void renderMapMarkers() {
        if (binding == null || googleMap == null) {
            return;
        }
        googleMap.clear();
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        LatLng onlyPosition = null;
        int markerCount = 0;
        long now = System.currentTimeMillis();

        for (FamilyLiveCloudMember member : latestCloudMembers) {
            if (!member.sharingEnabled || !member.hasLocation) {
                continue;
            }
            boolean stale = member.updatedAt <= 0L
                    || now - member.updatedAt > LIVE_FRESHNESS_MS;
            LatLng position = new LatLng(
                    member.latitude,
                    member.longitude
            );
            String displayName = member.displayName.trim().isEmpty()
                    ? getString(R.string.family_account_member_fallback)
                    : member.displayName;
            MarkerOptions marker = new MarkerOptions()
                    .position(position)
                    .title(displayName)
                    .snippet(getString(stale
                            ? R.string.family_live_map_marker_stale
                            : R.string.family_live_map_marker_live))
                    .icon(BitmapDescriptorFactory.defaultMarker(stale
                            ? BitmapDescriptorFactory.HUE_ORANGE
                            : BitmapDescriptorFactory.HUE_GREEN))
                    .alpha(stale ? 0.72F : 1F);
            googleMap.addMarker(marker);
            bounds.include(position);
            onlyPosition = position;
            markerCount++;
        }

        binding.mapError.setVisibility(
                markerCount == 0 ? View.VISIBLE : View.GONE
        );
        if (!mapViewSelected || markerCount == 0 || !fitMapOnNextRender) {
            return;
        }

        fitMapOnNextRender = false;
        final int safeMarkerCount = markerCount;
        final LatLng singlePosition = onlyPosition;
        binding.mapContainer.post(() -> {
            if (binding == null || googleMap == null) {
                return;
            }
            if (safeMarkerCount == 1 && singlePosition != null) {
                googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                                singlePosition,
                                15F
                        )
                );
            } else {
                googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(
                                bounds.build(),
                                72
                        )
                );
            }
        });
    }

    private void toggleMapType() {
        if (googleMap == null) {
            ensureMap();
            return;
        }
        satelliteMap = !satelliteMap;
        googleMap.setMapType(satelliteMap
                ? GoogleMap.MAP_TYPE_SATELLITE
                : GoogleMap.MAP_TYPE_NORMAL);
        binding.buttonMapType.setText(satelliteMap
                ? R.string.family_live_map_type_normal
                : R.string.family_live_map_type_satellite);
    }

    @NonNull
    private String movementDisplay(
            @NonNull FamilyLiveCloudMember member
    ) {
        int label;
        switch (member.movementType) {
            case "STATIONARY":
                label = R.string.family_live_movement_stationary;
                break;
            case "WALKING":
                label = R.string.family_live_movement_walking;
                break;
            case "CYCLING":
                label = R.string.family_live_movement_cycling;
                break;
            case "TRAVELLING":
                label = R.string.family_live_movement_travelling;
                break;
            default:
                return getString(R.string.family_live_unknown);
        }

        String movement = getString(label);
        if (member.speedMetersPerSecond < 0.3D) {
            return movement;
        }
        long speedKmh = Math.round(member.speedMetersPerSecond * 3.6D);
        return getString(
                R.string.family_live_movement_speed,
                movement,
                speedKmh
        );
    }

    private void showMemberDetails(
            @NonNull FamilyLiveMemberUiModel member
    ) {
        String battery;
        if (member.getBatteryPercentage() < 0) {
            battery = getString(
                    R.string.family_live_battery_unavailable
            );
        } else if (member.isCharging()) {
            battery = getString(
                    R.string.family_live_charging_format,
                    member.getBatteryPercentage()
            );
        } else {
            battery = getString(
                    R.string.family_live_battery_format,
                    member.getBatteryPercentage()
            );
        }

        String updated = member.getLastUpdatedTime() <= 0L
                ? getString(R.string.family_live_update_unavailable)
                : DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                ).format(new Date(member.getLastUpdatedTime()));

        String message = getString(
                R.string.family_live_member_details_message,
                member.getCurrentLocation(),
                member.getOnlineStatus(),
                battery,
                member.isInternetAvailable()
                        ? getString(
                        R.string.family_live_internet_available
                )
                        : getString(
                        R.string.family_live_internet_unavailable
                ),
                member.getMovementType(),
                updated
        );

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(member.getMemberName())
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
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
                && !activityRecognitionPermissionHandled
                && ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED) {
            showActivityRecognitionEducation();
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

    private void showActivityRecognitionEducation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.family_live_activity_permission_title)
                .setMessage(
                        R.string.family_live_activity_permission_explanation
                )
                .setNegativeButton(
                        R.string.family_live_not_now,
                        (dialog, which) -> {
                            activityRecognitionPermissionHandled = true;
                            continueStartFlow();
                        }
                )
                .setPositiveButton(
                        R.string.family_live_activity_permission_allow,
                        (dialog, which) ->
                                activityRecognitionPermissionLauncher.launch(
                                        Manifest.permission.ACTIVITY_RECOGNITION
                                )
                )
                .show();
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

    private void loadLocalFamilyLiveMembers() {
        repository.loadMemberStatuses(memberStatuses -> {
            if (binding == null
                    || familyLiveAdapter == null
                    || cloudDataReceived) {
                return;
            }
            renderMemberList(mapToUiModels(memberStatuses));
        });
    }

    private void renderMemberList(
            @NonNull List<FamilyLiveMemberUiModel> uiModels
    ) {
        if (binding == null || familyLiveAdapter == null) {
            return;
        }
        familyLiveAdapter.submitList(uiModels);
        binding.tvMemberCount.setText(getString(
                R.string.family_live_member_count,
                uiModels.size()
        ));

        boolean isEmpty = uiModels.isEmpty();
        if (mapViewSelected) {
            binding.recyclerFamilyLive.setVisibility(View.GONE);
            binding.familyLiveEmptyState.setVisibility(View.GONE);
        } else {
            binding.recyclerFamilyLive.setVisibility(
                    isEmpty ? View.GONE : View.VISIBLE
            );
            binding.familyLiveEmptyState.setVisibility(
                    isEmpty ? View.VISIBLE : View.GONE
            );
        }
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
                    "",
                    data.memberName,
                    location,
                    data.onlineStatus,
                    "ONLINE".equalsIgnoreCase(data.onlineStatus)
                            ? FamilyLiveAvailability.AVAILABLE
                            : FamilyLiveAvailability.DEVICE_OFFLINE,
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
        if (repository != null) {
            repository.close();
            repository = null;
        }
        binding.recyclerFamilyLive.setAdapter(null);
        familyLiveAdapter = null;
        googleMap = null;
        latestCloudMembers = new ArrayList<>();
        binding = null;
        super.onDestroyView();
    }
}
