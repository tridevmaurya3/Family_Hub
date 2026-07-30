package com.tridev.familyhub.feature.familylive;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
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
        final float safeRadius = radius;
        SafePlace place = editingPlace == null
                ? new SafePlace()
                : editingPlace;
        place.name = name;
        place.placeType = type;
        place.latitude = latitude;
        place.longitude = longitude;
        place.radiusMeters = safeRadius;
        repository.save(place, new SafePlaceRepository.SaveCallback() {
            @Override public void onSaved(long id) {
                place.id = id;
                if (place.alertsEnabled) {
                    SafePlaceRegistrar.register(
                            SafePlacesActivity.this,
                            String.valueOf(id),
                            latitude,
                            longitude,
                            safeRadius
                    );
                } else {
                    SafePlaceRegistrar.remove(
                            SafePlacesActivity.this,
                            String.valueOf(id)
                    );
                }
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_saved, Toast.LENGTH_SHORT).show();
                clearEditor();
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
        place.alertsEnabled = !place.alertsEnabled;
        repository.save(place, new SafePlaceRepository.SaveCallback() {
            @Override public void onSaved(long id) {
                if (place.alertsEnabled) {
                    SafePlaceRegistrar.register(
                            SafePlacesActivity.this,
                            String.valueOf(place.id),
                            place.latitude,
                            place.longitude,
                            place.radiusMeters
                    );
                } else {
                    SafePlaceRegistrar.remove(
                            SafePlacesActivity.this,
                            String.valueOf(place.id)
                    );
                }
                loadPlaces();
            }
            @Override public void onDuplicate() {
                place.alertsEnabled = !place.alertsEnabled;
            }
            @Override public void onError() {
                place.alertsEnabled = !place.alertsEnabled;
                Toast.makeText(SafePlacesActivity.this,
                        R.string.safe_place_save_error,
                        Toast.LENGTH_LONG).show();
            }
        });
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

    @Override protected void onDestroy() {
        repository.close();
        super.onDestroy();
    }
}
