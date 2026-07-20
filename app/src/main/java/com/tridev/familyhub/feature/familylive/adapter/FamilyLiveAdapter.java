package com.tridev.familyhub.feature.familylive.adapter;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.familylive.model.FamilyLiveMemberUiModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Displays Family Live member status com.tridev.familyhub.core.ui.cards.
 */
public class FamilyLiveAdapter
        extends RecyclerView.Adapter<FamilyLiveAdapter.ViewHolder> {

    private static final String STATUS_ONLINE = "Online";

    private final List<FamilyLiveMemberUiModel> members =
            new ArrayList<>();

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

        holder.memberName.setText(member.getMemberName());
        holder.avatar.setText(createInitials(member.getMemberName()));
        holder.location.setText(
                emptyFallback(member.getCurrentLocation(), "Location unavailable")
        );

        holder.status.setText(
                emptyFallback(member.getOnlineStatus(), "Unknown")
        );

        holder.movement.setText(
                emptyFallback(member.getMovementType(), "Status unavailable")
        );

        holder.battery.setText(
                createBatteryText(
                        member.getBatteryPercentage(),
                        member.isCharging()
                )
        );

        holder.connection.setText(
                member.isInternetAvailable()
                        ? "Internet available"
                        : "No internet connection"
        );

        holder.lastUpdated.setText(
                createUpdatedText(member.getLastUpdatedTime())
        );

        applyStatusAppearance(
                holder,
                member.getOnlineStatus()
        );
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    private void applyStatusAppearance(
            @NonNull ViewHolder holder,
            String status
    ) {
        boolean online = status != null
                && STATUS_ONLINE.equalsIgnoreCase(status.trim());

        int statusColor = Color.parseColor(
                online ? "#16875B" : "#C94A4A"
        );

        int statusBackground = Color.parseColor(
                online ? "#E8F5EE" : "#FDECEC"
        );

        holder.status.setTextColor(statusColor);

        ViewCompat.setBackgroundTintList(
                holder.status,
                ColorStateList.valueOf(statusBackground)
        );

        ViewCompat.setBackgroundTintList(
                holder.statusDot,
                ColorStateList.valueOf(statusColor)
        );
    }

    @NonNull
    private String createBatteryText(
            int batteryPercentage,
            boolean charging
    ) {
        if (batteryPercentage < 0) {
            return "Battery unavailable";
        }

        int safeBattery = Math.min(batteryPercentage, 100);

        if (charging) {
            return String.format(
                    Locale.getDefault(),
                    "Charging %d%%",
                    safeBattery
            );
        }

        return String.format(
                Locale.getDefault(),
                "Battery %d%%",
                safeBattery
        );
    }

    @NonNull
    private String createUpdatedText(long updatedTime) {
        if (updatedTime <= 0L) {
            return "Update time unavailable";
        }

        long difference = Math.max(
                0L,
                System.currentTimeMillis() - updatedTime
        );

        long minutes = TimeUnit.MILLISECONDS.toMinutes(difference);

        if (minutes < 1L) {
            return "Updated just now";
        }

        if (minutes < 60L) {
            return "Updated " + minutes + " min ago";
        }

        long hours = TimeUnit.MILLISECONDS.toHours(difference);

        if (hours < 24L) {
            return "Updated " + hours + " hr ago";
        }

        long days = TimeUnit.MILLISECONDS.toDays(difference);
        return "Updated " + days + " day ago";
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
        private final TextView battery;
        private final TextView movement;
        private final TextView connection;
        private final TextView lastUpdated;
        private final View statusDot;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            avatar = itemView.findViewById(R.id.tvAvatar);
            memberName = itemView.findViewById(R.id.tvMemberName);
            location = itemView.findViewById(R.id.tvLocation);
            status = itemView.findViewById(R.id.tvStatus);
            battery = itemView.findViewById(R.id.tvBattery);
            movement = itemView.findViewById(R.id.tvMovement);
            connection = itemView.findViewById(R.id.tvConnection);
            lastUpdated = itemView.findViewById(R.id.tvLastUpdated);
            statusDot = itemView.findViewById(R.id.viewStatusDot);
        }
    }
}