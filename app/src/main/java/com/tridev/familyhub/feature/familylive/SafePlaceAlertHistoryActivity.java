package com.tridev.familyhub.feature.familylive;

import android.os.Bundle;
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
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.data.repository.SafePlaceAlertRepository;
import com.tridev.familyhub.geofence.SafePlaceSmartAlertPolicy;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class SafePlaceAlertHistoryActivity extends AppCompatActivity {
    private SafePlaceAlertRepository repository;
    private LinearLayout list;
    private ProgressBar loading;
    private TextView unread;
    private TextView empty;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_safe_place_alert_history);
        repository = new SafePlaceAlertRepository(this);
        list = findViewById(R.id.safePlaceAlertList);
        loading = findViewById(R.id.safePlaceAlertLoading);
        unread = findViewById(R.id.safePlaceAlertUnread);
        empty = findViewById(R.id.safePlaceAlertEmpty);
        findViewById(R.id.buttonSafePlaceAlertBack)
                .setOnClickListener(v -> finish());
        findViewById(R.id.buttonSafePlaceMarkAllRead)
                .setOnClickListener(v -> repository.markAllRead(action()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
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
                unread.setText(getString(
                        R.string.safe_place_alert_unread_count,
                        unreadCount
                ));
                list.removeAllViews();
                empty.setVisibility(alerts.isEmpty()
                        ? View.VISIBLE
                        : View.GONE);
                for (SafePlaceAlert alert : alerts) {
                    list.addView(card(alert, placeNames));
                }
            }

            @Override
            public void onError() {
                showError();
            }
        });
    }

    private View card(
            @NonNull SafePlaceAlert alert,
            @NonNull Map<String, String> placeNames
    ) {
        int strokeColor;
        int backgroundColor;
        int labelRes;

        if (isArrived(alert.transitionType)) {
            strokeColor = R.color.fh_success;
            backgroundColor = R.color.fh_success_container;
            labelRes = R.string.safe_place_alert_arrived;
        } else if (isDwell(alert.transitionType)) {
            strokeColor = R.color.fh_info;
            backgroundColor = R.color.fh_info_container;
            labelRes = R.string.safe_place_alert_dwell;
        } else {
            strokeColor = R.color.fh_warning;
            backgroundColor = R.color.fh_warning_container;
            labelRes = R.string.safe_place_alert_left;
        }

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        card.setRadius(dp(18));
        card.setStrokeWidth(alert.isRead ? dp(1) : dp(2));
        card.setStrokeColor(ContextCompat.getColor(this, strokeColor));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                backgroundColor
        ));

        String placeName = placeNames.get(alert.placeId);
        if (placeName == null || placeName.trim().isEmpty()) {
            placeName = getString(R.string.safe_place_unknown_name);
        }

        TextView text = new TextView(this);
        text.setPadding(dp(16), dp(14), dp(16), dp(14));
        text.setText(getString(
                R.string.safe_place_alert_history_item,
                getString(labelRes),
                placeName,
                DateFormat.getDateTimeInstance().format(
                        new Date(alert.occurredAt)
                ),
                getString(alert.isRead
                        ? R.string.safe_place_alert_read
                        : R.string.safe_place_alert_unread)
        ));
        text.setTextAppearance(R.style.TextAppearance_FamilyHub_Body);
        card.addView(text);

        if (!alert.isRead) {
            card.setOnClickListener(v ->
                    repository.markRead(alert.id, action()));
        }
        return card;
    }

    private boolean isArrived(@Nullable String transitionType) {
        return SafePlaceSmartAlertPolicy.ALERT_ARRIVED.equals(transitionType)
                || "ENTER".equals(transitionType);
    }

    private boolean isDwell(@Nullable String transitionType) {
        return SafePlaceSmartAlertPolicy.ALERT_DWELL.equals(transitionType);
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
