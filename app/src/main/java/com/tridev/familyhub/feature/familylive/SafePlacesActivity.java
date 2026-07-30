package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlace;
import com.tridev.familyhub.data.repository.SafePlaceRepository;
import com.tridev.familyhub.databinding.ActivitySafePlacesBinding;
import com.tridev.familyhub.geofence.SafePlaceRegistrar;

public class SafePlacesActivity extends AppCompatActivity {
    private ActivitySafePlacesBinding binding;
    private SafePlaceRepository repository;
    private double latitude;
    private double longitude;
    private boolean hasLocation;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivitySafePlacesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new SafePlaceRepository(getApplicationContext());
        binding.safePlaceType.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.safe_place_types)
        ));
        binding.buttonBack.setOnClickListener(v -> finish());
        binding.buttonUseLocation.setOnClickListener(v -> loadLocation());
        binding.buttonSave.setOnClickListener(v -> save());
        loadPlaces();
    }

    @SuppressLint("MissingPermission")
    private void loadLocation() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.safe_place_permission, Toast.LENGTH_LONG).show();
            return;
        }
        LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        null
                ).addOnSuccessListener(location -> {
                    if (location == null) {
                        Toast.makeText(this, R.string.safe_place_location_error,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                    hasLocation = true;
                    binding.locationStatus.setText(
                            R.string.safe_place_location_ready
                    );
                });
    }

    private void save() {
        String name = String.valueOf(binding.safePlaceName.getText()).trim();
        String type = String.valueOf(binding.safePlaceType.getText()).trim();
        String radiusValue = String.valueOf(binding.safePlaceRadius.getText()).trim();
        if (name.length() < 2 || TextUtils.isEmpty(type) || !hasLocation) {
            Toast.makeText(this, R.string.safe_place_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        float radius;
        try {
            radius = Float.parseFloat(radiusValue);
        } catch (NumberFormatException error) {
            radius = 0F;
        }
        if (radius < 100F || radius > 5000F) {
            binding.safePlaceRadius.setError(
                    getString(R.string.safe_place_radius_error)
            );
            return;
        }
        SafePlace place = new SafePlace();
        place.name = name;
        place.placeType = type;
        place.latitude = latitude;
        place.longitude = longitude;
        place.radiusMeters = radius;
        repository.save(place, new SafePlaceRepository.SaveCallback() {
            @Override public void onSaved(long id) {
                place.id = id;
                SafePlaceRegistrar.register(
                        SafePlacesActivity.this,
                        String.valueOf(id), latitude, longitude, radius
                );
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_saved, Toast.LENGTH_SHORT).show();
                loadPlaces();
            }
            @Override public void onDuplicate() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_duplicate, Toast.LENGTH_LONG).show();
            }
            @Override public void onError() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_save_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadPlaces() {
        repository.loadAll(places -> {
            if (places.isEmpty()) {
                binding.safePlaceList.setText(R.string.safe_place_none);
                return;
            }
            StringBuilder summary = new StringBuilder();
            for (SafePlace place : places) {
                if (summary.length() > 0) summary.append('\n');
                summary.append("• ").append(place.name)
                        .append(" (").append(place.placeType).append(") · ")
                        .append(Math.round(place.radiusMeters)).append(" m");
            }
            binding.safePlaceList.setText(summary);
        });
    }

    @Override protected void onDestroy() {
        repository.close();
        super.onDestroy();
    }
}
