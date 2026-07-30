package com.tridev.familyhub.feature.familylive;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.SafePlaceAlert;
import com.tridev.familyhub.data.repository.SafePlaceAlertRepository;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class SafePlaceAlertHistoryActivity extends AppCompatActivity {
    private SafePlaceAlertRepository repository;
    private LinearLayout list;
    private ProgressBar loading;
    private TextView unread;
    private TextView empty;

    @Override protected void onCreate(@Nullable Bundle state) {
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

    @Override protected void onStart() {
        super.onStart();
        load();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        repository.loadHistory(new SafePlaceAlertRepository.HistoryCallback() {
            @Override public void onLoaded(
                    List<SafePlaceAlert> alerts,
                    int unreadCount
            ) {
                loading.setVisibility(View.GONE);
                unread.setText(getString(
                        R.string.safe_place_alert_unread_count,
                        unreadCount
                ));
                list.removeAllViews();
                empty.setVisibility(alerts.isEmpty()
                        ? View.VISIBLE : View.GONE);
                for (SafePlaceAlert alert : alerts) {
                    list.addView(card(alert));
                }
            }
            @Override public void onError() {
                showError();
            }
        });
    }

    private View card(SafePlaceAlert alert) {
        boolean entered = "ENTER".equals(alert.transitionType);
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        card.setRadius(dp(18));
        card.setStrokeWidth(alert.isRead ? dp(1) : dp(2));
        card.setStrokeColor(ContextCompat.getColor(
                this,
                entered ? R.color.fh_success : R.color.fh_warning
        ));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                entered ? R.color.fh_success_container
                        : R.color.fh_warning_container
        ));
        TextView text = new TextView(this);
        text.setPadding(dp(16), dp(14), dp(16), dp(14));
        text.setText(getString(
                R.string.safe_place_alert_history_item,
                getString(entered ? R.string.safe_place_entered
                        : R.string.safe_place_exited),
                alert.placeId,
                DateFormat.getDateTimeInstance().format(
                        new Date(alert.occurredAt)),
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

    private SafePlaceAlertRepository.ActionCallback action() {
        return new SafePlaceAlertRepository.ActionCallback() {
            @Override public void onComplete() { load(); }
            @Override public void onError() { showError(); }
        };
    }

    private void showError() {
        loading.setVisibility(View.GONE);
        Toast.makeText(this, R.string.safe_place_alert_history_error,
                Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        repository.close();
        super.onDestroy();
    }
}
