package com.tridev.familyhub.feature.familylive;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;

/** Professional Office 365-style action sheet for a selected Family Map member. */
public final class FamilyMapMemberActionsDialog {

    public interface Listener {
        void onDrive();

        void onWalk();

        void onOpenMaps();

        void onStreetView();
    }

    private FamilyMapMemberActionsDialog() {
    }

    public static void show(
            @NonNull AppCompatActivity activity,
            @NonNull String memberName,
            @NonNull String placeLabel,
            @NonNull Listener listener
    ) {
        View content = LayoutInflater.from(activity).inflate(
                R.layout.dialog_family_map_member_actions,
                null,
                false
        );

        TextView title = content.findViewById(
                R.id.textFamilyMapActionsTitle
        );
        TextView place = content.findViewById(
                R.id.textFamilyMapActionsPlace
        );

        title.setText(activity.getString(
                R.string.family_map_member_actions_title,
                memberName
        ));
        place.setText(placeLabel.trim().isEmpty()
                ? activity.getString(
                R.string.family_live_location_unavailable
        )
                : placeLabel.trim());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .create();

        bindAction(
                content.findViewById(R.id.actionFamilyMapDrive),
                dialog,
                listener::onDrive
        );
        bindAction(
                content.findViewById(R.id.actionFamilyMapWalk),
                dialog,
                listener::onWalk
        );
        bindAction(
                content.findViewById(R.id.actionFamilyMapOpenMaps),
                dialog,
                listener::onOpenMaps
        );
        bindAction(
                content.findViewById(R.id.actionFamilyMapStreetView),
                dialog,
                listener::onStreetView
        );
        content.findViewById(R.id.buttonFamilyMapActionsClose)
                .setOnClickListener(ignored -> dialog.dismiss());

        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        });
        dialog.show();
    }

    private static void bindAction(
            @NonNull View action,
            @NonNull AlertDialog dialog,
            @NonNull Runnable callback
    ) {
        action.setOnClickListener(ignored -> {
            dialog.dismiss();
            callback.run();
        });
    }
}
