package com.tridev.familyhub.feature.familylive.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Displays premium Family Live member cards with truthful availability,
 * movement, battery, network and freshness states.
 */
public class FamilyLiveAdapter
        extends RecyclerView.Adapter<FamilyLiveAdapter.ViewHolder> {

    public interface OnMemberClickListener {
        void onMemberClick(@NonNull FamilyLiveMemberUiModel member);
    }

    private final List<FamilyLiveMemberUiModel> members =
            new ArrayList<>();

    @Nullable
    private OnMemberClickListener onMemberClickListener;

    public void setOnMemberClickListener(
            @Nullable OnMemberClickListener listener
    ) {
        onMemberClickListener = listener;
    }

    public void submitList(
            @NonNull List<FamilyLiveMemberUiModel> memberList
    ) {
        members.clear();
        members.addAll(memberList);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        View view = LayoutInflater
                .from(parent.getContext())
                .inflate(
                        R.layout.item_family_live,
                        parent,
                        false
                );

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder,
            int position
    ) {
        FamilyLiveMemberUiModel member = members.get(position);
        Context context = holder.itemView.getContext();
        String reason = FamilyLiveAvailability.normalize(
                member.getAvailabilityReason()
        );

        holder.itemView.setOnClickListener(ignored -> {
            if (onMemberClickListener != null) {
                onMemberClickListener.onMemberClick(member);
            }
        });

        holder.memberName.setText(member.getMemberName());
        holder.avatar.setText(createInitials(member.getMemberName()));

        holder.location.setText(
                emptyFallback(
                        member.getCurrentLocation(),
                        context.getString(
                                R.string.family_live_location_unavailable
                        )
                )
        );

        holder.status.setText(FamilyLiveAvailability.labelRes(reason));
        holder.statusDetail.setText(
                FamilyLiveAvailability.detailRes(reason)
        );

        String movementLabel = displayLabel(
                emptyFallback(
                        member.getMovementType(),
                        context.getString(
                                R.string.family_live_status_unavailable
                        )
                )
        );
        holder.movement.setText(movementLabel);

        holder.battery.setText(
                createBatteryText(
                        context,
                        member.getBatteryPercentage(),
                        member.isCharging()
                )
        );

        int connectionState = FamilyLiveAvailability.connectionState(
                reason,
                member.isInternetAvailable()
        );
        holder.connection.setText(connectionLabel(connectionState));

        holder.lastUpdated.setText(
                createUpdatedText(
                        context,
                        member.getLastUpdatedTime()
                )
        );

        applyStatusAppearance(holder, reason);
        applyMovementAppearance(holder, member.getMovementType());
        applyBatteryAppearance(
                holder,
                member.getBatteryPercentage(),
                member.isCharging()
        );
        applyConnectionAppearance(holder, connectionState);
        applyLowBatteryAlert(
                holder,
                member.getBatteryPercentage(),
                member.isCharging()
        );
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    private void applyStatusAppearance(
            @NonNull ViewHolder holder,
            @NonNull String availabilityReason
    ) {
        Context context = holder.itemView.getContext();

        int foregroundRes;
        int backgroundRes;

        if (FamilyLiveAvailability.isAvailable(availabilityReason)) {
            foregroundRes = R.color.fh_success;
            backgroundRes = R.color.fh_success_container;
        } else if (FamilyLiveAvailability.isWarning(availabilityReason)) {
            foregroundRes = R.color.fh_warning;
            backgroundRes = R.color.fh_warning_container;
        } else if (FamilyLiveAvailability.isPaused(availabilityReason)) {
            foregroundRes = R.color.fh_primary;
            backgroundRes = R.color.fh_primary_container;
        } else {
            foregroundRes = R.color.fh_error;
            backgroundRes = R.color.fh_error_container;
        }

        int foreground = ContextCompat.getColor(
                context,
                foregroundRes
        );
        int background = ContextCompat.getColor(
                context,
                backgroundRes
        );

        holder.card.setCardBackgroundColor(
                ContextCompat.getColor(
                        context,
                        R.color.fh_surface
                )
        );
        holder.card.setStrokeColor(foreground);

        holder.status.setTextColor(foreground);
        holder.statusDetail.setTextColor(foreground);

        ViewCompat.setBackgroundTintList(
                holder.status,
                ColorStateList.valueOf(background)
        );
        ViewCompat.setBackgroundTintList(
                holder.statusDot,
                ColorStateList.valueOf(foreground)
        );
        ViewCompat.setBackgroundTintList(
                holder.avatar,
                ColorStateList.valueOf(foreground)
        );
    }

    private void applyMovementAppearance(
            @NonNull ViewHolder holder,
            String movementType
    ) {
        boolean unavailable = movementType == null
                || movementType.trim().isEmpty()
                || "UNKNOWN".equalsIgnoreCase(movementType);

        applyMetricAppearance(
                holder,
                holder.movementCard,
                holder.movementIcon,
                holder.movement,
                unavailable
                        ? R.color.fh_surface_variant
                        : R.color.fh_module_family_container,
                unavailable
                        ? R.color.fh_text_secondary
                        : R.color.fh_module_family
        );
    }

    private void applyBatteryAppearance(
            @NonNull ViewHolder holder,
            int batteryPercentage,
            boolean charging
    ) {
        int backgroundRes;
        int foregroundRes;

        if (batteryPercentage < 0) {
            backgroundRes = R.color.fh_surface_variant;
            foregroundRes = R.color.fh_text_secondary;
        } else if (charging) {
            backgroundRes = R.color.fh_primary_container;
            foregroundRes = R.color.fh_primary;
        } else if (FamilyLiveAvailability.isLowBattery(
                batteryPercentage,
                false
        )) {
            backgroundRes = R.color.fh_error_container;
            foregroundRes = R.color.fh_error;
        } else if (batteryPercentage <= 40) {
            backgroundRes = R.color.fh_warning_container;
            foregroundRes = R.color.fh_warning;
        } else {
            backgroundRes = R.color.fh_success_container;
            foregroundRes = R.color.fh_success;
        }

        applyMetricAppearance(
                holder,
                holder.batteryCard,
                holder.batteryIcon,
                holder.battery,
                backgroundRes,
                foregroundRes
        );
    }

    private void applyConnectionAppearance(
            @NonNull ViewHolder holder,
            int connectionState
    ) {
        int backgroundRes;
        int foregroundRes;

        if (connectionState
                == FamilyLiveAvailability.CONNECTION_CONNECTED) {
            backgroundRes = R.color.fh_success_container;
            foregroundRes = R.color.fh_success;
        } else if (connectionState
                == FamilyLiveAvailability.CONNECTION_OFFLINE) {
            backgroundRes = R.color.fh_error_container;
            foregroundRes = R.color.fh_error;
        } else {
            backgroundRes = R.color.fh_surface_variant;
            foregroundRes = R.color.fh_text_secondary;
        }

        applyMetricAppearance(
                holder,
                holder.connectionCard,
                holder.connectionIcon,
                holder.connection,
                backgroundRes,
                foregroundRes
        );
    }

    private void applyLowBatteryAlert(
            @NonNull ViewHolder holder,
            int batteryPercentage,
            boolean charging
    ) {
        boolean lowBattery = FamilyLiveAvailability.isLowBattery(
                batteryPercentage,
                charging
        );

        holder.lowBatteryAlert.setVisibility(
                lowBattery ? View.VISIBLE : View.GONE
        );

        if (!lowBattery) {
            return;
        }

        holder.lowBatteryAlert.setText(
                holder.itemView.getContext().getString(
                        R.string.family_live_low_battery_alert,
                        batteryPercentage
                )
        );
        holder.lowBatteryAlert.setTextColor(
                ContextCompat.getColor(
                        holder.itemView.getContext(),
                        R.color.fh_error
                )
        );
        ViewCompat.setBackgroundTintList(
                holder.lowBatteryAlert,
                ColorStateList.valueOf(ContextCompat.getColor(
                        holder.itemView.getContext(),
                        R.color.fh_error_container
                ))
        );
    }

    private void applyMetricAppearance(
            @NonNull ViewHolder holder,
            @NonNull MaterialCardView card,
            @NonNull ImageView icon,
            @NonNull TextView value,
            int backgroundRes,
            int foregroundRes
    ) {
        Context context = holder.itemView.getContext();
        int background = ContextCompat.getColor(
                context,
                backgroundRes
        );
        int foreground = ContextCompat.getColor(
                context,
                foregroundRes
        );

        card.setCardBackgroundColor(background);
        card.setStrokeColor(
                ContextCompat.getColor(
                        context,
                        R.color.fh_outline_variant
                )
        );
        icon.setImageTintList(ColorStateList.valueOf(foreground));
        value.setTextColor(foreground);
    }

    private int connectionLabel(int connectionState) {
        if (connectionState
                == FamilyLiveAvailability.CONNECTION_CONNECTED) {
            return R.string.family_live_network_connected;
        }
        if (connectionState
                == FamilyLiveAvailability.CONNECTION_OFFLINE) {
            return R.string.family_live_network_off;
        }
        return R.string.family_live_network_unknown;
    }

    @NonNull
    private String createBatteryText(
            @NonNull Context context,
            int batteryPercentage,
            boolean charging
    ) {
        if (batteryPercentage < 0) {
            return context.getString(
                    R.string.family_live_battery_unavailable
            );
        }

        int safeBattery = Math.min(
                Math.max(batteryPercentage, 0),
                100
        );

        if (charging) {
            return context.getString(
                    R.string.family_live_charging_format,
                    safeBattery
            );
        }

        return context.getString(
                R.string.family_live_battery_format,
                safeBattery
        );
    }

    @NonNull
    private String createUpdatedText(
            @NonNull Context context,
            long updatedTime
    ) {
        if (updatedTime <= 0L) {
            return context.getString(
                    R.string.family_live_update_unavailable
            );
        }

        long difference = Math.max(
                0L,
                System.currentTimeMillis() - updatedTime
        );
        long minutes = TimeUnit.MILLISECONDS.toMinutes(difference);

        if (minutes < 1L) {
            return context.getString(
                    R.string.family_live_updated_now
            );
        }

        if (minutes < 60L) {
            return context.getString(
                    R.string.family_live_updated_minutes,
                    minutes
            );
        }

        long hours = TimeUnit.MILLISECONDS.toHours(difference);
        if (hours < 24L) {
            return context.getString(
                    R.string.family_live_updated_hours,
                    hours
            );
        }

        long days = TimeUnit.MILLISECONDS.toDays(difference);
        return context.getString(
                R.string.family_live_updated_days,
                days
        );
    }

    @NonNull
    private String displayLabel(@NonNull String value) {
        if (value.contains(" ")) {
            return value;
        }

        String normalized = value
                .replace('_', ' ')
                .toLowerCase(Locale.getDefault());

        if (normalized.isEmpty()) {
            return normalized;
        }

        return normalized.substring(0, 1)
                .toUpperCase(Locale.getDefault())
                + normalized.substring(1);
    }

    @NonNull
    private String createInitials(String memberName) {
        if (memberName == null || memberName.trim().isEmpty()) {
            return "?";
        }

        String[] nameParts = memberName.trim().split("\\s+");

        if (nameParts.length == 1) {
            return nameParts[0]
                    .substring(0, 1)
                    .toUpperCase(Locale.getDefault());
        }

        String firstInitial = nameParts[0].substring(0, 1);
        String lastInitial =
                nameParts[nameParts.length - 1].substring(0, 1);

        return (firstInitial + lastInitial)
                .toUpperCase(Locale.getDefault());
    }

    @NonNull
    private String emptyFallback(
            String value,
            String fallback
    ) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        return value.trim();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView avatar;
        private final TextView memberName;
        private final TextView location;
        private final TextView status;
        private final TextView statusDetail;
        private final TextView lowBatteryAlert;
        private final TextView battery;
        private final TextView movement;
        private final TextView connection;
        private final TextView lastUpdated;

        private final ImageView movementIcon;
        private final ImageView batteryIcon;
        private final ImageView connectionIcon;

        private final View statusDot;

        private final MaterialCardView card;
        private final MaterialCardView movementCard;
        private final MaterialCardView batteryCard;
        private final MaterialCardView connectionCard;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            card = (MaterialCardView) itemView;
            avatar = itemView.findViewById(R.id.tvAvatar);
            memberName = itemView.findViewById(R.id.tvMemberName);
            location = itemView.findViewById(R.id.tvLocation);
            status = itemView.findViewById(R.id.tvStatus);
            statusDetail = itemView.findViewById(R.id.tvStatusDetail);
            lowBatteryAlert = itemView.findViewById(
                    R.id.tvLowBatteryAlert
            );
            battery = itemView.findViewById(R.id.tvBattery);
            movement = itemView.findViewById(R.id.tvMovement);
            connection = itemView.findViewById(R.id.tvConnection);
            lastUpdated = itemView.findViewById(R.id.tvLastUpdated);
            statusDot = itemView.findViewById(R.id.viewStatusDot);

            movementCard = itemView.findViewById(
                    R.id.cardMovementMetric
            );
            batteryCard = itemView.findViewById(
                    R.id.cardBatteryMetric
            );
            connectionCard = itemView.findViewById(
                    R.id.cardConnectionMetric
            );

            movementIcon = itemView.findViewById(
                    R.id.iconMovement
            );
            batteryIcon = itemView.findViewById(
                    R.id.iconBattery
            );
            connectionIcon = itemView.findViewById(
                    R.id.iconConnection
            );
        }
    }
}
