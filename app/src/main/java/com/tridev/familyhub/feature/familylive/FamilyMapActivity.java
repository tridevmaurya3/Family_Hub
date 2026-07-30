package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.databinding.ActivityFamilyMapBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen map for shared family locations and common map controls. */
public class FamilyMapActivity extends AppCompatActivity
        implements OnMapReadyCallback {

    private static final float SEARCH_ZOOM = 15f;
    private static final float MY_LOCATION_ZOOM = 16f;

    private ActivityFamilyMapBinding binding;
    private GoogleMap googleMap;
    private FamilyLiveRepository repository;
    private FusedLocationProviderClient locationClient;
    private final List<LatLng> familyPositions = new ArrayList<>();
    private final ExecutorService geocoderExecutor =
            Executors.newSingleThreadExecutor();
    private boolean trafficEnabled;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = Boolean.TRUE.equals(
                                result.get(Manifest.permission.ACCESS_FINE_LOCATION)
                        ) || Boolean.TRUE.equals(
                                result.get(Manifest.permission.ACCESS_COARSE_LOCATION)
                        );
                        if (granted) {
                            enableAndOpenMyLocation();
                        } else {
                            toast(R.string.map_location_permission_needed);
                        }
                    }
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        binding = ActivityFamilyMapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new FamilyLiveRepository(this);
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.familyMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        binding.btnCloseMap.setOnClickListener(v -> finish());
        binding.btnMapSearch.setOnClickListener(v -> searchPlace());
        binding.etMapSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPlace();
                return true;
            }
            return false;
        });
        binding.btnLayers.setOnClickListener(v -> showLayerPicker());
        binding.btnTraffic.setOnClickListener(v -> toggleTraffic());
        binding.btnMyLocation.setOnClickListener(v -> requestMyLocation());
        binding.btnFitFamily.setOnClickListener(v -> fitFamilyOnScreen());
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.setBuildingsEnabled(true);
        googleMap.setIndoorEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setMapToolbarEnabled(true);
        googleMap.getUiSettings().setRotateGesturesEnabled(true);
        googleMap.getUiSettings().setTiltGesturesEnabled(true);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.setPadding(0, 90, 150, 20);
        loadFamilyMarkers();
    }

    private void loadFamilyMarkers() {
        repository.loadMemberStatuses(members -> {
            if (googleMap == null || isFinishing()) {
                return;
            }
            googleMap.clear();
            familyPositions.clear();
            for (FamilyLiveMemberData member : members) {
                if (!member.isLocationSharingEnabled || !member.hasLocation) {
                    continue;
                }
                LatLng position = new LatLng(member.latitude, member.longitude);
                familyPositions.add(position);
                googleMap.addMarker(new MarkerOptions()
                        .position(position)
                        .title(member.memberName)
                        .snippet(member.currentPlaceName));
            }
            if (familyPositions.isEmpty()) {
                toast(R.string.map_no_shared_locations);
            } else {
                fitFamilyOnScreen();
            }
        });
    }

    private void fitFamilyOnScreen() {
        if (googleMap == null || familyPositions.isEmpty()) {
            toast(R.string.map_no_shared_locations);
            return;
        }
        if (familyPositions.size() == 1) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    familyPositions.get(0),
                    SEARCH_ZOOM
            ));
            return;
        }
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (LatLng position : familyPositions) {
            bounds.include(position);
        }
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(
                bounds.build(),
                120
        ));
    }

    private void showLayerPicker() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.map_layer_title)
                .setSingleChoiceItems(
                        R.array.map_layer_options,
                        mapTypeIndex(),
                        (dialog, which) -> {
                            if (googleMap != null) {
                                int[] types = {
                                        GoogleMap.MAP_TYPE_NORMAL,
                                        GoogleMap.MAP_TYPE_SATELLITE,
                                        GoogleMap.MAP_TYPE_TERRAIN,
                                        GoogleMap.MAP_TYPE_HYBRID
                                };
                                googleMap.setMapType(types[which]);
                            }
                            dialog.dismiss();
                        }
                )
                .show();
    }

    private int mapTypeIndex() {
        if (googleMap == null) return 0;
        switch (googleMap.getMapType()) {
            case GoogleMap.MAP_TYPE_SATELLITE: return 1;
            case GoogleMap.MAP_TYPE_TERRAIN: return 2;
            case GoogleMap.MAP_TYPE_HYBRID: return 3;
            default: return 0;
        }
    }

    private void toggleTraffic() {
        if (googleMap == null) return;
        trafficEnabled = !trafficEnabled;
        googleMap.setTrafficEnabled(trafficEnabled);
        binding.btnTraffic.setSelected(trafficEnabled);
        binding.btnTraffic.setAlpha(trafficEnabled ? 1f : 0.65f);
    }

    private void requestMyLocation() {
        if (hasLocationPermission()) {
            enableAndOpenMyLocation();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void enableAndOpenMyLocation() {
        if (googleMap == null || !hasLocationPermission()) return;
        googleMap.setMyLocationEnabled(true);
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && googleMap != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()),
                        MY_LOCATION_ZOOM
                ));
            }
        });
    }

    private void searchPlace() {
        String query = binding.etMapSearch.getText().toString().trim();
        if (query.isEmpty()) return;
        geocoderExecutor.execute(() -> {
            List<Address> addresses = null;
            try {
                addresses = new Geocoder(this, Locale.getDefault())
                        .getFromLocationName(query, 1);
            } catch (IOException ignored) {
                // A friendly not-found message is shown below.
            }
            List<Address> result = addresses;
            runOnUiThread(() -> {
                if (result == null || result.isEmpty() || googleMap == null) {
                    toast(R.string.map_place_not_found);
                    return;
                }
                Address address = result.get(0);
                LatLng position = new LatLng(
                        address.getLatitude(),
                        address.getLongitude()
                );
                googleMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(position, SEARCH_ZOOM)
                );
            });
        });
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        geocoderExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
