package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.data.repository.SafePlaceRepository;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dedicated lifecycle-safe map for authorised Family Live memberships.
 *
 * The map uses a compact Office 365-inspired control surface and renders a
 * structured member card directly above the selected marker instead of a
 * plain paragraph at the bottom of the screen.
 */
public final class FamilyMapActivity extends AppCompatActivity {

    public static final String EXTRA_FOCUS_MEMBER_UID =
            "com.tridev.familyhub.extra.FOCUS_MEMBER_UID";

    private static final long LIVE_FRESHNESS_MS = 3L * 60L * 1000L;
    private static final String STATE_CAMERA = "family_map_camera";
    private static final String STATE_MAP_TYPE = "family_map_type";
    private static final String STATE_QUERY = "family_map_query";
    private static final String STATE_SELECTED_MEMBER =
            "family_map_selected_member";
    private static final String STATE_TRAFFIC = "family_map_traffic";

    private final Map<Marker, FamilyLiveCloudMember> markerMembers =
            new HashMap<>();
    private final Map<String, Marker> memberMarkers = new HashMap<>();

    @NonNull
    private List<FamilyLiveCloudMember> members = new ArrayList<>();
    @NonNull
    private List<SafePlace> safePlaces = new ArrayList<>();
    @NonNull
    private String query = "";

    @Nullable
    private GoogleMap map;
    @Nullable
    private Circle accuracyCircle;
    @Nullable
    private Marker selectedMarker;
    @Nullable
    private CameraPosition restoredCamera;
    @Nullable
    private String selectedMemberUid;
    @Nullable
    private FamilyLiveRepository familyRepository;
    @Nullable
    private SafePlaceRepository safePlaceRepository;

    private View loading;
    private View stateCard;
    private View topPanel;
    private View controlRail;
    private View bottomPanel;
    private TextView stateText;
    private MaterialButton typeButton;
    private MaterialButton trafficButton;

