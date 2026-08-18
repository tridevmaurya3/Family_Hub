package com.tridev.familyhub.feature.familylive;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent, interactive member card for Family Map.
 *
 * Google Maps info windows are snapshots and cannot host a real dropdown. This
 * view intentionally sits above the map so a family member can remain selected
 * while another member is chosen for live distance comparison.
 */
public final class FamilyMapComparePanelView extends MaterialCardView {

    public interface Listener {
        void onCompareMemberSelected(@NonNull FamilyLiveCloudMember member);
        void onOpenMemberActions(@NonNull FamilyLiveCloudMember member);
    }

    private static final long LIVE_FRESHNESS_MS = 3L * 60L * 1000L;

    private final ImageView avatar;
    private final TextView nameView;
    private final TextView roleView;
    private final TextView statusView;
    private final TextView placeView;
    private final TextView updatedView;
    private final TextView accuracyView;
    private final TextView batteryView;
    private final TextView networkView;
    private final TextView movementView;
    private final TextView nearestView;
    private final TextView distanceView;
    private final TextView comparisonSummaryView;
    private final TextInputLayout compareLayout;
    private final MaterialAutoCompleteTextView compareInput;
    private final MaterialButton actionsButton;

    @NonNull
    private final List<FamilyLiveCloudMember> comparisonChoices = new ArrayList<>();
    @Nullable private Listener listener;
    @Nullable private FamilyLiveCloudMember primaryMember;
    private boolean bindingDropdown;

    public FamilyMapComparePanelView(@NonNull Context context) {
        this(context, null);
    }

