package com.tridev.familyhub.feature.familylive;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.SupportStreetViewPanoramaFragment;
import com.google.android.gms.maps.model.LatLng;
import com.tridev.familyhub.R;

/**
 * Full-screen Street View for a selected Family Live member position.
 *
 * The external Maps and Navigate actions remain available even when embedded
 * Street View is blocked by quota, billing or missing nearby imagery.
 */
public final class FamilyStreetViewActivity extends AppCompatActivity {

    private static final String EXTRA_LATITUDE =
            "com.tridev.familyhub.extra.STREET_VIEW_LATITUDE";
    private static final String EXTRA_LONGITUDE =
            "com.tridev.familyhub.extra.STREET_VIEW_LONGITUDE";
    private static final String EXTRA_TITLE =
            "com.tridev.familyhub.extra.STREET_VIEW_TITLE";

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            double latitude,
            double longitude,
            @NonNull String title
    ) {
        return new Intent(context, FamilyStreetViewActivity.class)
                .putExtra(EXTRA_LATITUDE, latitude)
                .putExtra(EXTRA_LONGITUDE, longitude)
                .putExtra(EXTRA_TITLE, title);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_family_street_view);

        double latitude = getIntent().getDoubleExtra(
                EXTRA_LATITUDE,
                Double.NaN
        );
        double longitude = getIntent().getDoubleExtra(
                EXTRA_LONGITUDE,
                Double.NaN
        );
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String displayTitle = title == null || title.trim().isEmpty()
                ? getString(R.string.family_street_view_title)
                : title.trim();

        View root = findViewById(R.id.familyStreetViewRoot);
        View topPanel = findViewById(R.id.familyStreetViewTopPanel);
        View loading = findViewById(R.id.familyStreetViewLoading);
        View stateCard = findViewById(R.id.familyStreetViewStateCard);
        TextView stateText = findViewById(R.id.textFamilyStreetViewState);
        TextView titleText = findViewById(R.id.textFamilyStreetViewTitle);

        titleText.setText(displayTitle);

        findViewById(R.id.buttonFamilyStreetViewBack).setOnClickListener(
                ignored -> getOnBackPressedDispatcher().onBackPressed()
        );
        findViewById(R.id.buttonFamilyStreetViewOpenMaps)
                .setOnClickListener(ignored -> {
                    if (!FamilyMapExternalLauncher.openLocation(
                            this,
                            latitude,
                            longitude,
                            displayTitle
                    )) {
                        showExternalUnavailable();
                    }
                });
        findViewById(R.id.buttonFamilyStreetViewNavigate)
                .setOnClickListener(ignored -> {
                    if (!FamilyMapExternalLauncher.openNavigation(
                            this,
                            latitude,
                            longitude,
                            FamilyMapNavigationUri.MODE_DRIVING
                    )) {
                        showExternalUnavailable();
                    }
                });

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) topPanel.getLayoutParams();
            params.topMargin = bars.top
                    + getResources().getDimensionPixelSize(R.dimen.space_8);
            topPanel.setLayoutParams(params);
            view.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        if (!validCoordinates(latitude, longitude)) {
            loading.setVisibility(View.GONE);
            stateText.setText(R.string.family_street_view_unavailable);
            stateCard.setVisibility(View.VISIBLE);
            findViewById(R.id.buttonFamilyStreetViewOpenMaps)
                    .setEnabled(false);
            findViewById(R.id.buttonFamilyStreetViewNavigate)
                    .setEnabled(false);
            return;
        }

        LatLng position = new LatLng(latitude, longitude);
        SupportStreetViewPanoramaFragment fragment =
                (SupportStreetViewPanoramaFragment)
                        getSupportFragmentManager().findFragmentById(
                                R.id.familyStreetViewHost
                        );
        if (fragment == null) {
            fragment = SupportStreetViewPanoramaFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.familyStreetViewHost, fragment)
                    .commitNow();
        }

        fragment.getStreetViewPanoramaAsync(panorama -> {
            panorama.setUserNavigationEnabled(true);
            panorama.setZoomGesturesEnabled(true);
            panorama.setPanningGesturesEnabled(true);
            panorama.setStreetNamesEnabled(true);
            panorama.setOnStreetViewPanoramaChangeListener(location -> {
                loading.setVisibility(View.GONE);
                if (location == null) {
                    stateText.setText(
                            R.string.family_street_view_unavailable
                    );
                    stateCard.setVisibility(View.VISIBLE);
                } else {
                    stateCard.setVisibility(View.GONE);
                }
            });
            panorama.setPosition(position, 250);
        });
    }

    private void showExternalUnavailable() {
        Toast.makeText(
                this,
                R.string.family_map_navigation_unavailable,
                Toast.LENGTH_SHORT
        ).show();
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
}
