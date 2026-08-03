package com.tridev.familyhub.feature.familylive;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

/** Opens Google Maps when installed and falls back to another map app/browser. */
public final class FamilyMapExternalLauncher {

    private static final String GOOGLE_MAPS_PACKAGE =
            "com.google.android.apps.maps";

    private FamilyMapExternalLauncher() {
    }

    public static boolean openNavigation(
            @NonNull Context context,
            double latitude,
            double longitude,
            @NonNull String travelMode
    ) {
        if (!FamilyMapNavigationUri.validCoordinates(
                latitude,
                longitude
        )) {
            return false;
        }

        Intent googleMaps = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(FamilyMapNavigationUri.googleNavigation(
                        latitude,
                        longitude,
                        travelMode
                ))
        );
        googleMaps.setPackage(GOOGLE_MAPS_PACKAGE);
        googleMaps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, googleMaps)) {
            return true;
        }

        Intent browserFallback = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(FamilyMapNavigationUri.webDirections(
                        latitude,
                        longitude,
                        travelMode
                ))
        );
        browserFallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(context, browserFallback);
    }

    public static boolean openLocation(
            @NonNull Context context,
            double latitude,
            double longitude,
            @NonNull String label
    ) {
        if (!FamilyMapNavigationUri.validCoordinates(
                latitude,
                longitude
        )) {
            return false;
        }

        Intent preferredMaps = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(FamilyMapNavigationUri.geoLocation(
                        latitude,
                        longitude,
                        label
                ))
        );
        preferredMaps.setPackage(GOOGLE_MAPS_PACKAGE);
        preferredMaps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, preferredMaps)) {
            return true;
        }

        Intent anyMap = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(FamilyMapNavigationUri.geoLocation(
                        latitude,
                        longitude,
                        label
                ))
        );
        anyMap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, anyMap)) {
            return true;
        }

        Intent browserFallback = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(FamilyMapNavigationUri.webLocation(
                        latitude,
                        longitude
                ))
        );
        browserFallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(context, browserFallback);
    }

    private static boolean tryStart(
            @NonNull Context context,
            @NonNull Intent intent
    ) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
