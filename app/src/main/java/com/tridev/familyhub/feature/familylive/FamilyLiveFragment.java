package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
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
import com.tridev.familyhub.feature.journey.FamilyJourneyActivity;
import com.tridev.familyhub.location.FamilyLocationService;
import com.tridev.familyhub.location.LocationFreshnessPolicy;
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
    private static final long CONTENT_ANIMATION_MS = 220L;
    private static final long SHARING_STATUS_REFRESH_MS = 30L * 1000L;
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
    private long selectedSharingDurationMs = 8L * 60L * 60L * 1000L;
    private final Runnable sharingStatusRefresh = new Runnable() {
        @Override
        public void run() {
            if (binding == null || !isAdded()) {
                return;
            }
            updateSharingUi();
            scheduleSharingStatusRefresh();
        }
    };
    private int selectedFilterId = R.id.chipFamilyLiveAll;
    private int lastRenderedCount = -1;
    @NonNull private String memberQuery = "";
    @Nullable private GoogleMap googleMap;
    @NonNull
    private List<FamilyLiveCloudMember> latestCloudMembers =
            new ArrayList<>();
    @NonNull
    private List<FamilyLiveMemberData> latestLocalMembers =
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
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setAddDuration(CONTENT_ANIMATION_MS);
        itemAnimator.setRemoveDuration(CONTENT_ANIMATION_MS);
        itemAnimator.setMoveDuration(CONTENT_ANIMATION_MS);
        itemAnimator.setChangeDuration(CONTENT_ANIMATION_MS);
        binding.recyclerFamilyLive.setItemAnimator(itemAnimator);

        binding.familyLiveRefresh.setColorSchemeResources(
                R.color.fh_module_family,
                R.color.fh_primary,
                R.color.fh_secondary
        );
        binding.familyLiveRefresh.setOnRefreshListener(
                this::refreshFamilyLive
        );
        binding.buttonRefreshList.setOnClickListener(ignored -> {
            animateRefreshButton();
            binding.familyLiveRefresh.setRefreshing(true);
            refreshFamilyLive();
        });
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
                        renderCurrentMemberData();
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
                    renderCurrentMemberData();
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
        binding.buttonManageViewers.setOnClickListener(ignored ->
                startActivity(new Intent(
                        requireContext(),
                        FamilyJourneyActivity.class
                ).putExtra(
                        FamilyJourneyActivity.EXTRA_OPEN_PRIVACY,
                        true
                )));
        binding.buttonExtendSharing.setOnClickListener(ignored -> {
            boolean extended = LocationSharingStore.extendTimedSharing(
                    requireContext(),
                    60L * 60L * 1000L
            );
            if (extended) {
                updateSharingUi();
                scheduleSharingStatusRefresh();
                Toast.makeText(
                        requireContext(),
                        R.string.family_live_sharing_extended,
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        updateSharingUi();
        updateFilterCounts(0, 0, 0, 0);
        updateListSortState(0);
        showLoadingState(true);
        loadLocalFamilyLiveMembers();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSharingUi();
        scheduleSharingStatusRefresh();
        if (retryStartOnResume) {
            retryStartOnResume = false;
            startAfterSettingsIfReady();
        }
        if (repository != null) {
            loadLocalFamilyLiveMembers();
        }
    }

    @Override
    public void onPause() {
        cancelSharingStatusRefresh();
        super.onPause();
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

    private void renderCurrentMemberData() {
        if (binding == null) {
            return;
        }
        if (cloudDataReceived) {
            renderCloudMembers(latestCloudMembers);
        } else {
            renderLocalMembers(latestLocalMembers);
        }
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
        String normalizedQuery = memberQuery.toLowerCase(Locale.ROOT);

        int liveCount = 0;
        int staleCount = 0;
        int attentionCount = 0;

        for (FamilyLiveCloudMember member : members) {
            String availabilityReason = FamilyLiveAvailability.resolve(
                    member,
                    now,
                    LIVE_FRESHNESS_MS
            );

            if (FamilyLiveAvailability.isAvailable(availabilityReason)) {
                liveCount++;
            }
            if (FamilyLiveAvailability.NO_RECENT_UPDATE.equals(
                    availabilityReason
            )) {
                staleCount++;
            }
            if (FamilyLiveAvailability.needsAttention(
                    availabilityReason,
                    member.batteryPercentage,
                    member.charging
            )) {
                attentionCount++;
            }

            String searchable = (
                    member.displayName
                            + " "
                            + member.role
                            + " "
                            + member.placeLabel
            ).toLowerCase(Locale.ROOT);

            if (!normalizedQuery.isEmpty()
                    && !searchable.contains(normalizedQuery)) {
                continue;
            }

            if (!matchesSelectedFilter(
                    availabilityReason,
                    member.batteryPercentage,
                    member.charging
            )) {
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
                    member.online,
                    movementDisplay(member),
                    member.updatedAt
            ));
        }

        updateFilterCounts(
                members.size(),
                liveCount,
                staleCount,
                attentionCount
        );
        sortMemberModels(uiModels);
        renderMemberList(uiModels);
        renderMapMarkers();
    }

    private void renderLocalMembers(
            @NonNull List<FamilyLiveMemberData> members
    ) {
        if (binding == null || cloudDataReceived) {
            return;
        }

        List<FamilyLiveMemberUiModel> uiModels = new ArrayList<>();
        long now = System.currentTimeMillis();
        String normalizedQuery = memberQuery.toLowerCase(Locale.ROOT);

        int liveCount = 0;
        int staleCount = 0;
        int attentionCount = 0;

        for (FamilyLiveMemberData member : members) {
            String availabilityReason = resolveLocalAvailability(member, now);

            if (FamilyLiveAvailability.isAvailable(availabilityReason)) {
                liveCount++;
            }
            if (FamilyLiveAvailability.NO_RECENT_UPDATE.equals(
                    availabilityReason
            )) {
                staleCount++;
            }
            if (FamilyLiveAvailability.needsAttention(
                    availabilityReason,
                    member.batteryPercentage,
                    member.isCharging
            )) {
                attentionCount++;
            }

            String searchable = (
                    member.memberName
                            + " "
                            + member.relation
                            + " "
                            + member.currentPlaceName
            ).toLowerCase(Locale.ROOT);

            if (!normalizedQuery.isEmpty()
                    && !searchable.contains(normalizedQuery)) {
                continue;
            }

            if (!matchesSelectedFilter(
                    availabilityReason,
                    member.batteryPercentage,
                    member.isCharging
            )) {
                continue;
            }

            boolean locationVisible =
                    member.isLocationSharingEnabled && member.hasLocation;
            String location = locationVisible
                    ? member.currentPlaceName
                    : getString(R.string.family_live_location_off);

            uiModels.add(new FamilyLiveMemberUiModel(
                    member.familyMemberId,
                    "",
                    member.memberName,
                    location,
                    FamilyLiveAvailability.isAvailable(availabilityReason)
                            ? "ONLINE"
                            : "OFFLINE",
                    availabilityReason,
                    member.batteryPercentage,
                    member.isCharging,
                    member.hasInternet,
                    member.movementType,
                    member.lastUpdatedAt
            ));
        }

        updateFilterCounts(
                members.size(),
                liveCount,
                staleCount,
                attentionCount
        );
        sortMemberModels(uiModels);
        renderMemberList(uiModels);
    }

    @NonNull
    private String resolveLocalAvailability(
            @NonNull FamilyLiveMemberData member,
            long now
    ) {
        if (!member.isLocationSharingEnabled) {
            return FamilyLiveAvailability.SHARING_PAUSED;
        }
        if (!"ONLINE".equalsIgnoreCase(member.onlineStatus)) {
            return FamilyLiveAvailability.DEVICE_OFFLINE;
        }
        if (!member.hasInternet) {
            return FamilyLiveAvailability.INTERNET_UNAVAILABLE;
        }
        if (LocationFreshnessPolicy.isStale(
                member.lastUpdatedAt,
                now,
                LIVE_FRESHNESS_MS
        )) {
            return FamilyLiveAvailability.NO_RECENT_UPDATE;
        }
        if (!member.hasLocation) {
            return FamilyLiveAvailability.LOCATION_UNAVAILABLE;
        }
        return FamilyLiveAvailability.AVAILABLE;
    }

    private boolean matchesSelectedFilter(
            @NonNull String availabilityReason,
            int batteryPercentage,
            boolean charging
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
            return FamilyLiveAvailability.needsAttention(
                    availabilityReason,
                    batteryPercentage,
                    charging
            );
        }
        return true;
    }

    private void sortMemberModels(
            @NonNull List<FamilyLiveMemberUiModel> members
    ) {
        members.sort((first, second) -> {
            int priorityCompare = Integer.compare(
                    priorityOf(first),
                    priorityOf(second)
            );
            if (priorityCompare != 0) {
                return priorityCompare;
            }

            boolean firstLowBattery = FamilyLiveAvailability.isLowBattery(
                    first.getBatteryPercentage(),
                    first.isCharging()
            );
            boolean secondLowBattery = FamilyLiveAvailability.isLowBattery(
                    second.getBatteryPercentage(),
                    second.isCharging()
            );
            if (firstLowBattery && secondLowBattery) {
                int batteryCompare = Integer.compare(
                        first.getBatteryPercentage(),
                        second.getBatteryPercentage()
                );
                if (batteryCompare != 0) {
                    return batteryCompare;
                }
            }

            if (selectedFilterId == R.id.chipFamilyLiveLive) {
                int travellingCompare = Boolean.compare(
                        isTravelling(second),
                        isTravelling(first)
                );
                if (travellingCompare != 0) {
                    return travellingCompare;
                }
            }

            boolean firstNeedsAttention =
                    FamilyLiveAvailability.needsAttention(
                            first.getAvailabilityReason(),
                            first.getBatteryPercentage(),
                            first.isCharging()
                    );
            boolean secondNeedsAttention =
                    FamilyLiveAvailability.needsAttention(
                            second.getAvailabilityReason(),
                            second.getBatteryPercentage(),
                            second.isCharging()
                    );

            int timeCompare;
            if (firstNeedsAttention || secondNeedsAttention
                    || selectedFilterId == R.id.chipFamilyLiveStale) {
                timeCompare = Long.compare(
                        urgencyTime(first.getLastUpdatedTime()),
                        urgencyTime(second.getLastUpdatedTime())
                );
            } else {
                timeCompare = Long.compare(
                        second.getLastUpdatedTime(),
                        first.getLastUpdatedTime()
                );
            }
            if (timeCompare != 0) {
                return timeCompare;
            }

            return String.CASE_INSENSITIVE_ORDER.compare(
                    first.getMemberName(),
                    second.getMemberName()
            );
        });
    }

    private int priorityOf(@NonNull FamilyLiveMemberUiModel member) {
        String reason = FamilyLiveAvailability.normalize(
                member.getAvailabilityReason()
        );

        if (FamilyLiveAvailability.isCritical(reason)) {
            return 0;
        }
        if (FamilyLiveAvailability.isLowBattery(
                member.getBatteryPercentage(),
                member.isCharging()
        )) {
            return 1;
        }
        if (FamilyLiveAvailability.isWarning(reason)) {
            return 2;
        }
        if (FamilyLiveAvailability.isPaused(reason)) {
            return 3;
        }
        if (FamilyLiveAvailability.isAvailable(reason)
                && isTravelling(member)) {
            return 4;
        }
        if (FamilyLiveAvailability.isAvailable(reason)) {
            return 5;
        }
        return 6;
    }

    private boolean isTravelling(
            @NonNull FamilyLiveMemberUiModel member
    ) {
        String movement = member.getMovementType();
        return movement != null
                && movement.toLowerCase(Locale.ROOT).contains("travell");
    }

    private long urgencyTime(long updatedTime) {
        return updatedTime <= 0L ? Long.MIN_VALUE : updatedTime;
    }

    private void updateFilterCounts(
            int total,
            int live,
            int stale,
            int attention
    ) {
        if (binding == null) {
            return;
        }
        binding.chipFamilyLiveAll.setText(getString(
                R.string.family_live_filter_all_count,
                total
        ));
        binding.chipFamilyLiveLive.setText(getString(
                R.string.family_live_filter_live_count,
                live
        ));
        binding.chipFamilyLiveStale.setText(getString(
                R.string.family_live_filter_stale_count,
                stale
        ));
        binding.chipFamilyLiveAttention.setText(getString(
                R.string.family_live_filter_attention_count,
                attention
        ));
    }

    private void updateListSortState(int shownCount) {
        if (binding == null) {
            return;
        }

        int labelRes;
        int foregroundRes;
        int backgroundRes;

        if (selectedFilterId == R.id.chipFamilyLiveLive) {
            labelRes = R.string.family_live_sort_live;
            foregroundRes = R.color.fh_success;
            backgroundRes = R.color.fh_success_container;
        } else if (selectedFilterId == R.id.chipFamilyLiveStale) {
            labelRes = R.string.family_live_sort_stale;
            foregroundRes = R.color.fh_warning;
            backgroundRes = R.color.fh_warning_container;
        } else if (selectedFilterId == R.id.chipFamilyLiveAttention) {
            labelRes = R.string.family_live_sort_attention;
            foregroundRes = R.color.fh_error;
            backgroundRes = R.color.fh_error_container;
        } else {
            labelRes = R.string.family_live_sort_priority;
            foregroundRes = R.color.fh_module_family;
            backgroundRes = R.color.fh_module_family_container;
        }

        binding.tvListSortState.setText(getString(
                R.string.family_live_sort_result_format,
                getString(labelRes),
                shownCount
        ));
        binding.tvListSortState.setTextColor(
                ContextCompat.getColor(requireContext(), foregroundRes)
        );
        ViewCompat.setBackgroundTintList(
                binding.tvListSortState,
                ColorStateList.valueOf(ContextCompat.getColor(
                        requireContext(),
                        backgroundRes
                ))
        );
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
        if (familyLiveAdapter == null
                || familyLiveAdapter.getItemCount() == 0) {
            showLoadingState(true);
        }
        repository.stopObservingCloudMembers();
        loadLocalFamilyLiveMembers();
        observeCloudMembers();
    }

    private void observeCloudMembers() {
        if (repository == null) {
            if (binding != null) {
                binding.familyLiveRefresh.setRefreshing(false);
                showLoadingState(false);
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
                    showLoadingState(false);
                    if (familyLiveAdapter != null
                            && familyLiveAdapter.getItemCount() == 0) {
                        renderMemberList(new ArrayList<>());
                    }
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

        if (binding.familyLiveLoadingState.getVisibility() == View.VISIBLE) {
            binding.recyclerFamilyLive.setVisibility(View.GONE);
            binding.familyLiveEmptyState.setVisibility(View.GONE);
            return;
        }

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
        binding.familyLiveLoadingState.setVisibility(View.GONE);
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
            boolean stale = LocationFreshnessPolicy.isStale(
                    member.updatedAt,
                    now,
                    LIVE_FRESHNESS_MS
            );
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
                        (dialog, which) -> showSharingDurationPicker()
                )
                .show();
    }

    private void showSharingDurationPicker() {
        String[] options = getResources().getStringArray(
                R.array.family_live_sharing_durations
        );
        long[] durations = {
                60L * 60L * 1000L,
                8L * 60L * 60L * 1000L,
                24L * 60L * 60L * 1000L,
                0L
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.family_live_sharing_duration_title)
                .setSingleChoiceItems(options, 1, (dialog, which) -> {
                    selectedSharingDurationMs = durations[which];
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.family_live_start_sharing,
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
        LocationSharingStore.prepareSharingDuration(
                requireContext(),
                selectedSharingDurationMs
        );
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
        if (enabled) {
            long expiresAt = LocationSharingStore.sharingExpiresAt(
                    requireContext()
            );
            if (expiresAt <= 0L) {
                binding.tvSharingDetail.setText(
                        R.string.family_live_sharing_until_stopped_detail
                );
            } else {
                long remainingMs = Math.max(
                        0L,
                        expiresAt - System.currentTimeMillis()
                );
                long remainingMinutes = Math.max(
                        1L,
                        (remainingMs + 59_999L) / 60_000L
                );
                if (remainingMinutes >= 60L) {
                    long hours = remainingMinutes / 60L;
                    long minutes = remainingMinutes % 60L;
                    binding.tvSharingDetail.setText(getString(
                            R.string.family_live_sharing_remaining_hours,
                            hours,
                            minutes
                    ));
                } else {
                    binding.tvSharingDetail.setText(getString(
                            R.string.family_live_sharing_remaining_minutes,
                            remainingMinutes
                    ));
                }
            }
        } else {
            binding.tvSharingDetail.setText(
                    R.string.family_live_sharing_off_detail
            );
        }
        binding.buttonLocationSharing.setText(enabled
                ? R.string.family_live_stop_sharing
                : R.string.family_live_start_sharing);
        binding.buttonExtendSharing.setVisibility(
                enabled && LocationSharingStore.sharingExpiresAt(
                        requireContext()
                ) > 0L ? View.VISIBLE : View.GONE
        );
        updateSharingActivity(enabled);
    }

    private void updateSharingActivity(boolean enabled) {
        long eventAt = enabled
                ? LocationSharingStore.lastSharingStartedAt(requireContext())
                : LocationSharingStore.lastSharingEndedAt(requireContext());
        if (eventAt <= 0L) {
            binding.tvSharingActivity.setText(
                    R.string.family_live_sharing_activity_none
            );
            return;
        }
        String eventTime = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
        ).format(new Date(eventAt));
        if (enabled) {
            binding.tvSharingActivity.setText(getString(
                    R.string.family_live_sharing_activity_started,
                    eventTime
            ));
            return;
        }
        int reason = LocationSharingStore.lastSharingEndReason(
                requireContext()
        );
        binding.tvSharingActivity.setText(getString(
                reason == LocationSharingStore.END_REASON_EXPIRED
                        ? R.string.family_live_sharing_activity_expired
                        : R.string.family_live_sharing_activity_stopped,
                eventTime
        ));
    }

    private void scheduleSharingStatusRefresh() {
        if (binding == null) {
            return;
        }
        binding.getRoot().removeCallbacks(sharingStatusRefresh);
        if (LocationSharingStore.isSharingEnabled(requireContext())) {
            binding.getRoot().postDelayed(
                    sharingStatusRefresh,
                    SHARING_STATUS_REFRESH_MS
            );
        }
    }

    private void cancelSharingStatusRefresh() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(sharingStatusRefresh);
        }
    }

    private void loadLocalFamilyLiveMembers() {
        if (repository == null) {
            return;
        }
        repository.loadMemberStatuses(memberStatuses -> {
            if (binding == null || familyLiveAdapter == null) {
                return;
            }
            latestLocalMembers = new ArrayList<>(memberStatuses);
            if (!cloudDataReceived) {
                renderLocalMembers(latestLocalMembers);
            }
        });
    }

    private void renderMemberList(
            @NonNull List<FamilyLiveMemberUiModel> uiModels
    ) {
        if (binding == null || familyLiveAdapter == null) {
            return;
        }

        showLoadingState(false);
        familyLiveAdapter.submitList(uiModels);
        binding.tvMemberCount.setText(getString(
                R.string.family_live_member_count,
                uiModels.size()
        ));
        updateListSortState(uiModels.size());
        updateEmptyStateCopy();

        boolean isEmpty = uiModels.isEmpty();
        boolean shouldAnimate = lastRenderedCount != uiModels.size();
        lastRenderedCount = uiModels.size();

        if (mapViewSelected) {
            binding.recyclerFamilyLive.setVisibility(View.GONE);
            binding.familyLiveEmptyState.setVisibility(View.GONE);
            return;
        }

        binding.recyclerFamilyLive.setVisibility(
                isEmpty ? View.GONE : View.VISIBLE
        );
        binding.familyLiveEmptyState.setVisibility(
                isEmpty ? View.VISIBLE : View.GONE
        );

        if (shouldAnimate) {
            animateVisibleContent(isEmpty
                    ? binding.familyLiveEmptyState
                    : binding.recyclerFamilyLive);
        }
    }

    private void showLoadingState(boolean show) {
        if (binding == null) {
            return;
        }

        binding.familyLiveLoadingState.setVisibility(
                show ? View.VISIBLE : View.GONE
        );

        if (!show) {
            return;
        }

        binding.recyclerFamilyLive.setVisibility(View.GONE);
        binding.familyLiveEmptyState.setVisibility(View.GONE);
        animateVisibleContent(binding.familyLiveLoadingState);
    }

    private void updateEmptyStateCopy() {
        if (binding == null) {
            return;
        }

        if (!memberQuery.isEmpty()) {
            binding.tvFamilyLiveEmptyTitle.setText(
                    R.string.family_live_empty_search_title
            );
            binding.tvFamilyLiveEmptyDetail.setText(getString(
                    R.string.family_live_empty_search_detail,
                    memberQuery
            ));
            return;
        }

        if (selectedFilterId == R.id.chipFamilyLiveLive) {
            binding.tvFamilyLiveEmptyTitle.setText(
                    R.string.family_live_empty_live_title
            );
            binding.tvFamilyLiveEmptyDetail.setText(
                    R.string.family_live_empty_live_detail
            );
        } else if (selectedFilterId == R.id.chipFamilyLiveStale) {
            binding.tvFamilyLiveEmptyTitle.setText(
                    R.string.family_live_empty_stale_title
            );
            binding.tvFamilyLiveEmptyDetail.setText(
                    R.string.family_live_empty_stale_detail
            );
        } else if (selectedFilterId == R.id.chipFamilyLiveAttention) {
            binding.tvFamilyLiveEmptyTitle.setText(
                    R.string.family_live_empty_attention_title
            );
            binding.tvFamilyLiveEmptyDetail.setText(
                    R.string.family_live_empty_attention_detail
            );
        } else {
            binding.tvFamilyLiveEmptyTitle.setText(
                    R.string.family_live_empty_title
            );
            binding.tvFamilyLiveEmptyDetail.setText(
                    R.string.family_live_empty_detail
            );
        }
    }

    private void animateVisibleContent(@NonNull View view) {
        float distance = 8F * getResources()
                .getDisplayMetrics()
                .density;
        view.animate().cancel();
        view.setAlpha(0F);
        view.setTranslationY(distance);
        view.animate()
                .alpha(1F)
                .translationY(0F)
                .setDuration(CONTENT_ANIMATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateRefreshButton() {
        if (binding == null) {
            return;
        }
        binding.buttonRefreshList.animate().cancel();
        binding.buttonRefreshList.animate()
                .rotationBy(360F)
                .setDuration(420L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (binding != null) {
                        binding.buttonRefreshList.setRotation(0F);
                    }
                })
                .start();
    }

    @Override
    public void onDestroyView() {
        cancelSharingStatusRefresh();
        if (repository != null) {
            repository.close();
            repository = null;
        }
        binding.recyclerFamilyLive.setAdapter(null);
        familyLiveAdapter = null;
        googleMap = null;
        latestCloudMembers = new ArrayList<>();
        latestLocalMembers = new ArrayList<>();
        binding = null;
        super.onDestroyView();
    }
}
