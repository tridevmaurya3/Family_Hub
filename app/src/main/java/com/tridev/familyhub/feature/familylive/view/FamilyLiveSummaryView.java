package com.tridev.familyhub.feature.familylive.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.model.FamilyLiveMemberData;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Premium Family Live status header.
 *
 * It independently observes the same offline-first repository used by the
 * Family Live screen, so the safety summary stays accurate even while the
 * member list is searched or filtered.
 */
public final class FamilyLiveSummaryView extends FrameLayout {

    private static final long LIVE_FRESHNESS_MS = 3L * 60L * 1000L;

    private MaterialCardView summarySurface;
    private MaterialCardView summaryIconContainer;
    private MaterialCardView attentionMetric;
    private ImageView summaryIcon;
    private ImageView syncIcon;
    private TextView safetyState;
    private TextView safetyDetail;
    private TextView lastSync;
    private TextView totalValue;
    private TextView liveValue;
    private TextView travellingValue;
    private TextView attentionValue;
    private TextView attentionLabel;

    @Nullable
    private FamilyLiveRepository repository;
    private boolean cloudDataReceived;

    public FamilyLiveSummaryView(@NonNull Context context) {
        this(context, null);
    }

    public FamilyLiveSummaryView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public FamilyLiveSummaryView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(@NonNull Context context) {
        LayoutInflater.from(context).inflate(
                R.layout.view_family_live_summary,
                this,
                true
        );

        summarySurface = findViewById(R.id.familyLiveSummarySurface);
        summaryIconContainer = findViewById(
                R.id.familyLiveSummaryIconContainer
        );
        attentionMetric = findViewById(R.id.familyLiveAttentionMetric);
        summaryIcon = findViewById(R.id.familyLiveSummaryIcon);
        syncIcon = findViewById(R.id.familyLiveSyncIcon);
        safetyState = findViewById(R.id.tvFamilySafetyState);
        safetyDetail = findViewById(R.id.tvFamilySafetyDetail);
        lastSync = findViewById(R.id.tvFamilyLastSync);
        totalValue = findViewById(R.id.tvSummaryTotal);
        liveValue = findViewById(R.id.tvSummaryLive);
        travellingValue = findViewById(R.id.tvSummaryTravelling);
        attentionValue = findViewById(R.id.tvSummaryAttention);
        attentionLabel = findViewById(R.id.tvSummaryAttentionLabel);

        renderSummary(0, 0, 0, 0, 0L);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) {
            return;
        }

        closeRepository();
        cloudDataReceived = false;
        repository = new FamilyLiveRepository(
                getContext().getApplicationContext()
        );

        repository.loadMemberStatuses(memberStatuses -> {
            if (repository == null || cloudDataReceived) {
                return;
            }
            renderLocalSummary(memberStatuses);
        });

