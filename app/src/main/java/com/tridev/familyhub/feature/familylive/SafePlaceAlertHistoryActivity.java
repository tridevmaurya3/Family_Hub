package com.tridev.familyhub.feature.familylive;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.data.repository.SafePlaceAlertRepository;
import com.tridev.familyhub.geofence.FamilySafetyAlertPolicy;
import com.tridev.familyhub.geofence.FamilySafetyAlertPreferences;
import com.tridev.familyhub.geofence.SafePlaceSmartAlertPolicy;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Professional local safety-alert centre with member-scoped controls. */
public final class SafePlaceAlertHistoryActivity extends AppCompatActivity {

    private SafePlaceAlertRepository repository;
    private FamilySafetyAlertPreferences preferences;
    private LinearLayout list;
    private ProgressBar loading;
    private TextView unread;
    private TextView empty;

    private final List<SafePlaceAlert> loadedAlerts = new ArrayList<>();
    private final Map<String, String> loadedPlaceNames = new HashMap<>();
    private String currentFilter = FamilySafetyAlertPolicy.FILTER_ALL;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_safe_place_alert_history);

        repository = new SafePlaceAlertRepository(this);
        preferences = new FamilySafetyAlertPreferences(this);
        list = findViewById(R.id.safePlaceAlertList);
        loading = findViewById(R.id.safePlaceAlertLoading);
        unread = findViewById(R.id.safePlaceAlertUnread);
        empty = findViewById(R.id.safePlaceAlertEmpty);

        findViewById(R.id.buttonSafePlaceAlertBack)
                .setOnClickListener(v -> finish());
        findViewById(R.id.buttonSafePlaceMarkAllRead)
                .setOnClickListener(v -> repository.markAllRead(action()));

        bindMemberScope();
        bindPreferenceControls();
        bindFilters();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    private void bindMemberScope() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String memberName = null;
        if (user != null) {
            memberName = user.getDisplayName();
            if (memberName == null || memberName.trim().isEmpty()) {
                memberName = user.getEmail();
            }
        }
        if (memberName == null || memberName.trim().isEmpty()) {
            memberName = getString(R.string.family_safety_alert_member_fallback);
        }
        TextView member = findViewById(R.id.textSafetyAlertMember);
        member.setText(getString(
                R.string.family_safety_alert_member_scope,
                memberName.trim()
        ));
    }

    private void bindPreferenceControls() {
        MaterialSwitch notifications = findViewById(
                R.id.switchSafetyAlertNotifications
        );
        MaterialSwitch arrived = findViewById(
                R.id.switchSafetyAlertArrived
        );
        MaterialSwitch left = findViewById(
                R.id.switchSafetyAlertLeft
        );
        MaterialSwitch dwell = findViewById(
                R.id.switchSafetyAlertDwell
        );
        MaterialSwitch quiet = findViewById(
                R.id.switchSafetyAlertQuiet
        );

        notifications.setChecked(preferences.notificationsEnabled());
        arrived.setChecked(preferences.arrivedEnabled());
        left.setChecked(preferences.leftEnabled());
        dwell.setChecked(preferences.dwellEnabled());
        quiet.setChecked(preferences.quietHoursEnabled());

        notifications.setOnCheckedChangeListener((button, checked) ->
                preferences.setNotificationsEnabled(checked));
        arrived.setOnCheckedChangeListener((button, checked) ->
                preferences.setArrivedEnabled(checked));
        left.setOnCheckedChangeListener((button, checked) ->
                preferences.setLeftEnabled(checked));
        dwell.setOnCheckedChangeListener((button, checked) ->
                preferences.setDwellEnabled(checked));
        quiet.setOnCheckedChangeListener((button, checked) ->
                preferences.setQuietHoursEnabled(checked));
    }

    private void bindFilters() {
        filterChip(
                R.id.chipSafetyAll,
                FamilySafetyAlertPolicy.FILTER_ALL
        );
        filterChip(
                R.id.chipSafetyUnread,
                FamilySafetyAlertPolicy.FILTER_UNREAD
        );
        filterChip(
                R.id.chipSafetyArrived,
                FamilySafetyAlertPolicy.FILTER_ARRIVED
        );
        filterChip(
                R.id.chipSafetyLeft,
                FamilySafetyAlertPolicy.FILTER_LEFT
        );
        filterChip(
                R.id.chipSafetyDwell,
                FamilySafetyAlertPolicy.FILTER_DWELL
        );
    }

    private void filterChip(int chipId, @NonNull String filter) {
        Chip chip = findViewById(chipId);
        chip.setOnClickListener(v -> {
            currentFilter = FamilySafetyAlertPolicy.normalizeFilter(filter);
            renderHistory();
        });
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        repository.loadHistory(new SafePlaceAlertRepository.HistoryCallback() {
            @Override
            public void onLoaded(
                    @NonNull List<SafePlaceAlert> alerts,
                    int unreadCount,
                    @NonNull Map<String, String> placeNames
            ) {
                loading.setVisibility(View.GONE);
                loadedAlerts.clear();
                loadedAlerts.addAll(alerts);
                loadedPlaceNames.clear();
                loadedPlaceNames.putAll(placeNames);

                unread.setText(getString(
                        R.string.safe_place_alert_unread_count,
                        unreadCount
                ));
                updateSummary(alerts, unreadCount);
                renderHistory();
            }

            @Override
            public void onError() {
                showError();
            }
        });
    }

    private void updateSummary(
            @NonNull List<SafePlaceAlert> alerts,
            int unreadCount
    ) {
        int arrived = 0;
        int left = 0;
        int dwell = 0;
        for (SafePlaceAlert alert : alerts) {
            if (FamilySafetyAlertPolicy.isArrived(alert.transitionType)) {
                arrived++;
            } else if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(
                    alert.transitionType
            )) {
                dwell++;
            } else if (FamilySafetyAlertPolicy.isLeft(
                    alert.transitionType
            )) {
                left++;
            }
        }

        setSummary(
                R.id.summaryTotal,
                alerts.size(),
                R.string.family_safety_alert_total,
                R.color.fh_primary
        );
        setSummary(
                R.id.summaryUnread,
                unreadCount,
                R.string.family_safety_alert_unread,
                R.color.fh_error
        );
        setSummary(
                R.id.summaryArrived,
                arrived,
                R.string.family_safety_alert_arrivals,
                R.color.fh_success
        );
        setSummary(
                R.id.summaryLeft,
                left,
                R.string.family_safety_alert_exits,
                R.color.fh_warning
        );
        setSummary(
                R.id.summaryDwell,
                dwell,
                R.string.family_safety_alert_stays,
                R.color.fh_info
        );
    }

    private void setSummary(
            int rootId,
            int value,
            int labelRes,
            int accentColorRes
    ) {
        View root = findViewById(rootId);
        TextView valueView = root.findViewById(R.id.textSummaryValue);
        TextView labelView = root.findViewById(R.id.textSummaryLabel);
        valueView.setText(getString(
                R.string.family_safety_alert_count_value,
                value
        ));
        valueView.setTextColor(ContextCompat.getColor(
                this,
                accentColorRes
        ));
        labelView.setText(labelRes);
    }

    private void renderHistory() {
        list.removeAllViews();
        int rendered = 0;
        for (SafePlaceAlert alert : loadedAlerts) {
            if (!FamilySafetyAlertPolicy.matchesFilter(
                    alert.transitionType,
                    alert.isRead,
                    currentFilter
            )) {
                continue;
            }
            list.addView(card(alert, loadedPlaceNames));
            rendered++;
        }

        boolean showEmpty = rendered == 0;
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            empty.setText(loadedAlerts.isEmpty()
                    ? R.string.safe_place_alert_history_empty
                    : R.string.family_safety_alert_filtered_empty);
        }
    }

    private View card(
            @NonNull SafePlaceAlert alert,
            @NonNull Map<String, String> placeNames
    ) {
        int strokeColor;
        int backgroundColor;
        int labelRes;

        if (FamilySafetyAlertPolicy.isArrived(alert.transitionType)) {
            strokeColor = R.color.fh_success;
            backgroundColor = R.color.fh_success_container;
            labelRes = R.string.safe_place_alert_arrived;
        } else if (SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(
                alert.transitionType
        )) {
            strokeColor = R.color.fh_info;
            backgroundColor = R.color.fh_info_container;
            labelRes = R.string.safe_place_alert_dwell;
        } else {
            strokeColor = R.color.fh_warning;
            backgroundColor = R.color.fh_warning_container;
            labelRes = R.string.safe_place_alert_left;
        }

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(18));
        card.setStrokeWidth(alert.isRead ? dp(1) : dp(2));
        card.setStrokeColor(ContextCompat.getColor(this, strokeColor));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                backgroundColor
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView type = badge(getString(labelRes), strokeColor);
        header.addView(type);

        TextView readState = new TextView(this);
        LinearLayout.LayoutParams readParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        readState.setLayoutParams(readParams);
        readState.setGravity(Gravity.END);
        readState.setText(alert.isRead
                ? R.string.family_safety_alert_item_read
                : R.string.family_safety_alert_item_unread);
        readState.setTextAppearance(R.style.TextAppearance_FamilyHub_Caption);
        readState.setTextColor(ContextCompat.getColor(
                this,
                alert.isRead ? R.color.fh_text_secondary : strokeColor
        ));
        header.addView(readState);
        body.addView(header);

        String placeName = placeNames.get(alert.placeId);
        if (placeName == null || placeName.trim().isEmpty()) {
            placeName = getString(R.string.safe_place_unknown_name);
        }

        TextView title = new TextView(this);
        title.setText(placeName);
        title.setTextAppearance(R.style.TextAppearance_FamilyHub_CardTitle);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(10);
        title.setLayoutParams(titleParams);
        body.addView(title);

        TextView time = new TextView(this);
        time.setText(getString(
                R.string.family_safety_alert_item_time,
                DateFormat.getDateTimeInstance().format(
                        new Date(alert.occurredAt)
                )
        ));
        time.setTextAppearance(R.style.TextAppearance_FamilyHub_Caption);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timeParams.topMargin = dp(4);
        time.setLayoutParams(timeParams);
        body.addView(time);

        card.addView(body);
        if (!alert.isRead) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v ->
                    repository.markRead(alert.id, action()));
        }
        return card;
    }

    @NonNull
    private TextView badge(@NonNull String value, int colorRes) {
        TextView badge = new TextView(this);
        badge.setText(value);
        badge.setTextSize(11F);
        badge.setTextColor(ContextCompat.getColor(this, colorRes));
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));

        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(
                this,
                R.color.fh_surface
        ));
        background.setCornerRadius(dp(14));
        background.setStroke(
                dp(1),
                ContextCompat.getColor(this, colorRes)
        );
        badge.setBackground(background);
        return badge;
    }

    private SafePlaceAlertRepository.ActionCallback action() {
        return new SafePlaceAlertRepository.ActionCallback() {
            @Override
            public void onComplete() {
                load();
            }

            @Override
            public void onError() {
                showError();
            }
        };
    }

    private void showError() {
        loading.setVisibility(View.GONE);
        Toast.makeText(
                this,
                R.string.safe_place_alert_history_error,
                Toast.LENGTH_LONG
        ).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        repository.close();
        super.onDestroy();
    }
}
