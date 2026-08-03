package com.tridev.familyhub.feature.journey;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.tridev.familyhub.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only route map for a trusted member and selected Journey day. */
public final class FamilyJourneyMapActivity extends AppCompatActivity
        implements OnMapReadyCallback {

    public static final String EXTRA_MEMBER_UID = "journey_member_uid";
    public static final String EXTRA_MEMBER_NAME = "journey_member_name";
    public static final String EXTRA_DAY_KEY = "journey_day_key";
    public static final String EXTRA_DATE_LABEL = "journey_date_label";

    private final FamilyJourneyRepository repository =
            new FamilyJourneyRepository();

    private TextView subtitle;
    private TextView summaryView;
    private View progress;
    private View empty;
    @Nullable private GoogleMap map;

    @NonNull private String memberUid = "";
    @NonNull private String memberName = "";
    @NonNull private String dayKey = "";
    @NonNull private String dateLabel = "";

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_journey_map);

        subtitle = findViewById(R.id.textJourneyMapSubtitle);
        summaryView = findViewById(R.id.textJourneyMapSummary);
        progress = findViewById(R.id.progressJourneyMap);
        empty = findViewById(R.id.textJourneyMapEmpty);
        findViewById(R.id.buttonJourneyMapBack)
                .setOnClickListener(v -> finish());

        memberUid = safe(getIntent().getStringExtra(EXTRA_MEMBER_UID));
        memberName = safe(getIntent().getStringExtra(EXTRA_MEMBER_NAME));
        dayKey = safe(getIntent().getStringExtra(EXTRA_DAY_KEY));
        dateLabel = safe(getIntent().getStringExtra(EXTRA_DATE_LABEL));
        subtitle.setText(getString(
                R.string.family_journey_map_subtitle,
                memberName,
                dateLabel
        ));

        SupportMapFragment fragment = SupportMapFragment.newInstance();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.journeyMapHost, fragment)
                .commit();
        fragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        googleMap.getUiSettings().setMapToolbarEnabled(true);
        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setRotateGesturesEnabled(true);
        loadRoute();
    }

    private void loadRoute() {
        if (memberUid.isEmpty() || dayKey.isEmpty() || map == null) {
            showEmpty();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        repository.loadDay(
                memberUid,
                dayKey,
                new FamilyJourneyRepository.PointsCallback() {
                    @Override
                    public void onLoaded(
                            @NonNull List<FamilyJourneyPoint> points
                    ) {
                        progress.setVisibility(View.GONE);
                        renderRoute(FamilyJourneySummary.from(points));
                    }

                    @Override
                    public void onError(@NonNull String reason) {
                        progress.setVisibility(View.GONE);
                        showEmpty();
                    }
                }
        );
    }

    private void renderRoute(@NonNull FamilyJourneySummary summary) {
        GoogleMap googleMap = map;
        if (googleMap == null || summary.points.isEmpty()) {
            showEmpty();
            return;
        }
        empty.setVisibility(View.GONE);
        googleMap.clear();

        PolylineOptions route = new PolylineOptions()
                .width(8F)
                .geodesic(true);
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (FamilyJourneyPoint point : summary.points) {
            LatLng latLng = new LatLng(point.latitude, point.longitude);
            route.add(latLng);
            bounds.include(latLng);
        }
        googleMap.addPolyline(route);

        FamilyJourneyPoint start = summary.points.get(0);
        FamilyJourneyPoint end = summary.points.get(summary.points.size() - 1);
        googleMap.addMarker(new MarkerOptions()
                .position(new LatLng(start.latitude, start.longitude))
                .title(getString(R.string.family_journey_map_start))
                .snippet(displayPlace(start))
                .icon(BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_GREEN
                )));
        googleMap.addMarker(new MarkerOptions()
                .position(new LatLng(end.latitude, end.longitude))
                .title(getString(R.string.family_journey_map_end))
                .snippet(displayPlace(end))
                .icon(BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_RED
                )));

        Set<String> markedPlaces = new HashSet<>();
        for (FamilyJourneyPoint point : summary.points) {
            if (point.safePlaceName == null
                    || point.safePlaceName.trim().isEmpty()
                    || !markedPlaces.add(point.safePlaceName.trim())) {
                continue;
            }
            googleMap.addMarker(new MarkerOptions()
                    .position(new LatLng(point.latitude, point.longitude))
                    .title(point.safePlaceName.trim())
                    .snippet(time(point.capturedAt))
                    .icon(BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_AZURE
                    )));
        }

        summaryView.setText(getString(
                R.string.family_journey_map_summary,
                formatDistance(summary.totalDistanceMeters),
                formatDuration(Math.max(0L,
                        summary.endedAt - summary.startedAt)),
                summary.points.size()
        ));

        if (summary.points.size() == 1) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(start.latitude, start.longitude),
                    16F
            ));
        } else {
            googleMap.setOnMapLoadedCallback(() -> {
                try {
                    googleMap.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                    bounds.build(),
                                    dp(72)
                            )
                    );
                } catch (IllegalStateException ignored) {
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(start.latitude, start.longitude),
                            14F
                    ));
                }
            });
        }
    }

    private void showEmpty() {
        empty.setVisibility(View.VISIBLE);
        summaryView.setText(R.string.family_journey_map_empty);
    }

    @NonNull
    private String displayPlace(@NonNull FamilyJourneyPoint point) {
        if (point.safePlaceName != null
                && !point.safePlaceName.trim().isEmpty()) {
            return point.safePlaceName.trim();
        }
        if (point.placeLabel != null && !point.placeLabel.trim().isEmpty()) {
            return point.placeLabel.trim();
        }
        return String.format(
                Locale.getDefault(),
                "%.5f, %.5f",
                point.latitude,
                point.longitude
        );
    }

    @NonNull
    private String formatDistance(double meters) {
        return meters < 1000D
                ? Math.round(meters) + " m"
                : String.format(Locale.getDefault(), "%.1f km", meters / 1000D);
    }

    @NonNull
    private String formatDuration(long durationMs) {
        long minutes = Math.max(0L, durationMs / 60_000L);
        long hours = minutes / 60L;
        return hours > 0L
                ? hours + "h " + (minutes % 60L) + "m"
                : minutes + " min";
    }

    @NonNull
    private String time(long timestamp) {
        return DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
