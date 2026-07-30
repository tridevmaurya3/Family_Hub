package com.tridev.familyhub.feature.familylive;

import android.app.Activity;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.textfield.TextInputEditText;
import com.tridev.familyhub.R;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only map and geocoder picker for a Safe Place coordinate. */
public final class SafePlaceMapPickerActivity extends AppCompatActivity {

    public static final String RESULT_LATITUDE = "safe_place_latitude";
    public static final String RESULT_LONGITUDE = "safe_place_longitude";
    private final ExecutorService geocodeExecutor =
            Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    @Nullable private GoogleMap map;
    @Nullable private Marker marker;
    @Nullable private LatLng selected;
    private TextView status;
    private TextInputEditText searchInput;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_safe_place_map_picker);
        status = findViewById(R.id.safePlacePickerStatus);
        searchInput = findViewById(R.id.safePlacePickerSearch);
        findViewById(R.id.buttonSafePlacePickerBack)
                .setOnClickListener(ignored -> finish());
        findViewById(R.id.buttonSafePlacePickerSearch)
                .setOnClickListener(ignored -> search());
        findViewById(R.id.buttonSafePlacePickerConfirm)
                .setOnClickListener(ignored -> confirm());

        SupportMapFragment fragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(
                        R.id.safePlacePickerMap
                );
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.safePlacePickerMap, fragment)
                    .commitNow();
        }
        fragment.getMapAsync(googleMap -> {
            map = googleMap;
            googleMap.getUiSettings().setCompassEnabled(true);
            googleMap.getUiSettings().setZoomControlsEnabled(true);
            googleMap.setOnMapClickListener(this::select);
        });
    }

    private void search() {
        String query = String.valueOf(searchInput.getText()).trim();
        if (TextUtils.isEmpty(query)) {
            searchInput.setError(getString(
                    R.string.safe_place_search_required
            ));
            return;
        }
        status.setText(R.string.safe_place_searching);
        try {
            geocodeExecutor.execute(() -> {
                LatLng result = null;
                try {
                    Geocoder geocoder = new Geocoder(
                            getApplicationContext(),
                            Locale.getDefault()
                    );
                    List<Address> addresses = geocoder.getFromLocationName(
                            query,
                            1
                    );
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        double latitude = address.getLatitude();
                        double longitude = address.getLongitude();
                        if (valid(latitude, longitude)) {
                            result = new LatLng(latitude, longitude);
                        }
                    }
                } catch (Exception ignored) {
                    // Search failure is represented in the UI without data logs.
                }
                LatLng resolved = result;
                runOnUiThread(() -> {
                    if (destroyed.get()) {
                        return;
                    }
                    if (resolved == null) {
                        status.setText(R.string.safe_place_search_no_result);
                    } else {
                        select(resolved);
                        if (map != null) {
                            map.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                            resolved,
                                            16F
                                    )
                            );
                        }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            status.setText(R.string.safe_place_search_no_result);
        }
    }

    private void select(@NonNull LatLng position) {
        if (!valid(position.latitude, position.longitude)) {
            return;
        }
        selected = position;
        if (marker != null) {
            marker.remove();
        }
        if (map != null) {
            marker = map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(getString(R.string.safe_place_selected_marker)));
        }
        status.setText(R.string.safe_place_map_selected);
    }

    private void confirm() {
        LatLng position = selected;
        if (position == null) {
            status.setText(R.string.safe_place_map_tap_first);
            return;
        }
        Intent result = new Intent()
                .putExtra(RESULT_LATITUDE, position.latitude)
                .putExtra(RESULT_LONGITUDE, position.longitude);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private boolean valid(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude <= 180D
                && !(latitude == 0D && longitude == 0D);
    }

    @Override
    protected void onDestroy() {
        destroyed.set(true);
        geocodeExecutor.shutdownNow();
        super.onDestroy();
    }
}
