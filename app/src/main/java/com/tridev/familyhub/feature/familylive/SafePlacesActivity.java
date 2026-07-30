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
import com.tridev.familyhub.databinding.ActivitySafePlacesBinding;
import com.tridev.familyhub.geofence.SafePlaceRegistrar;

import java.util.UUID;

public class SafePlacesActivity extends AppCompatActivity {
    private ActivitySafePlacesBinding binding;
    private double latitude;
    private double longitude;
    private boolean hasLocation;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivitySafePlacesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.safePlaceType.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.safe_place_types)
        ));
        binding.buttonBack.setOnClickListener(v -> finish());
        binding.buttonUseLocation.setOnClickListener(v -> loadLocation());
        binding.buttonSave.setOnClickListener(v -> save());
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
        boolean registered = SafePlaceRegistrar.register(
                this,
                UUID.randomUUID().toString(),
                latitude,
                longitude,
                radius
        );
        Toast.makeText(
                this,
                registered ? R.string.safe_place_saved
                        : R.string.safe_place_permission,
                Toast.LENGTH_LONG
        ).show();
        if (registered) {
            binding.safePlaceList.setText(
                    getString(R.string.safe_place_device_only, name, type)
            );
        }
    }
}
