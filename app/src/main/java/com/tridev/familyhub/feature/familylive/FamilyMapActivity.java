package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.data.repository.SafePlaceRepository;
import com.tridev.familyhub.location.LocationFreshnessPolicy;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.InputStream;
import java.util.concurrent.Executors;

/**
 * Dedicated lifecycle-safe map for authorised Family Live memberships.
 *
 * The map provides Office 365-inspired controls, structured member cards,
 * Street View launching, nearest-member intelligence, two-member distance
 * comparison and quota-safe external navigation without storing route history.
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
    private static final String STATE_COMPARISON_MEMBER =
            "family_map_comparison_member";
    private static final String STATE_TRAFFIC = "family_map_traffic";

    private final Map<Marker, FamilyLiveCloudMember> markerMembers =
            new HashMap<>();
    private final Map<String, Marker> memberMarkers = new HashMap<>();
    private final Map<String, Bitmap> memberPhotos = new HashMap<>();

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
    private Polyline comparisonLine;
    @Nullable
    private Marker selectedMarker;
    @Nullable
    private Marker comparisonMarker;
    @Nullable
    private CameraPosition restoredCamera;
    @Nullable
    private String selectedMemberUid;
    @Nullable
    private String comparisonTargetUid;
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
    private FamilyMapComparePanelView comparePanel;
    private FamilyMapExpandableControlsView expandableControls;

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
        comparePanel = findViewById(R.id.familyMapComparePanel);
        controlRail = findViewById(R.id.familyMapControlRail);
        bottomPanel = findViewById(R.id.familyMapBottomPanel);
        expandableControls = findViewById(R.id.familyMapExpandableControls);

        comparePanel.setListener(new FamilyMapComparePanelView.Listener() {
            @Override
            public void onCompareMemberSelected(
                    @NonNull FamilyLiveCloudMember member
            ) {
                if (selectedMemberUid == null
                        || selectedMemberUid.equals(member.uid)) {
                    return;
                }
                Marker marker = memberMarkers.get(member.uid);
                if (marker != null) {
                    compareWithSelectedMember(marker, member);
                }
            }

            @Override
            public void onOpenMemberActions(
                    @NonNull FamilyLiveCloudMember member
            ) {
                showMemberActions(member);
            }
        });

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
            topPanel.post(this::positionComparePanel);
            return insets;
        });

        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight,
                                        oldBottom) -> {
            positionComparePanel();
            applyMapPadding();
        });

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
        loadMemberPhotos();
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
        findViewById(R.id.buttonFamilyMapStreetView).setOnClickListener(
                ignored -> openStreetViewForSelectedMember()
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
        googleMap.getUiSettings().setZoomControlsEnabled(false);
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
            selectOrCompareMember(marker, member);
            return true;
        });

        googleMap.setOnInfoWindowClickListener(marker -> {
            FamilyLiveCloudMember member = markerMembers.get(marker);
            if (member != null) {
                showMemberActions(member);
            }
        });

        googleMap.setOnMapClickListener(ignored -> {
            clearMemberSelection();
            if (expandableControls != null) {
                expandableControls.collapseFromMapTap();
            }
        });
        googleMap.setOnInfoWindowCloseListener(
                ignored -> setSelectionChromeVisible(true)
        );

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
        outState.putString(STATE_COMPARISON_MEMBER, comparisonTargetUid);
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

        String comparisonToRestore = comparisonTargetUid;
        map.clear();
        markerMembers.clear();
        memberMarkers.clear();
        accuracyCircle = null;
        comparisonLine = null;
        comparisonMarker = null;
        comparisonTargetUid = null;
        selectedMarker = null;

        if (selectedMemberUid == null) {
            setSelectionChromeVisible(true);
            hideComparisonPanel();
        }

        renderSafePlaces();

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

            boolean stale = isStale(member);
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
                    .icon(memberMarker(member, current, stale, false)));

            if (marker == null) {
                continue;
            }

            markerMembers.put(marker, member);
            memberMarkers.put(member.uid, marker);
            if (member.uid.equals(selectedMemberUid)) {
                marker.setIcon(memberMarker(member, current, stale, true));
                selectedMarker = marker;
            }
            shown++;
        }

        restoreSelectedMemberFocus();
        if (comparisonToRestore != null
                && selectedMemberUid != null
                && !selectedMemberUid.equals(comparisonToRestore)) {
            Marker target = memberMarkers.get(comparisonToRestore);
            FamilyLiveCloudMember targetMember = target == null
                    ? null : markerMembers.get(target);
            if (target != null && targetMember != null) {
                compareWithSelectedMember(target, targetMember);
            }
        }
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

    private void selectOrCompareMember(
            @NonNull Marker marker,
            @NonNull FamilyLiveCloudMember member
    ) {
        if (selectedMarker != null
                && selectedMemberUid != null
                && !selectedMemberUid.equals(member.uid)) {
            compareWithSelectedMember(marker, member);
            return;
        }
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

        clearComparisonOnly();
        restoreSelectedMarkerAppearance();
        selectedMemberUid = member.uid;
        selectedMarker = marker;
        marker.setIcon(memberMarker(member, isCurrentUser(member.uid),
                isStale(member), true));

        if (accuracyCircle != null) {
            accuracyCircle.remove();
        }

        setSelectionChromeVisible(false);

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

        marker.hideInfoWindow();
        showComparisonPanel(member);
    }

    private void compareWithSelectedMember(
            @NonNull Marker targetMarker,
            @NonNull FamilyLiveCloudMember targetMember
    ) {
        if (map == null || selectedMarker == null) {
            focusMember(targetMarker, targetMember, true);
            return;
        }

        clearComparisonOnly();
        comparisonMarker = targetMarker;
        comparisonTargetUid = targetMember.uid;
        targetMarker.setIcon(memberMarker(targetMember,
                isCurrentUser(targetMember.uid), isStale(targetMember), true));

        comparisonLine = map.addPolyline(new PolylineOptions()
                .add(selectedMarker.getPosition(), targetMarker.getPosition())
                .color(ContextCompat.getColor(this, R.color.fh_primary))
                .width(6F)
                .geodesic(true));

        setSelectionChromeVisible(false);
        targetMarker.hideInfoWindow();

        FamilyLiveCloudMember selected = findMember(selectedMemberUid);
        if (selected != null) {
            showComparisonPanel(selected);
        }

        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(selectedMarker.getPosition())
                .include(targetMarker.getPosition())
                .build();
        findViewById(R.id.familyMapHost).post(() -> {
            if (map != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        150
                ));
            }
        });
    }

    private void clearMemberSelection() {
        restoreComparisonMarkerAppearance();
        restoreSelectedMarkerAppearance();
        if (selectedMarker != null) {
            selectedMarker.hideInfoWindow();
        }
        if (comparisonMarker != null) {
            comparisonMarker.hideInfoWindow();
        }
        if (accuracyCircle != null) {
            accuracyCircle.remove();
            accuracyCircle = null;
        }
        if (comparisonLine != null) {
            comparisonLine.remove();
            comparisonLine = null;
        }
        comparisonMarker = null;
        comparisonTargetUid = null;
        selectedMarker = null;
        selectedMemberUid = null;
        hideComparisonPanel();
        setSelectionChromeVisible(true);
    }

    private void clearComparisonOnly() {
        restoreComparisonMarkerAppearance();
        if (comparisonMarker != null) {
            comparisonMarker.hideInfoWindow();
        }
        if (comparisonLine != null) {
            comparisonLine.remove();
            comparisonLine = null;
        }
        comparisonMarker = null;
        comparisonTargetUid = null;
    }

    private void setSelectionChromeVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (controlRail.getVisibility() != visibility) {
            controlRail.setVisibility(visibility);
        }
        if (bottomPanel.getVisibility() != visibility) {
            bottomPanel.setVisibility(visibility);
        }
        findViewById(R.id.familyMapHost).post(this::applyMapPadding);
    }

    private void showComparisonPanel(
            @NonNull FamilyLiveCloudMember primaryMember
    ) {
        comparePanel.bind(
                primaryMember,
                members,
                memberPhotos,
                comparisonTargetUid
        );
        if (comparePanel.getVisibility() != View.VISIBLE) {
            comparePanel.setVisibility(View.VISIBLE);
        }
        positionComparePanel();
        comparePanel.post(this::applyMapPadding);
    }

    private void hideComparisonPanel() {
        if (comparePanel != null && comparePanel.getVisibility() != View.GONE) {
            comparePanel.setVisibility(View.GONE);
            findViewById(R.id.familyMapHost).post(this::applyMapPadding);
        }
    }

    private void positionComparePanel() {
        if (comparePanel == null || topPanel == null) {
            return;
        }
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) comparePanel.getLayoutParams();
        int desiredTop = topPanel.getBottom()
                + getResources().getDimensionPixelSize(R.dimen.space_8);
        if (params.topMargin != desiredTop) {
            params.topMargin = desiredTop;
            comparePanel.setLayoutParams(params);
        }
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
            hideComparisonPanel();
            setSelectionChromeVisible(true);
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
        TextView nearest = card.findViewById(R.id.textMapInfoNearest);
        TextView distance = card.findViewById(R.id.textMapInfoDistance);

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
        accuracy.setText(getString(
                R.string.family_map_info_accuracy,
                roundedAccuracy
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

        FamilyLiveCloudMember nearestMember = nearestMemberTo(member);
        if (nearestMember == null) {
            nearest.setVisibility(View.GONE);
        } else {
            double nearestMeters = FamilyMapDistance.meters(
                    member.latitude,
                    member.longitude,
                    nearestMember.latitude,
                    nearestMember.longitude
            );
            nearest.setText(getString(
                    R.string.family_map_info_nearest,
                    displayName(nearestMember),
                    FamilyMapDistance.format(nearestMeters)
            ));
            nearest.setVisibility(View.VISIBLE);
        }

        FamilyLiveCloudMember selected = findMember(selectedMemberUid);
        if (comparisonTargetUid != null
                && comparisonTargetUid.equals(member.uid)
                && selected != null) {
            double comparedMeters = FamilyMapDistance.meters(
                    selected.latitude,
                    selected.longitude,
                    member.latitude,
                    member.longitude
            );
            distance.setText(getString(
                    R.string.family_map_info_distance_from,
                    displayName(selected),
                    FamilyMapDistance.format(comparedMeters)
            ));
        } else {
            distance.setText(R.string.family_map_info_compare_hint);
        }

        return card;
    }

    private void showMemberActions(
            @NonNull FamilyLiveCloudMember member
    ) {
        if (!validCoordinates(member.latitude, member.longitude)) {
            Toast.makeText(
                    this,
                    R.string.family_map_location_invalid,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        FamilyMapMemberActionsDialog.show(
                this,
                displayName(member),
                member.placeLabel,
                new FamilyMapMemberActionsDialog.Listener() {
                    @Override
                    public void onDrive() {
                        openNavigation(
                                member,
                                FamilyMapNavigationUri.MODE_DRIVING
                        );
                    }

                    @Override
                    public void onWalk() {
                        openNavigation(
                                member,
                                FamilyMapNavigationUri.MODE_WALKING
                        );
                    }

                    @Override
                    public void onOpenMaps() {
                        openExternalLocation(member);
                    }

                    @Override
                    public void onStreetView() {
                        openStreetView(member);
                    }
                }
        );
    }

    private void openNavigation(
            @NonNull FamilyLiveCloudMember member,
            @NonNull String travelMode
    ) {
        if (!FamilyMapExternalLauncher.openNavigation(
                this,
                member.latitude,
                member.longitude,
                travelMode
        )) {
            showNavigationUnavailable();
        }
    }

    private void openExternalLocation(
            @NonNull FamilyLiveCloudMember member
    ) {
        if (!FamilyMapExternalLauncher.openLocation(
                this,
                member.latitude,
                member.longitude,
                displayName(member)
        )) {
            showNavigationUnavailable();
        }
    }

    private void showNavigationUnavailable() {
        Toast.makeText(
                this,
                R.string.family_map_navigation_unavailable,
                Toast.LENGTH_SHORT
        ).show();
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
            focusMember(marker, member, true);
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

    private void openStreetViewForSelectedMember() {
        FamilyLiveCloudMember member = null;
        if (comparisonMarker != null) {
            member = markerMembers.get(comparisonMarker);
        }
        if (member == null && selectedMarker != null) {
            member = markerMembers.get(selectedMarker);
        }
        if (member == null) {
            Toast.makeText(
                    this,
                    R.string.family_map_select_member_for_street_view,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        openStreetView(member);
    }

    private void openStreetView(@NonNull FamilyLiveCloudMember member) {
        if (!validCoordinates(member.latitude, member.longitude)) {
            Toast.makeText(
                    this,
                    R.string.family_street_view_unavailable,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        startActivity(FamilyStreetViewActivity.createIntent(
                this,
                member.latitude,
                member.longitude,
                displayName(member)
        ));
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
        comparisonTargetUid = state.getString(STATE_COMPARISON_MEMBER);
        restoredCamera = state.getParcelable(STATE_CAMERA);
        fitOnNextRender = restoredCamera == null;
    }

    private void updateMapControlLabels() {
        if (typeButton == null || trafficButton == null) {
            return;
        }

        int fullMapTypeLabel;
        if (mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            typeButton.setText(R.string.family_map_control_satellite_short);
            fullMapTypeLabel = R.string.family_map_satellite;
        } else if (mapType == GoogleMap.MAP_TYPE_HYBRID) {
            typeButton.setText(R.string.family_map_control_hybrid_short);
            fullMapTypeLabel = R.string.family_map_hybrid;
        } else if (mapType == GoogleMap.MAP_TYPE_TERRAIN) {
            typeButton.setText(R.string.family_map_control_terrain_short);
            fullMapTypeLabel = R.string.family_map_terrain;
        } else {
            typeButton.setText(R.string.family_map_control_normal_short);
            fullMapTypeLabel = R.string.family_map_normal;
        }

        typeButton.setContentDescription(
                getString(R.string.family_map_type_description)
                        + " • "
                        + getString(fullMapTypeLabel)
        );
        trafficButton.setText(R.string.family_map_control_traffic_short);
        trafficButton.setContentDescription(getString(trafficEnabled
                ? R.string.family_map_traffic_on
                : R.string.family_map_traffic_off));
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
        selectedMarker.setIcon(memberMarker(previous,
                isCurrentUser(previous.uid), isStale(previous), false));
    }

    private void restoreComparisonMarkerAppearance() {
        if (comparisonMarker == null) {
            return;
        }
        FamilyLiveCloudMember previous = markerMembers.get(comparisonMarker);
        if (previous == null) {
            comparisonMarker = null;
            return;
        }
        comparisonMarker.setIcon(memberMarker(previous,
                isCurrentUser(previous.uid), isStale(previous), false));
    }

    private boolean isStale(@NonNull FamilyLiveCloudMember member) {
        return LocationFreshnessPolicy.isStale(
                member.updatedAt,
                System.currentTimeMillis(),
                LIVE_FRESHNESS_MS
        );
    }

    private float normalMarkerHue(
            @NonNull FamilyLiveCloudMember member,
            boolean current,
            boolean stale
    ) {
        if (current) {
            return BitmapDescriptorFactory.HUE_AZURE;
        }
        if (stale) {
            return BitmapDescriptorFactory.HUE_ORANGE;
        }
        return BitmapDescriptorFactory.HUE_GREEN;
    }

    @NonNull
    private com.google.android.gms.maps.model.BitmapDescriptor memberMarker(
            @NonNull FamilyLiveCloudMember member,
            boolean current,
            boolean stale,
            boolean selected
    ) {
        int color = ContextCompat.getColor(this, selected
                ? R.color.fh_error
                : current ? R.color.fh_info
                : stale ? R.color.fh_warning : R.color.fh_success);
        return FamilyMemberMarkerFactory.create(displayName(member),
                memberPhotos.get(member.displayName.trim().toLowerCase(Locale.ROOT)), color);
    }

    private void loadMemberPhotos() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Map<String, Bitmap> loaded = new HashMap<>();
            for (FamilyMember member : FamilyHubDatabase.getInstance(this)
                    .familyMemberDao().getAll()) {
                if (member.profilePhotoUri.trim().isEmpty()) continue;
                try (InputStream stream = getContentResolver().openInputStream(
                        Uri.parse(member.profilePhotoUri))) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) loaded.put(
                            member.name.trim().toLowerCase(Locale.ROOT), bitmap);
                } catch (Exception ignored) {
                    // Initials remain available when a legacy photo URI cannot be opened.
                }
            }
            runOnUiThread(() -> {
                memberPhotos.clear();
                memberPhotos.putAll(loaded);
                renderMarkers();
            });
        });
    }

    @Nullable
    private FamilyLiveCloudMember findMember(@Nullable String uid) {
        if (uid == null) {
            return null;
        }
        for (FamilyLiveCloudMember member : members) {
            if (uid.equals(member.uid)) {
                return member;
            }
        }
        return null;
    }

    @Nullable
    private FamilyLiveCloudMember nearestMemberTo(
            @NonNull FamilyLiveCloudMember source
    ) {
        FamilyLiveCloudMember nearest = null;
        double nearestMeters = Double.POSITIVE_INFINITY;
        for (FamilyLiveCloudMember candidate : members) {
            if (candidate.uid.equals(source.uid)
                    || !candidate.sharingEnabled
                    || !candidate.hasLocation
                    || !validCoordinates(
                    candidate.latitude,
                    candidate.longitude
            )) {
                continue;
            }
            double meters = FamilyMapDistance.meters(
                    source.latitude,
                    source.longitude,
                    candidate.latitude,
                    candidate.longitude
            );
            if (Double.isFinite(meters) && meters < nearestMeters) {
                nearestMeters = meters;
                nearest = candidate;
            }
        }
        return nearest;
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
        int bottomControlHeight = getResources().getDimensionPixelSize(
                R.dimen.family_map_controls_height
        );
        int bottomPadding = edge;
        if (controlRail.getVisibility() == View.VISIBLE
                || bottomPanel.getVisibility() == View.VISIBLE) {
            bottomPadding = Math.max(
                    bottomControlHeight,
                    bottomPanel.getHeight()
            ) + (edge * 2);
        }

        int topPadding = topPanel.getBottom() + edge;
        if (comparePanel != null
                && comparePanel.getVisibility() == View.VISIBLE
                && comparePanel.getBottom() > 0) {
            topPadding = Math.max(
                    topPadding,
                    comparePanel.getBottom() + edge
            );
        }

        map.setPadding(
                edge,
                topPadding,
                edge,
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
        return FamilyMapNavigationUri.validCoordinates(
                latitude,
                longitude
        );
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
