package com.tridev.familyhub.feature.safety;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;
import com.tridev.familyhub.data.repository.FamilyLiveRepository;
import com.tridev.familyhub.data.repository.SafePlaceAlertRepository;
import com.tridev.familyhub.feature.automation.FamilyAutomationActivity;
import com.tridev.familyhub.feature.familylive.FamilyLiveAvailability;
import com.tridev.familyhub.feature.familylive.FamilyMapActivity;
import com.tridev.familyhub.feature.familylive.SafePlaceAlertHistoryActivity;
import com.tridev.familyhub.feature.familylive.SafePlacesActivity;
import com.tridev.familyhub.feature.insights.FamilyLocationReportsActivity;
import com.tridev.familyhub.feature.journey.FamilyJourneyActivity;
import com.tridev.familyhub.feature.sos.FamilySosActivity;
import com.tridev.familyhub.feature.sos.FamilySosAlert;
import com.tridev.familyhub.feature.sos.FamilySosPolicy;
import com.tridev.familyhub.feature.sos.FamilySosRepository;

import java.util.List;
import java.util.Map;

/**
 * Unified Office 365-style hub for Family Live safety, SOS, Safe Places,
 * Journey History, Location Insights, Smart Routines and confirmed alerts.
 * No exact location is copied or stored by this overview screen.
 */
public final class FamilySafetyCenterActivity extends AppCompatActivity {

    private static final long MEMBER_FRESHNESS_MS = 10L * 60L * 1000L;

    private final FamilySosRepository sosRepository = new FamilySosRepository();

    private FamilyLiveRepository liveRepository;
    private SafePlaceAlertRepository alertRepository;

    private MaterialCardView overviewCard;
    private ImageView overviewIcon;
    private TextView overviewTitle;
    private TextView overviewDetail;
    private ProgressBar overviewProgress;
    private View refreshButton;
    private TextView sosCountView;
    private TextView attentionCountView;
    private TextView unreadCountView;
    private TextView memberCountView;

    private int activeSosCount;
    private int attentionMemberCount;
    private int unreadAlertCount;
    private int familyMemberCount;

