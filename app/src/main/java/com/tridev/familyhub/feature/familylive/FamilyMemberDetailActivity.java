package com.tridev.familyhub.feature.familylive;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read-only detail screen backed by the authorised Family Live stream. */
public final class FamilyMemberDetailActivity extends AppCompatActivity {

    private static final String EXTRA_UID =
            "com.tridev.familyhub.extra.MEMBER_UID";
    private static final long FRESHNESS_MS = 3L * 60L * 1000L;

    private FamilyLiveRepository repository;
    private String memberUid;
    private ProgressBar loading;
    private View content;
    private View unavailable;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @NonNull String uid
    ) {
        return new Intent(context, FamilyMemberDetailActivity.class)
                .putExtra(EXTRA_UID, uid);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_member_detail);
        memberUid = getIntent().getStringExtra(EXTRA_UID);
        if (memberUid == null || memberUid.trim().isEmpty()) {
            finish();
            return;
        }
        loading = findViewById(R.id.memberDetailLoading);
        content = findViewById(R.id.memberDetailContent);
        unavailable = findViewById(R.id.memberDetailUnavailable);
        repository = new FamilyLiveRepository(this);
        findViewById(R.id.buttonMemberDetailBack).setOnClickListener(
                ignored -> getOnBackPressedDispatcher().onBackPressed()
        );
        findViewById(R.id.buttonMemberDetailRetry).setOnClickListener(
                ignored -> observeMember()
        );
        applyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        observeMember();
    }

    @Override
    protected void onStop() {
        if (repository != null) {
            repository.stopObservingCloudMembers();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (repository != null) {
            repository.close();
            repository = null;
        }
        super.onDestroy();
    }

    private void applyInsets() {
        View root = findViewById(R.id.memberDetailRoot);
        int start = root.getPaddingStart();
        int top = root.getPaddingTop();
        int end = root.getPaddingEnd();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            view.setPaddingRelative(
                    start + bars.left,
                    top + bars.top,
                    end + bars.right,
                    bottom + bars.bottom
            );
            return insets;
        });
    }

    private void observeMember() {
        if (repository == null) {
            return;
        }
        loading.setVisibility(View.VISIBLE);
        unavailable.setVisibility(View.GONE);
        repository.stopObservingCloudMembers();
        repository.observeCloudMembers(
                this::selectMember,
                error -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    loading.setVisibility(View.GONE);
                    if (content.getVisibility() != View.VISIBLE) {
                        unavailable.setVisibility(View.VISIBLE);
                    }
                    Snackbar.make(
                            findViewById(R.id.memberDetailRoot),
                            R.string.family_live_sync_error,
                            Snackbar.LENGTH_LONG
                    ).setAction(
                            R.string.family_map_retry,
                            ignored -> observeMember()
                    ).show();
                }
        );
    }

    private void selectMember(
            @NonNull List<FamilyLiveCloudMember> members
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        FamilyLiveCloudMember selected = null;
        for (FamilyLiveCloudMember member : members) {
            if (memberUid.equals(member.uid)) {
                selected = member;
                break;
            }
        }
        loading.setVisibility(View.GONE);
        unavailable.setVisibility(selected == null
                ? View.VISIBLE : View.GONE);
        content.setVisibility(selected == null
                ? View.GONE : View.VISIBLE);
        if (selected != null) {
            bindMember(selected);
        }
    }

    private void bindMember(@NonNull FamilyLiveCloudMember member) {
        String displayName = member.displayName.trim().isEmpty()
                ? getString(R.string.family_account_member_fallback)
                : member.displayName.trim();
        String reason = FamilyLiveAvailability.resolve(
                member,
                System.currentTimeMillis(),
                FRESHNESS_MS
        );
        text(R.id.tvMemberDetailAvatar, initials(displayName));
        text(R.id.tvMemberDetailName, displayName);
        text(R.id.tvMemberDetailRole, member.role.trim().isEmpty()
                ? getString(R.string.family_live_unknown) : member.role);
        TextView reasonView = findViewById(
                R.id.tvMemberDetailAvailability
        );
        reasonView.setText(FamilyLiveAvailability.labelRes(reason));
        reasonView.setTextColor(ContextCompat.getColor(
                this,
                FamilyLiveAvailability.isAvailable(reason)
                        ? R.color.fh_success
                        : FamilyLiveAvailability.isWarning(reason)
                                ? R.color.fh_warning : R.color.fh_error
        ));
        text(R.id.tvMemberDetailPlace, member.placeLabel.trim().isEmpty()
                ? getString(R.string.family_live_location_unavailable)
                : member.placeLabel);
        text(R.id.tvMemberDetailUpdated, member.updatedAt <= 0L
                ? getString(R.string.family_live_update_unavailable)
                : DateFormat.getDateTimeInstance().format(
                        new Date(member.updatedAt)
                ));
        text(R.id.tvMemberDetailAccuracy, member.hasLocation
                ? getString(
                        R.string.family_member_detail_accuracy_value,
                        Math.round(member.accuracy)
                )
                : getString(R.string.family_live_status_unavailable));
        text(R.id.tvMemberDetailBattery, member.batteryPercentage < 0
                ? getString(R.string.family_live_battery_unavailable)
                : getString(
                        R.string.family_live_battery_format,
                        member.batteryPercentage
                ));
        text(R.id.tvMemberDetailCharging, member.charging
                ? getString(R.string.family_map_yes)
                : getString(R.string.family_map_no));
        text(R.id.tvMemberDetailConnection, member.online
                ? getString(R.string.family_map_online)
                : getString(R.string.family_map_offline));
        text(R.id.tvMemberDetailMovement, movement(member.movementType));
        boolean reliableSpeed = member.hasLocation
                && member.accuracy > 0D
                && member.accuracy <= 100D
                && Double.isFinite(member.speedMetersPerSecond)
                && member.speedMetersPerSecond >= 0.3D;
        text(R.id.tvMemberDetailSpeed, reliableSpeed
                ? getString(
                        R.string.family_member_detail_speed_value,
                        Math.round(member.speedMetersPerSecond * 3.6D)
                )
                : getString(R.string.family_live_status_unavailable));
        text(R.id.tvMemberDetailSharing, member.sharingEnabled
                ? getString(R.string.family_map_sharing_on)
                : getString(R.string.family_map_sharing_off));
    }

    private void text(int id, @NonNull String value) {
        ((TextView) findViewById(id)).setText(value);
    }

    @NonNull
    private String movement(@NonNull String value) {
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "STATIONARY":
                return getString(R.string.family_live_movement_stationary);
            case "WALKING":
                return getString(R.string.family_live_movement_walking);
            case "CYCLING":
                return getString(R.string.family_live_movement_cycling);
            case "TRAVELLING":
                return getString(R.string.family_live_movement_travelling);
            default:
                return getString(R.string.family_live_unknown);
        }
    }

    @NonNull
    private String initials(@NonNull String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (!word.isEmpty() && result.length() < 2) {
                result.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return result.length() == 0 ? "?" : result.toString();
    }
}
