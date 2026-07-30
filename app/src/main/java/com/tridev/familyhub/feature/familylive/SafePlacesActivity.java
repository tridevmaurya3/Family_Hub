package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    @Nullable private SafePlace editingPlace;
    private ActivityResultLauncher<Intent> mapPickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivitySafePlacesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        repository = new SafePlaceRepository(getApplicationContext());
        mapPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != Activity.RESULT_OK
                            || data == null
                            || !data.hasExtra(
                                    SafePlaceMapPickerActivity.RESULT_LATITUDE
                            )
                            || !data.hasExtra(
                                    SafePlaceMapPickerActivity.RESULT_LONGITUDE
                            )) {
                        return;
                    }
                    latitude = data.getDoubleExtra(
                            SafePlaceMapPickerActivity.RESULT_LATITUDE,
                            0D
                    );
                    longitude = data.getDoubleExtra(
                            SafePlaceMapPickerActivity.RESULT_LONGITUDE,
                            0D
                    );
                    hasLocation = Double.isFinite(latitude)
                            && Double.isFinite(longitude)
                            && !(latitude == 0D && longitude == 0D);
                    if (hasLocation) {
                        binding.locationStatus.setText(
                                R.string.safe_place_map_location_ready
                        );
                    }
                }
        );
        binding.safePlaceType.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.safe_place_types)
        ));
        binding.buttonBack.setOnClickListener(v -> finish());
        binding.buttonAlertHistory.setOnClickListener(v -> startActivity(
                new Intent(this, SafePlaceAlertHistoryActivity.class)
        ));
        binding.buttonUseLocation.setOnClickListener(v -> loadLocation());
        binding.buttonSelectOnMap.setOnClickListener(v ->
                mapPickerLauncher.launch(new Intent(
                        this,
                        SafePlaceMapPickerActivity.class
                ))
        );
        binding.buttonSave.setOnClickListener(v -> save());
        binding.buttonSafePlaceSettings.setOnClickListener(v ->
                openRelevantSettings()
        );
        loadPlaces();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAvailabilityGuidance();
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
        final float safeRadius = radius;
        SafePlace place = editingPlace == null
                ? new SafePlace()
                : editingPlace;
        place.name = name;
        place.placeType = type;
        place.latitude = latitude;
        place.longitude = longitude;
        place.radiusMeters = safeRadius;
        if (place.alertsEnabled && !hasGeofencePermissions()) {
            place.alertsEnabled = false;
            Toast.makeText(
                    this,
                    R.string.safe_place_saved_disabled_permissions,
                    Toast.LENGTH_LONG
            ).show();
        }
        repository.save(place, new SafePlaceRepository.SaveCallback() {
            @Override public void onSaved(long id) {
                place.id = id;
                if (place.alertsEnabled) {
                    registerGeofence(place, true);
                } else {
                    SafePlaceRegistrar.remove(
                            SafePlacesActivity.this,
                            String.valueOf(id)
                    );
                    Toast.makeText(SafePlacesActivity.this,
                            R.string.safe_place_saved_local,
                            Toast.LENGTH_SHORT).show();
                }
                clearEditor();
                loadPlaces();
            }
            @Override public void onDuplicate() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_duplicate, Toast.LENGTH_LONG).show();
            }
            @Override public void onLimitReached() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_limit_reached,
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onError() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_save_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadPlaces() {
        repository.loadAll(places -> {
            binding.safePlaceList.removeAllViews();
            if (places.isEmpty()) {
                TextView empty = createBodyText(
                        getString(R.string.safe_place_none)
                );
                binding.safePlaceList.addView(empty);
                return;
            }
            for (SafePlace place : places) {
                binding.safePlaceList.addView(createPlaceCard(place));
            }
        });
    }

    private View createPlaceCard(SafePlace place) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(18));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(
                this,
                place.alertsEnabled
                        ? R.color.fh_module_family
                        : R.color.fh_outline_variant
        ));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                place.alertsEnabled
                        ? R.color.fh_module_family_container
                        : R.color.fh_surface_variant
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = createBodyText(place.name);
        title.setTextAppearance(R.style.TextAppearance_FamilyHub_CardTitle);
        body.addView(title);
        body.addView(createBodyText(getString(
                R.string.safe_place_card_detail,
                place.placeType,
                Math.round(place.radiusMeters),
                getString(place.alertsEnabled
                        ? R.string.safe_place_alerts_on
                        : R.string.safe_place_alerts_off)
        )));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        MaterialButton edit = actionButton(R.string.safe_place_edit);
        MaterialButton toggle = actionButton(place.alertsEnabled
                ? R.string.safe_place_disable
                : R.string.safe_place_enable);
        MaterialButton delete = actionButton(R.string.safe_place_delete);
        actions.addView(edit);
        actions.addView(toggle);
        actions.addView(delete);
        body.addView(actions);
        card.addView(body);

        edit.setOnClickListener(ignored -> editPlace(place));
        toggle.setOnClickListener(ignored -> toggleAlerts(place));
        delete.setOnClickListener(ignored -> confirmDelete(place));
        return card;
    }

    private MaterialButton actionButton(int textRes) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setText(textRes);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        params.setMarginEnd(dp(6));
        button.setLayoutParams(params);
        button.setMinWidth(0);
        button.setTextSize(12F);
        return button;
    }

    private TextView createBodyText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextAppearance(R.style.TextAppearance_FamilyHub_Body);
        return text;
    }

    private void editPlace(SafePlace place) {
        editingPlace = place;
        binding.safePlaceName.setText(place.name);
        binding.safePlaceType.setText(place.placeType, false);
        binding.safePlaceRadius.setText(String.valueOf(
                Math.round(place.radiusMeters)
        ));
        latitude = place.latitude;
        longitude = place.longitude;
        hasLocation = true;
        binding.locationStatus.setText(R.string.safe_place_location_ready);
        binding.buttonSave.setText(R.string.safe_place_update);
        binding.safePlaceName.requestFocus();
    }

    private void toggleAlerts(SafePlace place) {
        if (!place.alertsEnabled && !hasGeofencePermissions()) {
            Toast.makeText(this,
                    R.string.safe_place_permission_required_for_alerts,
                    Toast.LENGTH_LONG).show();
            updateAvailabilityGuidance();
            return;
        }
        place.alertsEnabled = !place.alertsEnabled;
        repository.save(place, new SafePlaceRepository.SaveCallback() {
            @Override public void onSaved(long id) {
                if (place.alertsEnabled) {
                    registerGeofence(place, false);
                } else {
                    SafePlaceRegistrar.remove(
                            SafePlacesActivity.this,
                            String.valueOf(place.id)
                    );
                    loadPlaces();
                }
            }
            @Override public void onDuplicate() {
                place.alertsEnabled = !place.alertsEnabled;
            }
            @Override public void onLimitReached() {
                place.alertsEnabled = false;
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_limit_reached,
                        Toast.LENGTH_LONG).show();
                loadPlaces();
            }
            @Override public void onError() {
                place.alertsEnabled = !place.alertsEnabled;
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_save_error,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void registerGeofence(
            SafePlace place,
            boolean savedFromEditor
    ) {
        SafePlaceRegistrar.register(
                this,
                String.valueOf(place.id),
                place.latitude,
                place.longitude,
                place.radiusMeters,
                new SafePlaceRegistrar.RegistrationCallback() {
                    @Override public void onRegistered() {
                        Toast.makeText(
                                SafePlacesActivity.this,
                                savedFromEditor
                                        ? R.string.safe_place_saved
                                        : R.string.safe_place_alert_enabled,
                                Toast.LENGTH_SHORT
                        ).show();
                        loadPlaces();
                    }

                    @Override public void onPermissionDenied() {
                        rollbackFailedRegistration(place);
                    }

                    @Override public void onError() {
                        rollbackFailedRegistration(place);
                    }
                }
        );
    }

    private void rollbackFailedRegistration(SafePlace place) {
        place.alertsEnabled = false;
        repository.setAlertsEnabled(
                place.id,
                false,
                new SafePlaceRepository.ActionCallback() {
                    @Override public void onComplete() {
                        Toast.makeText(
                                SafePlacesActivity.this,
                                R.string.safe_place_registration_failed,
                                Toast.LENGTH_LONG
                        ).show();
                        loadPlaces();
                    }

                    @Override public void onError() {
                        Toast.makeText(
                                SafePlacesActivity.this,
                                R.string.safe_place_save_error,
                                Toast.LENGTH_LONG
                        ).show();
                        loadPlaces();
                    }
                }
        );
    }

    private void confirmDelete(SafePlace place) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.safe_place_delete_title)
                .setMessage(getString(
                        R.string.safe_place_delete_message,
                        place.name
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.safe_place_delete,
                        (dialog, which) -> deletePlace(place)
                )
                .show();
    }

    private void deletePlace(SafePlace place) {
        repository.delete(place, new SafePlaceRepository.ActionCallback() {
            @Override public void onComplete() {
                SafePlaceRegistrar.remove(
                        SafePlacesActivity.this,
                        String.valueOf(place.id)
                );
                if (editingPlace != null
                        && editingPlace.id == place.id) {
                    clearEditor();
                }
                loadPlaces();
            }
            @Override public void onError() {
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_delete_error,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void clearEditor() {
        editingPlace = null;
        hasLocation = false;
        latitude = 0D;
        longitude = 0D;
        binding.safePlaceName.setText("");
        binding.safePlaceType.setText("", false);
        binding.safePlaceRadius.setText("200");
        binding.safePlaceRadius.setError(null);
        binding.locationStatus.setText(
                R.string.safe_place_location_waiting
        );
        binding.buttonSave.setText(R.string.safe_place_save);
    }

    private int dp(int value) {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    }

    private boolean hasGeofencePermissions() {
        boolean fine = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT
                < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
        return fine && background;
    }

    private boolean isGpsEnabled() {
        LocationManager manager = (LocationManager) getSystemService(
                LOCATION_SERVICE
        );
        return manager != null && manager.isLocationEnabled();
    }

    private void updateAvailabilityGuidance() {
        if (!hasGeofencePermissions()) {
            binding.safePlacePermissionStatus.setText(
                    R.string.safe_place_background_permission_state
            );
            binding.buttonSafePlaceSettings.setText(
                    R.string.safe_place_open_app_settings
            );
        } else if (!isGpsEnabled()) {
            binding.safePlacePermissionStatus.setText(
                    R.string.safe_place_gps_off_state
            );
            binding.buttonSafePlaceSettings.setText(
                    R.string.safe_place_open_location_settings
            );
        } else {
            binding.safePlacePermissionStatus.setText(
                    R.string.safe_place_ready_state
            );
            binding.buttonSafePlaceSettings.setText(
                    R.string.safe_place_open_settings
            );
        }
    }

    private void openRelevantSettings() {
        Intent intent;
        if (!hasGeofencePermissions()) {
            intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)
            );
        } else {
            intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        }
        startActivity(intent);
    }

    @Override protected void onDestroy() {
        repository.close();
        super.onDestroy();
    }
}