    private boolean sosLoaded;
    private boolean membersLoaded;
    private boolean alertsLoaded;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_safety_center);

        liveRepository = new FamilyLiveRepository(this);
        alertRepository = new SafePlaceAlertRepository(this);

        overviewCard = findViewById(R.id.cardFamilySafetyOverview);
        overviewIcon = findViewById(R.id.imageFamilySafetyOverview);
        overviewTitle = findViewById(R.id.textFamilySafetyOverviewTitle);
        overviewDetail = findViewById(R.id.textFamilySafetyOverviewDetail);
        overviewProgress = findViewById(R.id.progressFamilySafetyOverview);
        refreshButton = findViewById(R.id.buttonFamilySafetyRefresh);
        sosCountView = findViewById(R.id.textFamilySafetySosCount);
        attentionCountView = findViewById(
                R.id.textFamilySafetyAttentionCount
        );
        unreadCountView = findViewById(R.id.textFamilySafetyUnreadCount);
        memberCountView = findViewById(R.id.textFamilySafetyMemberCount);

        findViewById(R.id.buttonFamilySafetyBack)
                .setOnClickListener(v -> finish());
        refreshButton.setOnClickListener(v -> refreshOverview());
        overviewCard.setOnClickListener(v -> openMostRelevantTool());

        bindSummaryCard(sosCountView, FamilySosActivity.class);
        bindSummaryCard(attentionCountView, FamilyMapActivity.class);
        bindSummaryCard(unreadCountView,
                SafePlaceAlertHistoryActivity.class);
        bindSummaryCard(memberCountView, FamilyMapActivity.class);

        findViewById(R.id.cardFamilySafetySos).setOnClickListener(v ->
                open(FamilySosActivity.class));
        findViewById(R.id.cardFamilySafetyMap).setOnClickListener(v ->
                open(FamilyMapActivity.class));
        findViewById(R.id.cardFamilySafetyPlaces).setOnClickListener(v ->
                open(SafePlacesActivity.class));
        View alertCard = findViewById(R.id.cardFamilySafetyAlerts);
        alertCard.setOnClickListener(v ->
                open(SafePlaceAlertHistoryActivity.class));

        View journeyCard = addToolCard(
                alertCard,
                R.string.family_journey_more_button,
                R.string.family_journey_more_description,
                R.drawable.ic_family_map_route,
                R.color.fh_secondary,
                R.color.fh_secondary_container,
                FamilyJourneyActivity.class
        );
        View reportsCard = addToolCard(
                journeyCard == null ? alertCard : journeyCard,
                R.string.family_reports_safety_title,
                R.string.family_reports_safety_detail,
                R.drawable.ic_family_map_route,
                R.color.fh_info,
                R.color.fh_info_container,
                FamilyLocationReportsActivity.class
        );
        addToolCard(
                reportsCard == null
                        ? (journeyCard == null ? alertCard : journeyCard)
                        : reportsCard,
                R.string.family_automation_safety_title,
                R.string.family_automation_safety_detail,
                R.drawable.ic_family_automation,
                R.color.fh_warning,
                R.color.fh_warning_container,
                FamilyAutomationActivity.class
        );

        renderCounts();
        renderOverview();
    }

    private void bindSummaryCard(
            @NonNull TextView countView,
            @NonNull Class<?> activityClass
    ) {
        MaterialCardView card = findParentCard(countView);
        if (card == null) {
            countView.setOnClickListener(v -> open(activityClass));
            return;
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(ContextCompat.getDrawable(
                this,
                android.R.drawable.list_selector_background
        ));
        card.setOnClickListener(v -> open(activityClass));
    }

    @Nullable
    private MaterialCardView findParentCard(@NonNull View child) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent instanceof MaterialCardView) {
                return (MaterialCardView) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /** Adds a compact safety tool card immediately after the supplied anchor. */
    @Nullable
    private View addToolCard(
            @NonNull View anchor,
            int titleRes,
            int detailRes,
            int iconRes,
            int accentColorRes,
            int containerColorRes,
            @NonNull Class<?> activityClass
    ) {
        if (!(anchor.getParent() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(18));
        card.setCardElevation(0F);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, accentColorRes));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                containerColorRes
        ));
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(ContextCompat.getDrawable(
                this,
                android.R.drawable.list_selector_background
        ));
        card.setOnClickListener(v -> open(activityClass));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(44),
                dp(44)
        );
        icon.setLayoutParams(iconParams);
        icon.setBackgroundResource(R.drawable.bg_placeholder_icon);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                accentColorRes
        )));
        row.addView(icon);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        copyParams.leftMargin = dp(12);
        row.addView(copy, copyParams);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(16F);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, accentColorRes));
        copy.addView(title);

        TextView detail = new TextView(this);
        detail.setText(detailRes);
        detail.setTextSize(13F);
        detail.setTextColor(ContextCompat.getColor(
                this,
                R.color.fh_text_secondary
        ));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(2);
        copy.addView(detail, detailParams);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                accentColorRes
        )));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));

        card.addView(row);
        int index = parent.indexOfChild(anchor);
        parent.addView(card, Math.min(index + 1, parent.getChildCount()));
        return card;
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshOverview();
    }

    private void refreshOverview() {
        sosLoaded = false;
        membersLoaded = false;
        alertsLoaded = false;
        overviewProgress.setVisibility(View.VISIBLE);
        refreshButton.setEnabled(false);
        overviewTitle.setText(R.string.family_safety_overview_loading);
        overviewDetail.setText(R.string.family_safety_center_subtitle);

        observeSos();
        observeMembers();
        loadUnreadAlerts();
    }

    private void observeSos() {
        sosRepository.observe(new FamilySosRepository.Listener() {
            @Override
            public void onLoaded(
                    @NonNull FamilySosRepository.Session session,
                    @NonNull List<FamilySosAlert> alerts
            ) {
                int count = 0;
                for (FamilySosAlert alert : alerts) {
                    if (FamilySosPolicy.isActive(alert.status)) {
                        count++;
                    }
                }
                activeSosCount = count;
                sosLoaded = true;
                renderAll();
            }

            @Override
            public void onError(@NonNull String reason) {
                activeSosCount = 0;
                sosLoaded = true;
                renderAll();
            }
        });
    }

    private void observeMembers() {
        liveRepository.observeCloudMembers(members -> {
            familyMemberCount = members.size();
            attentionMemberCount = countAttentionMembers(members);
            membersLoaded = true;
            renderAll();
        }, error -> {
            familyMemberCount = 0;
            attentionMemberCount = 0;
            membersLoaded = true;
            renderAll();
        });
    }

    private void loadUnreadAlerts() {
        alertRepository.loadHistory(
                new SafePlaceAlertRepository.HistoryCallback() {
                    @Override
                    public void onLoaded(
                            @NonNull List<SafePlaceAlert> alerts,
                            int unreadCount,
                            @NonNull Map<String, String> placeNames
                    ) {
                        unreadAlertCount = Math.max(0, unreadCount);
                        alertsLoaded = true;
                        renderAll();
                    }

                    @Override
                    public void onError() {
                        unreadAlertCount = 0;
                        alertsLoaded = true;
                        renderAll();
                    }
                }
        );
    }

    private int countAttentionMembers(
            @NonNull List<FamilyLiveCloudMember> members
    ) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (FamilyLiveCloudMember member : members) {
            String reason = FamilyLiveAvailability.resolve(
                    member,
                    now,
                    MEMBER_FRESHNESS_MS
            );
            if (FamilyLiveAvailability.needsAttention(
                    reason,
                    member.batteryPercentage,
                    member.charging
            )) {
                count++;
            }
        }
        return count;
    }

    private void renderAll() {
        renderCounts();
        renderOverview();
        boolean loaded = sosLoaded && membersLoaded && alertsLoaded;
        overviewProgress.setVisibility(loaded ? View.GONE : View.VISIBLE);
        refreshButton.setEnabled(loaded);
    }

    private void renderCounts() {
        sosCountView.setText(String.valueOf(Math.max(0, activeSosCount)));
        attentionCountView.setText(String.valueOf(
                Math.max(0, attentionMemberCount)
        ));
        unreadCountView.setText(String.valueOf(Math.max(0, unreadAlertCount)));
        memberCountView.setText(String.valueOf(Math.max(0, familyMemberCount)));
    }

    private void renderOverview() {
        String state = FamilySafetyOverviewPolicy.resolve(
                activeSosCount,
                attentionMemberCount,
                unreadAlertCount
        );
        if (FamilySafetyOverviewPolicy.STATE_EMERGENCY.equals(state)) {
            applyOverviewStyle(
                    R.color.fh_error_container,
                    R.color.fh_error,
                    R.drawable.ic_family_sos,
                    R.string.family_safety_overview_emergency_title,
                    R.string.family_safety_overview_emergency_detail
            );
            return;
        }
        if (FamilySafetyOverviewPolicy.STATE_ATTENTION.equals(state)) {
            applyOverviewStyle(
                    R.color.fh_warning_container,
                    R.color.fh_warning,
                    R.drawable.ic_safe_place_alert_history,
                    R.string.family_safety_overview_attention_title,
                    R.string.family_safety_overview_attention_detail
            );
            return;
        }
        applyOverviewStyle(
                R.color.fh_success_container,
                R.color.fh_success,
                R.drawable.ic_safe_place_shield,
                R.string.family_safety_overview_all_clear_title,
                R.string.family_safety_overview_all_clear_detail
        );
    }

    private void applyOverviewStyle(
            int containerColorRes,
            int accentColorRes,
            int iconRes,
            int titleRes,
            int detailRes
    ) {
        int accent = ContextCompat.getColor(this, accentColorRes);
        overviewCard.setCardBackgroundColor(ContextCompat.getColor(
                this,
                containerColorRes
        ));
        overviewCard.setStrokeColor(accent);
        overviewIcon.setImageResource(iconRes);
        overviewIcon.setImageTintList(ColorStateList.valueOf(accent));
        overviewTitle.setText(titleRes);
        overviewTitle.setTextColor(accent);
        overviewDetail.setText(detailRes);
    }

    private void openMostRelevantTool() {
        if (activeSosCount > 0) {
            open(FamilySosActivity.class);
            return;
        }
        if (attentionMemberCount > 0) {
            open(FamilyMapActivity.class);
            return;
        }
        if (unreadAlertCount > 0) {
            open(SafePlaceAlertHistoryActivity.class);
            return;
        }
        open(FamilyMapActivity.class);
    }

    private void open(@NonNull Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        sosRepository.close();
        liveRepository.close();
        alertRepository.close();
        super.onDestroy();
    }
}