    private boolean mapReady;
    private boolean dataReady;
    private boolean fitOnNextRender = true;
    private boolean trafficEnabled;
    private boolean pendingIntentFocus;
    private int mapType = GoogleMap.MAP_TYPE_NORMAL;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String memberUid
    ) {
        return new Intent(context, FamilyMapActivity.class)
                .putExtra(EXTRA_FOCUS_MEMBER_UID, memberUid);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_family_map);

        View root = findViewById(R.id.familyMapRoot);
        topPanel = findViewById(R.id.familyMapTopPanel);
        controlRail = findViewById(R.id.familyMapControlRail);
        bottomPanel = findViewById(R.id.familyMapBottomPanel);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            ViewGroup.MarginLayoutParams topParams =
                    (ViewGroup.MarginLayoutParams) topPanel.getLayoutParams();
            topParams.topMargin = bars.top
                    + getResources().getDimensionPixelSize(R.dimen.space_8);
            topPanel.setLayoutParams(topParams);
            view.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight,
                                        oldBottom) -> applyMapPadding());

        loading = findViewById(R.id.familyMapLoading);
        stateCard = findViewById(R.id.familyMapStateCard);
        stateText = findViewById(R.id.textFamilyMapState);
        typeButton = findViewById(R.id.buttonFamilyMapType);
        trafficButton = findViewById(R.id.buttonFamilyMapTraffic);

        restoreUiState(savedInstanceState);
        restoreIntentFocus(savedInstanceState);
        bindControls();
        bindSearch();

        familyRepository = new FamilyLiveRepository(this);
        safePlaceRepository = new SafePlaceRepository(this);
        safePlaceRepository.loadAll(places -> {
            safePlaces = new ArrayList<>(places);
            renderMarkers();
        });

        SupportMapFragment fragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(
                        R.id.familyMapHost
                );
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.familyMapHost, fragment)
                    .commitNow();
        }

        fragment.getMapAsync(this::configureMap);
    }

    private void restoreIntentFocus(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            return;
        }
        String requestedUid = getIntent().getStringExtra(
                EXTRA_FOCUS_MEMBER_UID
        );
        if (requestedUid != null && !requestedUid.trim().isEmpty()) {
            selectedMemberUid = requestedUid;
            pendingIntentFocus = true;
            fitOnNextRender = false;
        }
    }

    private void bindControls() {
        findViewById(R.id.buttonFamilyMapBack).setOnClickListener(
                ignored -> getOnBackPressedDispatcher().onBackPressed()
        );
        findViewById(R.id.buttonFamilyMapRetry).setOnClickListener(
                ignored -> restartMemberStream()
        );
        findViewById(R.id.buttonFamilyMapFit).setOnClickListener(
                ignored -> fitAllMembers()
        );
        findViewById(R.id.buttonFamilyMapRecenter).setOnClickListener(
                ignored -> focusCurrentUser()
        );
        typeButton.setOnClickListener(ignored -> cycleMapType());
        trafficButton.setOnClickListener(ignored -> toggleTraffic());
    }

    private void bindSearch() {
        TextInputEditText search = findViewById(
                R.id.inputFamilyMapSearch
        );
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
                query = s == null ? "" : s.toString().trim();
                renderMarkers();
                focusMatchingMember();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        if (!query.isEmpty()) {
            search.setText(query);
            search.setSelection(search.length());
        }
    }

    private void configureMap(@NonNull GoogleMap googleMap) {
        map = googleMap;
        mapReady = true;

        googleMap.setMapType(mapType);
        googleMap.setTrafficEnabled(trafficEnabled);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setMapToolbarEnabled(true);
        googleMap.getUiSettings().setIndoorLevelPickerEnabled(true);

        googleMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Nullable
            @Override
            public View getInfoWindow(@NonNull Marker marker) {
                FamilyLiveCloudMember member = markerMembers.get(marker);
                return member == null
                        ? null
                        : createMemberInfoWindow(member);
            }

            @Nullable
            @Override
            public View getInfoContents(@NonNull Marker marker) {
                return null;
            }
        });

        googleMap.setOnMarkerClickListener(marker -> {
            FamilyLiveCloudMember member = markerMembers.get(marker);
            if (member == null) {
                return false;
            }
            selectMember(marker, member);
            return true;
        });

        googleMap.setOnMapClickListener(ignored -> clearMemberSelection());

        updateMapControlLabels();
        enableMyLocationIfAllowed();
        applyMapPadding();
        renderMarkers();

        if (restoredCamera != null) {
            googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                    restoredCamera
            ));
            restoredCamera = null;
            fitOnNextRender = false;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MAP_TYPE, mapType);
        outState.putBoolean(STATE_TRAFFIC, trafficEnabled);
        outState.putString(STATE_QUERY, query);
        outState.putString(STATE_SELECTED_MEMBER, selectedMemberUid);
        if (map != null) {
            outState.putParcelable(
                    STATE_CAMERA,
                    map.getCameraPosition()
            );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        restartMemberStream();
    }

    @Override
    protected void onStop() {
        if (familyRepository != null) {
            familyRepository.stopObservingCloudMembers();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (familyRepository != null) {
            familyRepository.close();
            familyRepository = null;
        }
        if (safePlaceRepository != null) {
            safePlaceRepository.close();
            safePlaceRepository = null;
        }
        super.onDestroy();
    }

    private void restartMemberStream() {
        if (familyRepository == null) {
            return;
        }
        familyRepository.stopObservingCloudMembers();
        dataReady = false;
        showLoading();
        familyRepository.observeCloudMembers(received -> {
            dataReady = true;
            members = new ArrayList<>(received);
            renderMarkers();
        }, error -> {
            dataReady = true;
            showState(isNetworkAvailable()
                    ? R.string.family_map_firebase_unavailable
                    : R.string.family_map_internet_unavailable);
        });
    }

    private void renderMarkers() {
        if (!mapReady || map == null) {
            return;
        }

        map.clear();
        markerMembers.clear();
        memberMarkers.clear();
        accuracyCircle = null;
        selectedMarker = null;

        renderSafePlaces();

        long now = System.currentTimeMillis();
        int authorised = members.size();
        int sharing = 0;
        int shown = 0;
        String normalized = query.toLowerCase(Locale.ROOT);

        for (FamilyLiveCloudMember member : members) {
            if (!member.sharingEnabled) {
                continue;
            }
            sharing++;

            if (!member.hasLocation || !validCoordinates(
                    member.latitude,
                    member.longitude
            )) {
                continue;
            }

            String searchable = (
                    member.displayName
                            + " "
                            + member.role
                            + " "
                            + member.placeLabel
            ).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !searchable.contains(normalized)) {
                continue;
            }

            boolean stale = member.updatedAt <= 0L
                    || now - member.updatedAt > LIVE_FRESHNESS_MS;
            boolean current = isCurrentUser(member.uid);
            LatLng position = new LatLng(
                    member.latitude,
                    member.longitude
            );

            Marker marker = map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(displayName(member))
                    .snippet(getString(stale
                            ? R.string.family_live_map_marker_stale
                            : R.string.family_live_map_marker_live))
                    .alpha(stale ? 0.68F : 1F)
                    .icon(BitmapDescriptorFactory.defaultMarker(current
                            ? BitmapDescriptorFactory.HUE_AZURE
                            : stale
                            ? BitmapDescriptorFactory.HUE_ORANGE
                            : BitmapDescriptorFactory.HUE_GREEN)));

            if (marker == null) {
                continue;
            }

            markerMembers.put(marker, member);
            memberMarkers.put(member.uid, marker);
            if (member.uid.equals(selectedMemberUid)) {
                marker.setIcon(BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_RED
                ));
                selectedMarker = marker;
            }
            shown++;
        }

        restoreSelectedMemberFocus();
        loading.setVisibility(View.GONE);

        if (!dataReady) {
            showLoading();
        } else if (authorised == 0) {
            showState(R.string.family_map_no_authorised);
        } else if (sharing == 0) {
            showState(R.string.family_map_none_sharing);
        } else if (shown == 0 && !query.isEmpty()) {
            showState(R.string.family_map_no_search_result);
        } else if (shown == 0) {
            showState(R.string.family_live_map_no_locations);
        } else if (!isGpsEnabled()) {
            showState(R.string.family_map_gps_off);
        } else {
            stateCard.setVisibility(View.GONE);
            if (fitOnNextRender) {
                fitOnNextRender = false;
                fitAllMembers();
            }
        }
    }

    private void renderSafePlaces() {
        if (map == null) {
            return;
        }

        for (SafePlace place : safePlaces) {
            if (!validCoordinates(place.latitude, place.longitude)
                    || place.radiusMeters < 1F) {
                continue;
            }

            LatLng position = new LatLng(
                    place.latitude,
                    place.longitude
            );
            float hue = place.alertsEnabled
                    ? BitmapDescriptorFactory.HUE_VIOLET
                    : BitmapDescriptorFactory.HUE_ROSE;

            map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(place.name)
                    .snippet(getString(place.alertsEnabled
                            ? R.string.family_map_safe_place_enabled
                            : R.string.family_map_safe_place_disabled))
                    .alpha(place.alertsEnabled ? 0.9F : 0.45F)
                    .icon(BitmapDescriptorFactory.defaultMarker(hue)));

            int stroke = place.alertsEnabled
                    ? Color.rgb(107, 79, 161)
                    : Color.GRAY;
            map.addCircle(new CircleOptions()
                    .center(position)
                    .radius(place.radiusMeters)
                    .strokeColor(stroke)
                    .fillColor(Color.argb(
                            place.alertsEnabled ? 35 : 18,
                            Color.red(stroke),
                            Color.green(stroke),
                            Color.blue(stroke)
                    ))
                    .strokeWidth(place.alertsEnabled ? 3F : 1.5F));
        }
    }

    private void selectMember(
            @NonNull Marker marker,
            @NonNull FamilyLiveCloudMember member
    ) {
        focusMember(marker, member, true);
    }

    private void focusMember(
            @NonNull Marker marker,
            @NonNull FamilyLiveCloudMember member,
            boolean moveCamera
    ) {
        if (map == null) {
            return;
        }

        restoreSelectedMarkerAppearance();
        selectedMemberUid = member.uid;
        selectedMarker = marker;
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(
                BitmapDescriptorFactory.HUE_RED
        ));

        if (accuracyCircle != null) {
            accuracyCircle.remove();
        }

        if (moveCamera) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    marker.getPosition(),
                    16.5F
            ));
        }

        if (member.accuracy > 0D
                && member.accuracy <= 5000D
                && Double.isFinite(member.accuracy)) {
            accuracyCircle = map.addCircle(new CircleOptions()
                    .center(marker.getPosition())
                    .radius(member.accuracy)
                    .strokeColor(ContextCompat.getColor(
                            this,
                            R.color.fh_primary
                    ))
                    .fillColor(Color.argb(30, 15, 108, 189))
                    .strokeWidth(3F));
        }

        marker.showInfoWindow();
    }

    private void clearMemberSelection() {
        restoreSelectedMarkerAppearance();
        if (selectedMarker != null) {
            selectedMarker.hideInfoWindow();
        }
        if (accuracyCircle != null) {
            accuracyCircle.remove();
            accuracyCircle = null;
        }
        selectedMarker = null;
        selectedMemberUid = null;
    }

    private void restoreSelectedMemberFocus() {
        if (selectedMemberUid == null || map == null) {
            return;
        }

        Marker marker = memberMarkers.get(selectedMemberUid);
        FamilyLiveCloudMember member = marker == null
                ? null
                : markerMembers.get(marker);

        if (marker == null || member == null) {
            if (pendingIntentFocus && dataReady) {
                pendingIntentFocus = false;
                showState(R.string.family_map_selected_unavailable);
            }
            return;
        }

        boolean moveCamera = pendingIntentFocus && restoredCamera == null;
        pendingIntentFocus = false;
        focusMember(marker, member, moveCamera);
    }

    @NonNull
    private View createMemberInfoWindow(
            @NonNull FamilyLiveCloudMember member
    ) {
        View card = LayoutInflater.from(this).inflate(
                R.layout.view_family_map_member_info,
                null,
                false
        );

        String availabilityReason = FamilyLiveAvailability.resolve(
                member,
                System.currentTimeMillis(),
                LIVE_FRESHNESS_MS
        );

        TextView name = card.findViewById(R.id.textMapInfoName);
        TextView role = card.findViewById(R.id.textMapInfoRole);
        TextView status = card.findViewById(R.id.textMapInfoStatus);
        TextView place = card.findViewById(R.id.textMapInfoPlace);
        TextView updated = card.findViewById(R.id.textMapInfoUpdated);
        TextView accuracy = card.findViewById(R.id.textMapInfoAccuracy);
        TextView battery = card.findViewById(R.id.textMapInfoBattery);
        TextView network = card.findViewById(R.id.textMapInfoNetwork);
        TextView movement = card.findViewById(R.id.textMapInfoMovement);

        name.setText(displayName(member));
        role.setText(getString(
                R.string.family_map_info_role,
                humanizeRole(member.role)
        ));
        status.setText(getString(FamilyLiveAvailability.labelRes(
                availabilityReason
        )));
        styleStatusChip(status, availabilityReason);

        String placeValue = member.placeLabel.trim().isEmpty()
                ? getString(R.string.family_live_location_unavailable)
                : member.placeLabel.trim();
        place.setText(getString(
                R.string.family_map_info_place,
                placeValue
        ));

        String updatedValue = member.updatedAt <= 0L
                ? getString(R.string.family_live_update_unavailable)
                : DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                ).format(new Date(member.updatedAt));
        updated.setText(getString(
                R.string.family_map_info_updated,
                updatedValue
        ));

        long roundedAccuracy = member.accuracy > 0D
                && Double.isFinite(member.accuracy)
                ? Math.round(member.accuracy)
                : 0L;
        accuracy.setText(roundedAccuracy > 0L
                ? getString(
                        R.string.family_map_info_accuracy,
                        roundedAccuracy
                )
                : getString(
                        R.string.family_map_info_accuracy,
                        0L
                ));

        String batteryValue;
        if (member.batteryPercentage < 0) {
            batteryValue = getString(R.string.family_live_unknown);
        } else if (member.charging) {
            batteryValue = getString(
                    R.string.family_map_info_battery_charging,
                    member.batteryPercentage
            );
        } else {
            batteryValue = getString(
                    R.string.family_map_info_battery_not_charging,
                    member.batteryPercentage
            );
        }
        battery.setText(getString(
                R.string.family_map_info_battery,
                batteryValue
        ));

        network.setText(getString(
                R.string.family_map_info_network,
                networkLabel(member, availabilityReason)
        ));
        movement.setText(getString(
                R.string.family_map_info_movement,
                movementLabel(member)
        ));

        return card;
    }

    private void styleStatusChip(
            @NonNull TextView status,
            @NonNull String availabilityReason
    ) {
        int backgroundColor;
        int textColor;

        if (FamilyLiveAvailability.isCritical(availabilityReason)) {
            backgroundColor = R.color.family_map_critical_container;
            textColor = R.color.family_map_critical;
        } else if (FamilyLiveAvailability.isWarning(availabilityReason)) {
            backgroundColor = R.color.family_map_warning_container;
            textColor = R.color.family_map_warning;
        } else {
            backgroundColor = R.color.family_map_live_container;
            textColor = R.color.family_map_live;
        }

        Drawable background = status.getBackground();
        if (background != null) {
            background = background.mutate();
            background.setTint(ContextCompat.getColor(
                    this,
                    backgroundColor
            ));
            status.setBackground(background);
        }
        status.setTextColor(ContextCompat.getColor(this, textColor));
    }

    @NonNull
    private String networkLabel(
            @NonNull FamilyLiveCloudMember member,
            @NonNull String availabilityReason
    ) {
        int connectionState = FamilyLiveAvailability.connectionState(
                availabilityReason,
                member.online
        );
        if (connectionState == FamilyLiveAvailability.CONNECTION_CONNECTED) {
            return getString(R.string.family_live_network_connected);
        }
        if (connectionState == FamilyLiveAvailability.CONNECTION_OFFLINE) {
            return getString(R.string.family_live_network_off);
        }
        return getString(R.string.family_live_network_unknown);
    }

    @NonNull
    private String movementLabel(@NonNull FamilyLiveCloudMember member) {
        String movement = movement(member);
        if (member.speedMetersPerSecond >= 0.3D
                && Double.isFinite(member.speedMetersPerSecond)) {
            return getString(
                    R.string.family_live_movement_speed,
                    movement,
                    Math.round(member.speedMetersPerSecond * 3.6D)
            );
        }
        return movement;
    }

    private void fitAllMembers() {
        if (map == null || memberMarkers.isEmpty()) {
            return;
        }

        clearMemberSelection();

        if (memberMarkers.size() == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    memberMarkers.values().iterator().next().getPosition(),
                    15F
            ));
            return;
        }

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (Marker marker : memberMarkers.values()) {
            bounds.include(marker.getPosition());
        }

        findViewById(R.id.familyMapHost).post(() -> {
            if (map != null && memberMarkers.size() > 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                        bounds.build(),
                        112
                ));
            }
        });
    }

    private void focusCurrentUser() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            showState(R.string.family_map_current_unavailable);
            return;
        }

        Marker marker = memberMarkers.get(
                FirebaseAuth.getInstance().getCurrentUser().getUid()
        );
        if (marker == null) {
            showState(R.string.family_map_current_unavailable);
            return;
        }

        FamilyLiveCloudMember member = markerMembers.get(marker);
        if (member != null) {
            selectMember(marker, member);
        }
    }

    private void focusMatchingMember() {
        if (query.isEmpty() || memberMarkers.size() != 1) {
            return;
        }

        Marker marker = memberMarkers.values().iterator().next();
        FamilyLiveCloudMember member = markerMembers.get(marker);
        if (member != null) {
            focusMember(marker, member, true);
        }
    }

    private void cycleMapType() {
        if (map == null) {
            return;
        }

        if (mapType == GoogleMap.MAP_TYPE_NORMAL) {
            mapType = GoogleMap.MAP_TYPE_SATELLITE;
        } else if (mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            mapType = GoogleMap.MAP_TYPE_HYBRID;
        } else if (mapType == GoogleMap.MAP_TYPE_HYBRID) {
            mapType = GoogleMap.MAP_TYPE_TERRAIN;
        } else {
            mapType = GoogleMap.MAP_TYPE_NORMAL;
        }

        map.setMapType(mapType);
        updateMapControlLabels();
    }

    private void toggleTraffic() {
        if (map == null) {
            return;
        }
        trafficEnabled = !map.isTrafficEnabled();
        map.setTrafficEnabled(trafficEnabled);
        updateMapControlLabels();
    }

    private void restoreUiState(@Nullable Bundle state) {
        if (state == null) {
            return;
        }

        int restoredType = state.getInt(
                STATE_MAP_TYPE,
                GoogleMap.MAP_TYPE_NORMAL
        );
        if (restoredType == GoogleMap.MAP_TYPE_NORMAL
                || restoredType == GoogleMap.MAP_TYPE_SATELLITE
                || restoredType == GoogleMap.MAP_TYPE_HYBRID
                || restoredType == GoogleMap.MAP_TYPE_TERRAIN) {
            mapType = restoredType;
        }

        trafficEnabled = state.getBoolean(STATE_TRAFFIC, false);
        query = valueOrEmpty(state.getString(STATE_QUERY));
        selectedMemberUid = state.getString(STATE_SELECTED_MEMBER);
        restoredCamera = state.getParcelable(STATE_CAMERA);
        fitOnNextRender = restoredCamera == null;
    }

    private void updateMapControlLabels() {
        if (typeButton == null || trafficButton == null) {
            return;
        }

        if (mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            typeButton.setText(R.string.family_map_satellite);
        } else if (mapType == GoogleMap.MAP_TYPE_HYBRID) {
            typeButton.setText(R.string.family_map_hybrid);
        } else if (mapType == GoogleMap.MAP_TYPE_TERRAIN) {
            typeButton.setText(R.string.family_map_terrain);
        } else {
            typeButton.setText(R.string.family_map_normal);
        }

        trafficButton.setText(trafficEnabled
                ? R.string.family_map_traffic_on
                : R.string.family_map_traffic_off);
        trafficButton.setChecked(trafficEnabled);
    }

    private void restoreSelectedMarkerAppearance() {
        if (selectedMarker == null) {
            return;
        }

        FamilyLiveCloudMember previous = markerMembers.get(selectedMarker);
        if (previous == null) {
            selectedMarker = null;
            return;
        }

        boolean stale = previous.updatedAt <= 0L
                || System.currentTimeMillis() - previous.updatedAt
                > LIVE_FRESHNESS_MS;
        boolean current = isCurrentUser(previous.uid);

        selectedMarker.setIcon(BitmapDescriptorFactory.defaultMarker(
                current
                        ? BitmapDescriptorFactory.HUE_AZURE
                        : stale
                        ? BitmapDescriptorFactory.HUE_ORANGE
                        : BitmapDescriptorFactory.HUE_GREEN
        ));
    }

    @NonNull
    private static String valueOrEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private boolean isCurrentUser(@NonNull String uid) {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                && uid.equals(
                FirebaseAuth.getInstance().getCurrentUser().getUid()
        );
    }

    private void enableMyLocationIfAllowed() {
        if (map == null) {
            return;
        }

        boolean fine = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (fine || coarse) {
            try {
                map.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {
                showState(R.string.family_map_permission_denied);
            }
        }
    }

    private void applyMapPadding() {
        if (map == null
                || topPanel == null
                || controlRail == null
                || bottomPanel == null) {
            return;
        }

        int edge = getResources().getDimensionPixelSize(R.dimen.space_12);
        int rightPadding = controlRail.getWidth() + (edge * 2);
        int bottomPadding = bottomPanel.getHeight() + (edge * 2);

        map.setPadding(
                edge,
                topPanel.getBottom() + edge,
                rightPadding,
                bottomPadding
        );
    }

    private void showLoading() {
        loading.setVisibility(View.VISIBLE);
        stateCard.setVisibility(View.GONE);
    }

    private void showState(int message) {
        loading.setVisibility(View.GONE);
        stateText.setText(message);
        stateCard.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager manager = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || manager.getActiveNetwork() == null) {
            return false;
        }

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(
                manager.getActiveNetwork()
        );
        return capabilities != null
                && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
        );
    }

    private boolean isGpsEnabled() {
        LocationManager manager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
        );
    }

    private static boolean validCoordinates(
            double latitude,
            double longitude
    ) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }

    @NonNull
    private String displayName(@NonNull FamilyLiveCloudMember member) {
        return member.displayName.trim().isEmpty()
                ? getString(R.string.family_account_member_fallback)
                : member.displayName.trim();
    }

    @NonNull
    private String humanizeRole(@Nullable String role) {
        if (role == null || role.trim().isEmpty()) {
            return getString(R.string.family_live_unknown);
        }

        String[] parts = role.trim()
                .toLowerCase(Locale.ROOT)
                .split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(part.substring(0, 1).toUpperCase(
                    Locale.getDefault()
            ));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        return result.length() == 0
                ? getString(R.string.family_live_unknown)
                : result.toString();
    }

    @NonNull
    private String movement(@NonNull FamilyLiveCloudMember member) {
        switch (member.movementType) {
            case "STATIONARY":
                return getString(R.string.family_live_movement_stationary);
            case "WALKING":
                return getString(R.string.family_live_movement_walking);
            case "CYCLING":
                return getString(R.string.family_live_movement_cycling);
            case "TRAVELLING":
                return getString(R.string.family_live_movement_travelling);
            default:
                return getString(R.string.family_live_unknown);
        }
    }
}
