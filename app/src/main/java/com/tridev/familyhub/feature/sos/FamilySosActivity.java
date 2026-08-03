package com.tridev.familyhub.feature.sos;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.tridev.familyhub.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Family SOS Option A: authenticated in-app realtime alert and response centre. */
public final class FamilySosActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FamilySosRepository repository = new FamilySosRepository();

    private MaterialButton holdButton;
    private LinearProgressIndicator holdProgress;
    private ProgressBar loading;
    private LinearLayout activeList;
    private LinearLayout historyList;
    private TextView activeCount;
    private TextView empty;

    private long holdStartedAt;
    private boolean holdInProgress;
    private boolean requestInFlight;
    private boolean ownActiveSos;

    private final Runnable holdTicker = new Runnable() {
        @Override
        public void run() {
            if (!holdInProgress) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - holdStartedAt;
            int progress = (int) Math.min(
                    FamilySosPolicy.HOLD_DURATION_MS,
                    Math.max(0L, elapsed)
            );
            holdProgress.setProgressCompat(progress, true);
            if (elapsed >= FamilySosPolicy.HOLD_DURATION_MS) {
                completeHold();
                return;
            }
            handler.postDelayed(this, 50L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_sos);

        holdButton = findViewById(R.id.buttonFamilySosHold);
        holdProgress = findViewById(R.id.progressFamilySosHold);
        loading = findViewById(R.id.progressFamilySosLoading);
        activeList = findViewById(R.id.listFamilySosActive);
        historyList = findViewById(R.id.listFamilySosHistory);
        activeCount = findViewById(R.id.textFamilySosActiveCount);
        empty = findViewById(R.id.textFamilySosEmpty);

        findViewById(R.id.buttonFamilySosBack)
                .setOnClickListener(v -> finish());
        bindHoldButton();
        observeAlerts();
    }

    private void bindHoldButton() {
        holdButton.setOnClickListener(v -> {
            // Touch-and-hold is required to prevent accidental SOS requests.
        });
        holdButton.setOnTouchListener((view, event) -> {
            if (!holdButton.isEnabled() || requestInFlight || ownActiveSos) {
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startHold(view);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                cancelHold();
                return true;
            }
            return true;
        });
    }

    private void startHold(@NonNull View view) {
        if (holdInProgress) {
            return;
        }
        holdInProgress = true;
        holdStartedAt = SystemClock.uptimeMillis();
        holdProgress.setProgressCompat(0, false);
        holdProgress.setVisibility(View.VISIBLE);
        holdButton.setText(R.string.family_sos_holding_button);
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        handler.post(holdTicker);
    }

    private void cancelHold() {
        if (!holdInProgress) {
            return;
        }
        holdInProgress = false;
        handler.removeCallbacks(holdTicker);
        holdProgress.setProgressCompat(0, false);
        holdProgress.setVisibility(View.INVISIBLE);
        updateHoldButtonState();
    }

    private void completeHold() {
        if (!holdInProgress) {
            return;
        }
        holdInProgress = false;
        handler.removeCallbacks(holdTicker);
        holdProgress.setProgressCompat(
                (int) FamilySosPolicy.HOLD_DURATION_MS,
                true
        );
        holdButton.performHapticFeedback(
                HapticFeedbackConstants.CONFIRM
        );
        requestInFlight = true;
        holdButton.setEnabled(false);
        holdButton.setText(R.string.family_sos_sending);

        repository.requestSos(new FamilySosRepository.ActionCallback() {
            @Override
            public void onSuccess(@Nullable String sosId) {
                requestInFlight = false;
                ownActiveSos = true;
                holdProgress.setVisibility(View.INVISIBLE);
                updateHoldButtonState();
                Toast.makeText(
                        FamilySosActivity.this,
                        R.string.family_sos_sent,
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(@NonNull String reason) {
                requestInFlight = false;
                holdProgress.setVisibility(View.INVISIBLE);
                updateHoldButtonState();
                showError(reason);
            }
        });
    }

    private void observeAlerts() {
        loading.setVisibility(View.VISIBLE);
        repository.observe(new FamilySosRepository.Listener() {
            @Override
            public void onLoaded(
                    @NonNull FamilySosRepository.Session session,
                    @NonNull List<FamilySosAlert> alerts
            ) {
                loading.setVisibility(View.GONE);
                render(session, alerts);
            }

            @Override
            public void onError(@NonNull String reason) {
                loading.setVisibility(View.GONE);
                showError(reason);
            }
        });
    }

    private void render(
            @NonNull FamilySosRepository.Session session,
            @NonNull List<FamilySosAlert> alerts
    ) {
        activeList.removeAllViews();
        historyList.removeAllViews();
        ownActiveSos = false;
        int active = 0;
        int history = 0;

        for (FamilySosAlert alert : alerts) {
            if (FamilySosPolicy.isActive(alert.status)) {
                active++;
                if (session.uid.equals(alert.senderUid)) {
                    ownActiveSos = true;
                }
                activeList.addView(alertCard(session, alert, true));
            } else {
                history++;
                historyList.addView(alertCard(session, alert, false));
            }
        }

        activeCount.setText(getString(
                R.string.family_sos_active_count,
                active
        ));
        empty.setVisibility(
                active == 0 && history == 0 ? View.VISIBLE : View.GONE
        );
        updateHoldButtonState();
    }

    @NonNull
    private View alertCard(
            @NonNull FamilySosRepository.Session session,
            @NonNull FamilySosAlert alert,
            boolean active
    ) {
        int accent = active
                ? R.color.fh_error
                : R.color.fh_text_secondary;
        int container = active
                ? R.color.fh_error_container
                : R.color.fh_surface;

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setRadius(dp(18));
        card.setStrokeWidth(active ? dp(2) : dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, accent));
        card.setCardBackgroundColor(ContextCompat.getColor(this, container));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        title.setLayoutParams(titleParams);
        title.setText(safeName(alert.senderName));
        title.setTextAppearance(R.style.TextAppearance_FamilyHub_CardTitle);
        header.addView(title);
        header.addView(statusBadge(alert.status));
        body.addView(header);

        TextView time = caption(getString(
                R.string.family_sos_created_time,
                DateFormat.getDateTimeInstance().format(
                        new Date(alert.effectiveCreatedAt())
                )
        ));
        addWithTopMargin(body, time, 6);

        TextView location = new TextView(this);
        location.setTextAppearance(R.style.TextAppearance_FamilyHub_BodyStrong);
        if (alert.hasLocation) {
            String place = alert.placeLabel == null
                    || alert.placeLabel.trim().isEmpty()
                    ? getString(R.string.family_sos_latest_location)
                    : alert.placeLabel.trim();
            location.setText(place);
        } else {
            location.setText(R.string.family_sos_location_unavailable);
        }
        addWithTopMargin(body, location, 10);

        if (alert.hasLocation) {
            TextView accuracy = caption(getString(
                    R.string.family_sos_accuracy,
                    alert.accuracy
            ));
            addWithTopMargin(body, accuracy, 3);
        }

        int responseCount = alert.responses == null
                ? 0
                : alert.responses.size();
        TextView responses = caption(getString(
                R.string.family_sos_responses,
                responseCount
        ));
        addWithTopMargin(body, responses, 6);

        if (active || alert.hasLocation) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actionRowParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
            actionRowParams.topMargin = dp(12);
            actions.setLayoutParams(actionRowParams);

            if (alert.hasLocation) {
                MaterialButton map = actionButton(
                        R.string.family_sos_open_map,
                        R.color.fh_primary,
                        R.color.fh_primary_container
                );
                map.setOnClickListener(v -> openMap(alert));
                actions.addView(map, weightedButtonParams());
            }

            if (active && session.uid.equals(alert.senderUid)) {
                MaterialButton cancel = actionButton(
                        R.string.family_sos_cancel,
                        R.color.fh_error,
                        R.color.fh_error_container
                );
                cancel.setOnClickListener(v -> confirmCancel(alert));
                actions.addView(cancel, weightedButtonParams());
            } else if (active) {
                boolean responded = alert.responses != null
                        && alert.hasResponseFrom(session.uid);
                MaterialButton respond = actionButton(
                        responded
                                ? R.string.family_sos_responded
                                : R.string.family_sos_respond,
                        R.color.fh_success,
                        R.color.fh_success_container
                );
                respond.setEnabled(!responded);
                respond.setOnClickListener(v -> respond(alert));
                actions.addView(respond, weightedButtonParams());
            }
            body.addView(actions);
        }

        card.addView(body);
        return card;
    }

    private void confirmCancel(@NonNull FamilySosAlert alert) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.family_sos_cancel_confirm_title)
                .setMessage(R.string.family_sos_cancel_confirm_detail)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(
                        R.string.family_sos_cancel,
                        (dialog, which) -> repository.cancelSos(
                                alert.sosId,
                                action(R.string.family_sos_cancelled_message)
                        )
                )
                .show();
    }

    private void respond(@NonNull FamilySosAlert alert) {
        repository.respondToSos(
                alert.sosId,
                action(R.string.family_sos_response_sent)
        );
    }

    @NonNull
    private FamilySosRepository.ActionCallback action(int successMessage) {
        return new FamilySosRepository.ActionCallback() {
            @Override
            public void onSuccess(@Nullable String sosId) {
                Toast.makeText(
                        FamilySosActivity.this,
                        successMessage,
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(@NonNull String reason) {
                showError(reason);
            }
        };
    }

    private void openMap(@NonNull FamilySosAlert alert) {
        String label = Uri.encode(safeName(alert.senderName) + " SOS");
        Uri uri = Uri.parse(
                "geo:"
                        + alert.latitude
                        + ","
                        + alert.longitude
                        + "?q="
                        + alert.latitude
                        + ","
                        + alert.longitude
                        + "("
                        + label
                        + ")"
        );
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
        mapIntent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(mapIntent);
        } catch (ActivityNotFoundException unavailable) {
            try {
                mapIntent.setPackage(null);
                startActivity(mapIntent);
            } catch (ActivityNotFoundException ignored) {
                showError("MAP_UNAVAILABLE");
            }
        }
    }

    private void updateHoldButtonState() {
        if (requestInFlight) {
            holdButton.setEnabled(false);
            holdButton.setText(R.string.family_sos_sending);
            return;
        }
        if (ownActiveSos) {
            holdButton.setEnabled(false);
            holdButton.setText(R.string.family_sos_status_active);
            return;
        }
        holdButton.setEnabled(true);
        holdButton.setText(R.string.family_sos_hold_button);
    }

    private void showError(@NonNull String reason) {
        int message;
        if ("AUTH_REQUIRED".equals(reason)) {
            message = R.string.family_sos_error_auth;
        } else if ("ACTIVE_FAMILY_REQUIRED".equals(reason)) {
            message = R.string.family_sos_error_family;
        } else if ("SOS_ALREADY_ACTIVE".equals(reason)) {
            ownActiveSos = true;
            updateHoldButtonState();
            message = R.string.family_sos_error_existing;
        } else if ("PLEASE_WAIT".equals(reason)) {
            message = R.string.family_sos_error_wait;
        } else {
            message = R.string.family_sos_error_generic;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @NonNull
    private TextView statusBadge(@Nullable String status) {
        int label;
        int color;
        if (FamilySosPolicy.STATUS_CANCELLED.equals(status)) {
            label = R.string.family_sos_status_cancelled;
            color = R.color.fh_warning;
        } else if (FamilySosPolicy.STATUS_RESOLVED.equals(status)) {
            label = R.string.family_sos_status_resolved;
            color = R.color.fh_success;
        } else {
            label = R.string.family_sos_status_active;
            color = R.color.fh_error;
        }
        TextView badge = new TextView(this);
        badge.setText(label);
        badge.setTextSize(11F);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setTextColor(ContextCompat.getColor(this, color));
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.fh_surface));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), ContextCompat.getColor(this, color));
        badge.setBackground(background);
        return badge;
    }

    @NonNull
    private MaterialButton actionButton(
            int textRes,
            int textColorRes,
            int backgroundColorRes
    ) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setText(textRes);
        button.setTextSize(12F);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setTextColor(ContextCompat.getColor(this, textColorRes));
        button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, backgroundColorRes)
        ));
        button.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(this, textColorRes)
        ));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(16));
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1F
        );
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        return params;
    }

    @NonNull
    private TextView caption(@NonNull String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextAppearance(R.style.TextAppearance_FamilyHub_Caption);
        return text;
    }

    private void addWithTopMargin(
            @NonNull LinearLayout parent,
            @NonNull View child,
            int topMarginDp
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        child.setLayoutParams(params);
        parent.addView(child);
    }

    @NonNull
    private String safeName(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.family_sos_member_fallback);
        }
        return value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        repository.close();
        super.onDestroy();
    }
}