    public FamilyMapComparePanelView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public FamilyMapComparePanelView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);

        setCardBackgroundColor(ContextCompat.getColor(
                context, R.color.family_map_panel_surface));
        setRadius(dp(20));
        setCardElevation(dp(5));
        setStrokeWidth(dp(1));
        setStrokeColor(ContextCompat.getColor(
                context, R.color.family_map_panel_stroke));
        setClickable(true);
        setFocusable(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        addView(root, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatarBackground.setColor(ContextCompat.getColor(
                context, R.color.fh_primary_container));
        avatar.setBackground(avatarBackground);
        avatar.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                dp(54), dp(54));
        avatarParams.setMarginEnd(dp(10));
        header.addView(avatar, avatarParams);

        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        header.addView(identity, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F));

        nameView = text(18F, true, R.color.family_map_info_value);
        identity.addView(nameView, matchWrap());
        roleView = text(12F, false, R.color.family_map_info_label);
        LinearLayout.LayoutParams roleParams = matchWrap();
        roleParams.topMargin = dp(2);
        identity.addView(roleView, roleParams);

        statusView = text(11F, true, R.color.family_map_live);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMarginStart(dp(8));
        header.addView(statusView, statusParams);

        addDivider(root);

        placeView = detailText(true);
        root.addView(placeView, matchWrap());
        updatedView = detailText(false);
        root.addView(updatedView, detailParams());
        accuracyView = detailText(false);
        root.addView(accuracyView, detailParams());

        LinearLayout deviceRow = new LinearLayout(context);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams deviceRowParams = matchWrap();
        deviceRowParams.topMargin = dp(4);
        root.addView(deviceRow, deviceRowParams);

        batteryView = detailText(false);
        deviceRow.addView(batteryView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F));
        networkView = detailText(false);
        LinearLayout.LayoutParams networkParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F);
        networkParams.setMarginStart(dp(8));
        deviceRow.addView(networkView, networkParams);

        movementView = detailText(false);
        root.addView(movementView, detailParams());

        nearestView = text(12F, true, R.color.fh_secondary);
        nearestView.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams nearestParams = matchWrap();
        nearestParams.topMargin = dp(8);
        root.addView(nearestView, nearestParams);

        TextView compareTitle = text(12F, true, R.color.family_map_info_value);
        compareTitle.setText(R.string.family_map_compare_title);
        LinearLayout.LayoutParams compareTitleParams = matchWrap();
        compareTitleParams.topMargin = dp(10);
        root.addView(compareTitle, compareTitleParams);

        compareLayout = new TextInputLayout(context);
        compareLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        compareLayout.setBoxCornerRadii(dp(12), dp(12), dp(12), dp(12));
        compareLayout.setBoxStrokeColor(ContextCompat.getColor(
                context, R.color.fh_primary));
        compareLayout.setHint(getResources().getString(
                R.string.family_map_compare_dropdown_hint));
        compareLayout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        LinearLayout.LayoutParams compareLayoutParams = matchWrap();
        compareLayoutParams.topMargin = dp(5);
        root.addView(compareLayout, compareLayoutParams);

        compareInput = new MaterialAutoCompleteTextView(context);
        compareInput.setInputType(InputType.TYPE_NULL);
        compareInput.setSingleLine(true);
        compareInput.setTextSize(13F);
        compareInput.setTextColor(ContextCompat.getColor(
                context, R.color.family_map_info_value));
        compareInput.setHintTextColor(ContextCompat.getColor(
                context, R.color.family_map_info_label));
        compareInput.setPadding(dp(12), 0, dp(8), 0);
        compareInput.setMinHeight(dp(46));
        compareInput.setOnClickListener(ignored -> compareInput.showDropDown());
        compareLayout.addView(compareInput, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        distanceView = text(13F, true, R.color.fh_primary);
        distanceView.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams distanceParams = matchWrap();
        distanceParams.topMargin = dp(7);
        root.addView(distanceView, distanceParams);

        comparisonSummaryView = text(11.5F, false, R.color.family_map_info_label);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(4);
        root.addView(comparisonSummaryView, summaryParams);

        actionsButton = new MaterialButton(context);
        actionsButton.setText(R.string.family_map_compare_actions);
        actionsButton.setTextSize(12F);
        actionsButton.setTextColor(ContextCompat.getColor(
                context, R.color.fh_primary));
        actionsButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.fh_primary_container)));
        actionsButton.setCornerRadius(dp(14));
        actionsButton.setMinHeight(dp(42));
        actionsButton.setAllCaps(false);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(8);
        root.addView(actionsButton, actionParams);
        actionsButton.setOnClickListener(ignored -> {
            if (listener != null && primaryMember != null) {
                listener.onOpenMemberActions(primaryMember);
            }
        });

        compareInput.setOnItemClickListener((parent, view, position, id) -> {
            if (bindingDropdown || position < 0
                    || position >= comparisonChoices.size()) {
                return;
            }
            FamilyLiveCloudMember member = comparisonChoices.get(position);
            if (listener != null) {
                listener.onCompareMemberSelected(member);
            }
        });
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void bind(
            @NonNull FamilyLiveCloudMember primary,
            @NonNull List<FamilyLiveCloudMember> allMembers,
            @NonNull Map<String, Bitmap> photos,
            @Nullable String comparisonTargetUid
    ) {
        primaryMember = primary;
        String primaryName = displayName(primary);
        nameView.setText(primaryName);
        roleView.setText(getResources().getString(
                R.string.family_map_info_role,
                humanizeRole(primary.role)));

        String availabilityReason = FamilyLiveAvailability.resolve(
                primary,
                System.currentTimeMillis(),
                LIVE_FRESHNESS_MS);
        statusView.setText(FamilyLiveAvailability.labelRes(availabilityReason));
        styleStatus(availabilityReason);

        Bitmap photo = photos.get(primaryName.trim().toLowerCase(Locale.ROOT));
        if (photo != null) {
            avatar.clearColorFilter();
            avatar.setImageBitmap(photo);
        } else {
            avatar.setImageResource(R.drawable.ic_family_map_group);
            avatar.setColorFilter(ContextCompat.getColor(
                    getContext(), R.color.fh_primary));
        }

        String place = safe(primary.placeLabel).isEmpty()
                ? getResources().getString(R.string.family_live_location_unavailable)
                : safe(primary.placeLabel);
        placeView.setText(getResources().getString(
                R.string.family_map_info_place, place));

        String updated = primary.updatedAt <= 0L
                ? getResources().getString(R.string.family_live_update_unavailable)
                : DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(primary.updatedAt));
        updatedView.setText(getResources().getString(
                R.string.family_map_info_updated, updated));

        long accuracy = primary.accuracy > 0D && Double.isFinite(primary.accuracy)
                ? Math.round(primary.accuracy) : 0L;
        accuracyView.setText(getResources().getString(
                R.string.family_map_info_accuracy, accuracy));

        batteryView.setText(getResources().getString(
                R.string.family_map_info_battery,
                batteryLabel(primary)));
        networkView.setText(getResources().getString(
                R.string.family_map_info_network,
                networkLabel(primary, availabilityReason)));
        movementView.setText(getResources().getString(
                R.string.family_map_info_movement,
                movementLabel(primary)));

        comparisonChoices.clear();
        FamilyLiveCloudMember nearest = null;
        double nearestMeters = Double.POSITIVE_INFINITY;
        FamilyLiveCloudMember selectedTarget = null;
        List<String> labels = new ArrayList<>();

        for (FamilyLiveCloudMember candidate : allMembers) {
            if (candidate == null
                    || candidate.uid.equals(primary.uid)
                    || !candidate.sharingEnabled
                    || !candidate.hasLocation
                    || !validCoordinates(candidate.latitude, candidate.longitude)) {
                continue;
            }
            comparisonChoices.add(candidate);
            labels.add(displayName(candidate));

            double meters = FamilyMapDistance.meters(
                    primary.latitude,
                    primary.longitude,
                    candidate.latitude,
                    candidate.longitude);
            if (Double.isFinite(meters) && meters < nearestMeters) {
                nearestMeters = meters;
                nearest = candidate;
            }
            if (comparisonTargetUid != null
                    && comparisonTargetUid.equals(candidate.uid)) {
                selectedTarget = candidate;
            }
        }

        if (nearest == null) {
            nearestView.setVisibility(GONE);
        } else {
            nearestView.setVisibility(VISIBLE);
            nearestView.setText(getResources().getString(
                    R.string.family_map_info_nearest,
                    displayName(nearest),
                    FamilyMapDistance.format(nearestMeters)));
            nearestView.setBackgroundColor(ContextCompat.getColor(
                    getContext(), R.color.fh_secondary_container));
        }

        bindingDropdown = true;
        compareInput.setAdapter(new ArrayAdapter<>(
                getContext(), R.layout.item_form_dropdown, labels));
        if (comparisonChoices.isEmpty()) {
            compareInput.setEnabled(false);
            compareInput.setText(R.string.family_map_compare_no_member, false);
        } else {
            compareInput.setEnabled(true);
            compareInput.setText(selectedTarget == null
                    ? "" : displayName(selectedTarget), false);
        }
        bindingDropdown = false;

        if (selectedTarget == null) {
            distanceView.setText(R.string.family_map_info_compare_hint);
            comparisonSummaryView.setText(R.string.family_map_compare_select_summary);
        } else {
            double meters = FamilyMapDistance.meters(
                    primary.latitude,
                    primary.longitude,
                    selectedTarget.latitude,
                    selectedTarget.longitude);
            distanceView.setText(getResources().getString(
                    R.string.family_map_compare_distance,
                    primaryName,
                    FamilyMapDistance.format(meters)));
            boolean isNearest = nearest != null
                    && nearest.uid.equals(selectedTarget.uid);
            comparisonSummaryView.setText(isNearest
                    ? getResources().getString(
                    R.string.family_map_compare_nearest_summary,
                    displayName(selectedTarget))
                    : getResources().getString(
                    R.string.family_map_compare_member_summary,
                    displayName(selectedTarget),
                    FamilyMapDistance.format(meters)));
        }
    }

    private void styleStatus(@NonNull String availabilityReason) {
        int backgroundColor;
        int textColor;
        if (FamilyLiveAvailability.isCritical(availabilityReason)) {
            backgroundColor = R.color.family_map_critical_container;
            textColor = R.color.family_map_critical;
        } else if (FamilyLiveAvailability.isWarning(availabilityReason)) {
            backgroundColor = R.color.family_map_warning_container;
            textColor = R.color.family_map_warning;
        } else {
            backgroundColor = R.color.family_map_live_container;
            textColor = R.color.family_map_live;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(getContext(), backgroundColor));
        background.setCornerRadius(dp(14));
        statusView.setBackground(background);
        statusView.setTextColor(ContextCompat.getColor(getContext(), textColor));
    }

    @NonNull
    private String batteryLabel(@NonNull FamilyLiveCloudMember member) {
        if (member.batteryPercentage < 0) {
            return getResources().getString(R.string.family_live_unknown);
        }
        return getResources().getString(member.charging
                        ? R.string.family_map_info_battery_charging
                        : R.string.family_map_info_battery_not_charging,
                member.batteryPercentage);
    }

    @NonNull
    private String networkLabel(
            @NonNull FamilyLiveCloudMember member,
            @NonNull String availabilityReason
    ) {
        int connectionState = FamilyLiveAvailability.connectionState(
                availabilityReason, member.online);
        if (connectionState == FamilyLiveAvailability.CONNECTION_CONNECTED) {
            return getResources().getString(R.string.family_live_network_connected);
        }
        if (connectionState == FamilyLiveAvailability.CONNECTION_OFFLINE) {
            return getResources().getString(R.string.family_live_network_off);
        }
        return getResources().getString(R.string.family_live_network_unknown);
    }

    @NonNull
    private String movementLabel(@NonNull FamilyLiveCloudMember member) {
        String movement;
        switch (safe(member.movementType)) {
            case "STATIONARY":
                movement = getResources().getString(
                        R.string.family_live_movement_stationary);
                break;
            case "WALKING":
                movement = getResources().getString(
                        R.string.family_live_movement_walking);
                break;
            case "CYCLING":
                movement = getResources().getString(
                        R.string.family_live_movement_cycling);
                break;
            case "TRAVELLING":
                movement = getResources().getString(
                        R.string.family_live_movement_travelling);
                break;
            default:
                movement = getResources().getString(R.string.family_live_unknown);
                break;
        }
        if (member.speedMetersPerSecond >= 0.3D
                && Double.isFinite(member.speedMetersPerSecond)) {
            return getResources().getString(
                    R.string.family_live_movement_speed,
                    movement,
                    Math.round(member.speedMetersPerSecond * 3.6D));
        }
        return movement;
    }

    @NonNull
    private String humanizeRole(@Nullable String role) {
        String value = safe(role);
        if (value.isEmpty()) {
            return getResources().getString(R.string.family_live_unknown);
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(part.substring(0, 1).toUpperCase(Locale.getDefault()));
            if (part.length() > 1) result.append(part.substring(1));
        }
        return result.toString();
    }

    @NonNull
    private String displayName(@NonNull FamilyLiveCloudMember member) {
        String value = safe(member.displayName);
        return value.isEmpty()
                ? getResources().getString(R.string.family_account_member_fallback)
                : value;
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return FamilyMapNavigationUri.validCoordinates(latitude, longitude);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private TextView detailText(boolean bold) {
        return text(11.5F, bold, bold
                ? R.color.family_map_info_value
                : R.color.family_map_info_label);
    }

    private TextView text(float sizeSp, boolean bold, int colorRes) {
        TextView view = new TextView(getContext());
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(getContext(), colorRes));
        view.setGravity(Gravity.START);
        view.setMaxLines(3);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams detailParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addDivider(@NonNull LinearLayout root) {
        View divider = new View(getContext());
        divider.setBackgroundColor(ContextCompat.getColor(
                getContext(), R.color.family_map_info_border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(9);
        params.bottomMargin = dp(8);
        root.addView(divider, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
