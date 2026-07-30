package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;
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

/** Dedicated lifecycle-safe map for authorised Family Live memberships. */
public final class FamilyMapActivity extends AppCompatActivity {
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
    @NonNull private List<FamilyLiveCloudMember> members = new ArrayList<>();
    @NonNull private List<SafePlace> safePlaces = new ArrayList<>();
    @NonNull private String query = "";
    @Nullable private GoogleMap map;
    @Nullable private Circle accuracyCircle;
    @Nullable private Marker selectedMarker;
    @Nullable private CameraPosition restoredCamera;
    @Nullable private String selectedMemberUid;
    @Nullable private FamilyLiveRepository familyRepository;
    @Nullable private SafePlaceRepository safePlaceRepository;
    private View loading;
    private View stateCard;
    private View topPanel;
    private View bottomPanel;
    private TextView stateText;
    private MaterialButton typeButton;
    private MaterialButton trafficButton;
    private boolean mapReady;
    private boolean dataReady;
    private boolean fitOnNextRender = true;
    private boolean trafficEnabled;
    private int mapType = GoogleMap.MAP_TYPE_NORMAL;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_family_map);
        View root = findViewById(R.id.familyMapRoot);
        topPanel = findViewById(R.id.familyMapTopPanel);
        bottomPanel = findViewById(R.id.familyMapBottomPanel);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            topPanel.setPadding(topPanel.getPaddingLeft(),
                    bars.top + getResources().getDimensionPixelSize(
                            R.dimen.space_8),
                    topPanel.getPaddingRight(), topPanel.getPaddingBottom());
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
        findViewById(R.id.buttonFamilyMapBack).setOnClickListener(
                ignored -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.buttonFamilyMapRetry).setOnClickListener(
                ignored -> restartMemberStream());
        findViewById(R.id.buttonFamilyMapFit).setOnClickListener(
                ignored -> fitAllMembers());
        findViewById(R.id.buttonFamilyMapRecenter).setOnClickListener(
                ignored -> focusCurrentUser());
        typeButton.setOnClickListener(ignored -> cycleMapType());
        trafficButton.setOnClickListener(ignored -> toggleTraffic());
        TextInputEditText search = findViewById(R.id.inputFamilyMapSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim();
                renderMarkers();
                focusMatchingMember();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        if (!query.isEmpty()) {
            search.setText(query);
            search.setSelection(search.length());
        }
        familyRepository = new FamilyLiveRepository(this);
        safePlaceRepository = new SafePlaceRepository(this);
        safePlaceRepository.loadAll(places -> {
            safePlaces = new ArrayList<>(places);
            renderMarkers();
        });
        SupportMapFragment fragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(
                        R.id.familyMapHost);
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.familyMapHost, fragment).commitNow();
        }
        fragment.getMapAsync(googleMap -> {
            map = googleMap;
            mapReady = true;
            googleMap.setMapType(mapType);
            googleMap.setTrafficEnabled(trafficEnabled);
            updateMapControlLabels();
            googleMap.getUiSettings().setCompassEnabled(true);
            googleMap.getUiSettings().setZoomControlsEnabled(true);
            googleMap.getUiSettings().setMapToolbarEnabled(true);
            googleMap.setOnMarkerClickListener(marker -> {
                FamilyLiveCloudMember member = markerMembers.get(marker);
                if (member == null) return false;
                selectMember(marker, member);
                return true;
            });
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
        });
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

    @Override protected void onStart() {
        super.onStart();
        restartMemberStream();
    }

    @Override protected void onStop() {
        if (familyRepository != null) {
            familyRepository.stopObservingCloudMembers();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
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
        if (familyRepository == null) return;
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
        if (!mapReady || map == null) return;
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
            if (!member.sharingEnabled) continue;
            sharing++;
            if (!member.hasLocation || !validCoordinates(
                    member.latitude, member.longitude)) continue;
            String searchable = (member.displayName + " " + member.role)
                    .toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !searchable.contains(normalized)) {
                continue;
            }
            boolean stale = member.updatedAt <= 0L
                    || now - member.updatedAt > LIVE_FRESHNESS_MS;
            boolean current = FirebaseAuth.getInstance().getCurrentUser()
                    != null && member.uid.equals(FirebaseAuth.getInstance()
                    .getCurrentUser().getUid());
            LatLng position = new LatLng(member.latitude, member.longitude);
            Marker marker = map.addMarker(new MarkerOptions()
                    .position(position).title(displayName(member))
                    .snippet(getString(stale
                            ? R.string.family_live_map_marker_stale
                            : R.string.family_live_map_marker_live))
                    .alpha(stale ? 0.68F : 1F)
                    .icon(BitmapDescriptorFactory.defaultMarker(current
                            ? BitmapDescriptorFactory.HUE_AZURE
                            : stale ? BitmapDescriptorFactory.HUE_ORANGE
                            : BitmapDescriptorFactory.HUE_GREEN)));
            if (marker != null) {
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
        }
        loading.setVisibility(View.GONE);
        if (!dataReady) showLoading();
        else if (authorised == 0) showState(R.string.family_map_no_authorised);
        else if (sharing == 0) showState(R.string.family_map_none_sharing);
        else if (shown == 0 && !query.isEmpty()) {
            showState(R.string.family_map_no_search_result);
        } else if (shown == 0) {
            showState(R.string.family_live_map_no_locations);
        } else if (!isGpsEnabled()) showState(R.string.family_map_gps_off);
        else {
            stateCard.setVisibility(View.GONE);
            if (fitOnNextRender) {
                fitOnNextRender = false;
                fitAllMembers();
            }
        }
    }

    private void renderSafePlaces() {
        if (map == null) return;
        for (SafePlace place : safePlaces) {
            if (!validCoordinates(place.latitude, place.longitude)
                    || place.radiusMeters < 1F) continue;
            LatLng position = new LatLng(place.latitude, place.longitude);
            float hue = place.alertsEnabled
                    ? BitmapDescriptorFactory.HUE_VIOLET
                    : BitmapDescriptorFactory.HUE_ROSE;
            map.addMarker(new MarkerOptions().position(position)
                    .title(place.name).snippet(getString(place.alertsEnabled
                            ? R.string.family_map_safe_place_enabled
                            : R.string.family_map_safe_place_disabled))
                    .alpha(place.alertsEnabled ? 0.9F : 0.45F)
                    .icon(BitmapDescriptorFactory.defaultMarker(hue)));
            int stroke = place.alertsEnabled
                    ? Color.rgb(107, 79, 161) : Color.GRAY;
            map.addCircle(new CircleOptions().center(position)
                    .radius(place.radiusMeters).strokeColor(stroke)
                    .fillColor(Color.argb(place.alertsEnabled ? 35 : 18,
                            Color.red(stroke), Color.green(stroke),
                            Color.blue(stroke)))
                    .strokeWidth(place.alertsEnabled ? 3F : 1.5F));
        }
    }

    private void selectMember(
            @NonNull Marker marker, @NonNull FamilyLiveCloudMember member) {
        if (map == null) return;
        restoreSelectedMarkerAppearance();
        selectedMemberUid = member.uid;
        selectedMarker = marker;
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(
                BitmapDescriptorFactory.HUE_RED
        ));
        if (accuracyCircle != null) accuracyCircle.remove();
        marker.showInfoWindow();
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                marker.getPosition(), 16F));
        if (member.accuracy > 0D && Double.isFinite(member.accuracy)) {
            accuracyCircle = map.addCircle(new CircleOptions()
                    .center(marker.getPosition()).radius(member.accuracy)
                    .strokeColor(ContextCompat.getColor(
                            this, R.color.fh_primary))
                    .fillColor(Color.argb(30, 15, 108, 189))
                    .strokeWidth(3F));
        }
        showMemberSheet(member);
    }

    private void showMemberSheet(@NonNull FamilyLiveCloudMember member) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        TextView details = new TextView(this);
        int padding = getResources().getDimensionPixelSize(R.dimen.space_24);
        details.setPadding(padding, padding, padding, padding);
        details.setTextSize(16F);
        details.setTextColor(ContextCompat.getColor(
                this, R.color.fh_text_primary));
        boolean stale = member.updatedAt <= 0L
                || System.currentTimeMillis() - member.updatedAt
                > LIVE_FRESHNESS_MS;
        String updated = member.updatedAt <= 0L
                ? getString(R.string.family_live_update_unavailable)
                : DateFormat.getDateTimeInstance().format(
                        new Date(member.updatedAt));
        String speed = member.speedMetersPerSecond >= 0.3D
                && Double.isFinite(member.speedMetersPerSecond)
                ? getString(R.string.family_live_movement_speed,
                        movement(member),
                        Math.round(member.speedMetersPerSecond * 3.6D))
                : movement(member);
        details.setText(getString(R.string.family_map_member_details,
                displayName(member),
                member.role.trim().isEmpty()
                        ? getString(R.string.family_live_unknown) : member.role,
                member.placeLabel.trim().isEmpty()
                        ? getString(R.string.family_live_location_unavailable)
                        : member.placeLabel,
                getString(stale ? R.string.family_live_map_marker_stale
                        : R.string.family_live_map_marker_live),
                updated, Math.round(member.accuracy),
                member.batteryPercentage < 0
                        ? getString(R.string.family_live_unknown)
                        : getString(R.string.family_live_battery_format,
                                member.batteryPercentage),
                member.charging ? getString(R.string.family_map_yes)
                        : getString(R.string.family_map_no),
                member.online ? getString(R.string.family_map_online)
                        : getString(R.string.family_map_offline),
                isNetworkAvailable()
                        ? getString(R.string.family_live_internet_available)
                        : getString(R.string.family_live_internet_unavailable),
                speed, member.sharingEnabled
                        ? getString(R.string.family_map_sharing_on)
                        : getString(R.string.family_map_sharing_off)));
        dialog.setContentView(details);
        dialog.show();
    }

    private void fitAllMembers() {
        if (map == null || memberMarkers.isEmpty()) return;
        if (memberMarkers.size() == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    memberMarkers.values().iterator().next().getPosition(),
                    15F));
            return;
        }
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (Marker marker : memberMarkers.values()) {
            bounds.include(marker.getPosition());
        }
        findViewById(R.id.familyMapHost).post(() -> {
            if (map != null && memberMarkers.size() > 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                        bounds.build(), 96));
            }
        });
    }

    private void focusCurrentUser() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            showState(R.string.family_map_current_unavailable);
            return;
        }
        Marker marker = memberMarkers.get(
                FirebaseAuth.getInstance().getCurrentUser().getUid());
        if (marker == null) {
            showState(R.string.family_map_current_unavailable);
            return;
        }
        FamilyLiveCloudMember member = markerMembers.get(marker);
        if (member != null) selectMember(marker, member);
    }

    private void focusMatchingMember() {
        if (query.isEmpty() || memberMarkers.size() != 1) return;
        Marker marker = memberMarkers.values().iterator().next();
        FamilyLiveCloudMember member = markerMembers.get(marker);
        if (member != null) selectMember(marker, member);
    }

    private void cycleMapType() {
        if (map == null) return;
        if (mapType == GoogleMap.MAP_TYPE_NORMAL) {
            mapType = GoogleMap.MAP_TYPE_SATELLITE;
            typeButton.setText(R.string.family_map_satellite);
        } else if (mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            mapType = GoogleMap.MAP_TYPE_TERRAIN;
            typeButton.setText(R.string.family_map_terrain);
        } else {
            mapType = GoogleMap.MAP_TYPE_NORMAL;
            typeButton.setText(R.string.family_map_normal);
        }
        map.setMapType(mapType);
        updateMapControlLabels();
    }

    private void toggleTraffic() {
        if (map == null) return;
        boolean enabled = !map.isTrafficEnabled();
        map.setTrafficEnabled(enabled);
        trafficEnabled = enabled;
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
        } else if (mapType == GoogleMap.MAP_TYPE_TERRAIN) {
            typeButton.setText(R.string.family_map_terrain);
        } else {
            typeButton.setText(R.string.family_map_normal);
        }
        trafficButton.setText(trafficEnabled
                ? R.string.family_map_traffic_on
                : R.string.family_map_traffic_off);
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
        boolean current = FirebaseAuth.getInstance().getCurrentUser() != null
                && previous.uid.equals(
                FirebaseAuth.getInstance().getCurrentUser().getUid()
        );
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

    private void enableMyLocationIfAllowed() {
        if (map == null) return;
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            try {
                map.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {
                showState(R.string.family_map_permission_denied);
            }
        }
    }

    private void applyMapPadding() {
        if (map == null || topPanel == null || bottomPanel == null) {
            return;
        }
        int edge = getResources().getDimensionPixelSize(R.dimen.space_12);
        map.setPadding(
                edge,
                topPanel.getBottom() + edge,
                edge,
                bottomPanel.getHeight() + (edge * 2)
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
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(
                manager.getActiveNetwork());
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR)
                        || capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private boolean isGpsEnabled() {
        LocationManager manager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER);
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90D && latitude <= 90D
                && longitude >= -180D && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }

    @NonNull private String displayName(
            @NonNull FamilyLiveCloudMember member) {
        return member.displayName.trim().isEmpty()
                ? getString(R.string.family_account_member_fallback)
                : member.displayName;
    }

    @NonNull private String movement(
            @NonNull FamilyLiveCloudMember member) {
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