        repository.observeCloudMembers(
                members -> {
                    if (repository == null) {
                        return;
                    }
                    cloudDataReceived = true;
                    renderCloudSummary(members);
                },
                error -> {
                    // Local Room data remains visible when cloud sync is not
                    // currently available. The main screen already surfaces
                    // the detailed sync error to the user.
                }
        );
    }

    @Override
    protected void onDetachedFromWindow() {
        closeRepository();
        super.onDetachedFromWindow();
    }

    private void renderCloudSummary(
            @NonNull List<FamilyLiveCloudMember> members
    ) {
        long now = System.currentTimeMillis();
        int live = 0;
        int travelling = 0;
        int attention = 0;
        long latestUpdate = 0L;

        for (FamilyLiveCloudMember member : members) {
            String reason = FamilyLiveAvailability.resolve(
                    member,
                    now,
                    LIVE_FRESHNESS_MS
            );
            boolean available = FamilyLiveAvailability.isAvailable(reason);

            if (available) {
                live++;
                if ("TRAVELLING".equalsIgnoreCase(member.movementType)) {
                    travelling++;
                }
            }

            if (FamilyLiveAvailability.needsAttention(
                    reason,
                    member.batteryPercentage,
                    member.charging
            )) {
                attention++;
            }

            latestUpdate = Math.max(latestUpdate, member.updatedAt);
        }

        renderSummary(
                members.size(),
                live,
                travelling,
                attention,
                latestUpdate
        );
    }

    private void renderLocalSummary(
            @NonNull List<FamilyLiveMemberData> members
    ) {
        int live = 0;
        int travelling = 0;
        int attention = 0;
        long latestUpdate = 0L;

        for (FamilyLiveMemberData member : members) {
            boolean available = "ONLINE".equalsIgnoreCase(
                    member.onlineStatus
            );

            if (available) {
                live++;
                if ("TRAVELLING".equalsIgnoreCase(member.movementType)) {
                    travelling++;
                }
            }

            String localReason = available
                    ? FamilyLiveAvailability.AVAILABLE
                    : FamilyLiveAvailability.DEVICE_OFFLINE;
            if (FamilyLiveAvailability.needsAttention(
                    localReason,
                    member.batteryPercentage,
                    member.isCharging
            )) {
                attention++;
            }

            latestUpdate = Math.max(latestUpdate, member.lastUpdatedAt);
        }

        renderSummary(
                members.size(),
                live,
                travelling,
                attention,
                latestUpdate
        );
    }

    private void renderSummary(
            int total,
            int live,
            int travelling,
            int attention,
            long latestUpdate
    ) {
        totalValue.setText(String.valueOf(total));
        liveValue.setText(String.valueOf(live));
        travellingValue.setText(String.valueOf(travelling));
        attentionValue.setText(String.valueOf(attention));
        lastSync.setText(createLastSyncText(latestUpdate));

        if (total == 0) {
            safetyState.setText(R.string.family_live_summary_waiting);
            safetyDetail.setText(
                    R.string.family_live_summary_waiting_detail
            );
            applyHeaderPalette(
                    R.color.fh_module_family,
                    R.color.fh_module_family_container
            );
        } else if (attention == 0) {
            safetyState.setText(R.string.family_live_summary_all_safe);
            safetyDetail.setText(getResources().getString(
                    R.string.family_live_summary_detail,
                    live,
                    travelling,
                    total
            ));
            applyHeaderPalette(
                    R.color.fh_success,
                    R.color.fh_success_container
            );
        } else {
            safetyState.setText(getResources().getQuantityString(
                    R.plurals.family_live_summary_attention_count,
                    attention,
                    attention
            ));
            safetyDetail.setText(getResources().getString(
                    R.string.family_live_summary_detail,
                    live,
                    travelling,
                    total
            ));
            applyHeaderPalette(
                    R.color.fh_error,
                    R.color.fh_error_container
            );
        }

        applyAttentionPalette(attention > 0);
    }

    private void applyHeaderPalette(
            @ColorRes int accentRes,
            @ColorRes int containerRes
    ) {
        int accent = color(accentRes);
        int container = color(containerRes);

        summarySurface.setCardBackgroundColor(color(R.color.fh_surface));
        summarySurface.setStrokeColor(color(R.color.fh_outline));
        summaryIconContainer.setCardBackgroundColor(container);
        summaryIconContainer.setStrokeColor(
                color(R.color.fh_outline_variant)
        );
        summaryIcon.setImageTintList(ColorStateList.valueOf(accent));
        syncIcon.setImageTintList(ColorStateList.valueOf(accent));
        safetyState.setTextColor(accent);
        safetyDetail.setTextColor(color(R.color.fh_text_secondary));
    }

    private void applyAttentionPalette(boolean needsAttention) {
        int foreground = color(needsAttention
                ? R.color.fh_error
                : R.color.fh_success);
        int background = color(needsAttention
                ? R.color.fh_error_container
                : R.color.fh_success_container);

        attentionMetric.setCardBackgroundColor(background);
        attentionMetric.setStrokeColor(
                color(R.color.fh_outline_variant)
        );
        attentionValue.setTextColor(foreground);
        attentionLabel.setTextColor(foreground);
    }

    @NonNull
    private String createLastSyncText(long latestUpdate) {
        if (latestUpdate <= 0L) {
            return getResources().getString(
                    R.string.family_live_summary_sync_none
            );
        }

        long difference = Math.max(
                0L,
                System.currentTimeMillis() - latestUpdate
        );
        long minutes = TimeUnit.MILLISECONDS.toMinutes(difference);

        if (minutes < 1L) {
            return getResources().getString(
                    R.string.family_live_summary_sync_now
            );
        }
        if (minutes < 60L) {
            return getResources().getString(
                    R.string.family_live_summary_sync_minutes,
                    minutes
            );
        }

        long hours = TimeUnit.MILLISECONDS.toHours(difference);
        if (hours < 24L) {
            return getResources().getString(
                    R.string.family_live_summary_sync_hours,
                    hours
            );
        }

        String date = DateFormat.getDateInstance(
                DateFormat.MEDIUM
        ).format(new Date(latestUpdate));
        return getResources().getString(
                R.string.family_live_summary_sync_date,
                date
        );
    }

    private int color(@ColorRes int colorRes) {
        return ContextCompat.getColor(getContext(), colorRes);
    }

    private void closeRepository() {
        if (repository == null) {
            return;
        }
        repository.close();
        repository = null;
    }
}
