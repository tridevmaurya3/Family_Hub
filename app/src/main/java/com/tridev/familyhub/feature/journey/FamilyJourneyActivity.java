package com.tridev.familyhub.feature.journey;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tridev.familyhub.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Office 365-style Journey History timeline and privacy controls. */
public final class FamilyJourneyActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_PRIVACY =
            "com.tridev.familyhub.extra.OPEN_JOURNEY_PRIVACY";

    private final FamilyJourneyRepository repository =
            new FamilyJourneyRepository();

    private MaterialAutoCompleteTextView memberInput;
    private TextView recordingTitle;
    private TextView recordingDetail;
    private MaterialCardView recordingCard;
    private TextView selectedDateView;
    private TextView distanceView;
    private TextView durationView;
    private TextView pointsView;
    private TextView visitsView;
    private TextView startEndView;
    private View openMapButton;
    private View progress;
    private TextView emptyView;
    private LinearLayout timeline;
    private View deleteActions;

    @Nullable private FamilyJourneyRepository.Session session;
    @Nullable private FamilyJourneyRepository.Member selectedMember;
    @NonNull private List<FamilyJourneyRepository.Member> allMembers =
            new ArrayList<>();
    @NonNull private List<FamilyJourneyRepository.Member> accessibleMembers =
            new ArrayList<>();
    @Nullable private FamilyJourneyRepository.PrivacySettings ownSettings;
    @Nullable private FamilyJourneySummary currentSummary;
    private long selectedDateStart = FamilyJourneyPolicy.startOfDay(
            System.currentTimeMillis()
    );
    private boolean overviewLoading;
    private boolean openPrivacyAfterLoad;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        openPrivacyAfterLoad = getIntent().getBooleanExtra(
                EXTRA_OPEN_PRIVACY,
                false
        );
        setContentView(R.layout.activity_family_journey);

        memberInput = findViewById(R.id.inputJourneyMember);
        recordingTitle = findViewById(R.id.textJourneyRecordingTitle);
        recordingDetail = findViewById(R.id.textJourneyRecordingDetail);
        recordingCard = findViewById(R.id.cardJourneyRecording);
        selectedDateView = findViewById(R.id.textJourneySelectedDate);
        distanceView = findViewById(R.id.textJourneyDistance);
        durationView = findViewById(R.id.textJourneyDuration);
        pointsView = findViewById(R.id.textJourneyPoints);
        visitsView = findViewById(R.id.textJourneyVisits);
        startEndView = findViewById(R.id.textJourneyStartEnd);
        openMapButton = findViewById(R.id.buttonJourneyOpenMap);
        progress = findViewById(R.id.progressJourney);
        emptyView = findViewById(R.id.textJourneyEmpty);
        timeline = findViewById(R.id.listJourneyTimeline);
        deleteActions = findViewById(R.id.layoutJourneyDeleteActions);

        memberInput.setThreshold(0);
        memberInput.setInputType(0);
        memberInput.setOnClickListener(v -> showMemberDropdown());
        memberInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showMemberDropdown();
            }
        });

        findViewById(R.id.buttonJourneyBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonJourneyRefresh).setOnClickListener(v ->
                loadOverview(true));
        findViewById(R.id.buttonJourneyToday).setOnClickListener(v ->
                selectDate(System.currentTimeMillis()));
        findViewById(R.id.buttonJourneyYesterday).setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, -1);
            selectDate(calendar.getTimeInMillis());
        });
        findViewById(R.id.buttonJourneyCustomDate).setOnClickListener(v ->
                showDatePicker());
        recordingCard.setOnClickListener(v -> openJourneySettings());
        openMapButton.setOnClickListener(v -> openRouteMap());
        findViewById(R.id.buttonJourneyDeleteDay).setOnClickListener(v ->
                confirmDeleteDay());
        findViewById(R.id.buttonJourneyDeleteAll).setOnClickListener(v ->
                confirmDeleteAll());

        updateDateLabel();
        resetSummary();
        loadOverview(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (session != null && !overviewLoading) {
            loadOverview(true);
        }
    }

    private void openJourneySettings() {
        if (ownSettings == null || session == null) {
            Toast.makeText(
                    this,
                    R.string.family_journey_settings_loading,
                    Toast.LENGTH_LONG
            ).show();
            loadOverview(true);
            return;
        }
        showPrivacyDialog();
    }

    private void showMemberDropdown() {
        if (accessibleMembers.isEmpty()) {
            if (!overviewLoading) {
                loadOverview(true);
            }
            return;
        }
        memberInput.showDropDown();
    }

    private void loadOverview(boolean preserveSelection) {
        if (overviewLoading) {
            return;
        }
        overviewLoading = true;
        progress.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        repository.loadOverview(new FamilyJourneyRepository.OverviewCallback() {
            @Override
            public void onLoaded(
                    @NonNull FamilyJourneyRepository.Session loadedSession,
                    @NonNull List<FamilyJourneyRepository.Member> loadedAll,
                    @NonNull List<FamilyJourneyRepository.Member> loadedAccessible,
                    @NonNull FamilyJourneyRepository.PrivacySettings settings
            ) {
                overviewLoading = false;
                session = loadedSession;
                allMembers = new ArrayList<>(loadedAll);
                accessibleMembers = new ArrayList<>(loadedAccessible);
                ownSettings = settings;
                memberInput.setEnabled(true);
                recordingCard.setEnabled(true);
                renderRecordingState();
                bindMemberDropdown(preserveSelection);
                if (openPrivacyAfterLoad) {
                    openPrivacyAfterLoad = false;
                    showPrivacyDialog();
                }
            }

            @Override
            public void onError(@NonNull String reason) {
                overviewLoading = false;
                progress.setVisibility(View.GONE);
                currentSummary = null;
                accessibleMembers.clear();
                memberInput.setText("");
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText(R.string.family_journey_error);
                resetSummary();
            }
        });
    }

    private void bindMemberDropdown(boolean preserveSelection) {
        String previousUid = preserveSelection && selectedMember != null
                ? selectedMember.uid
                : "";
        ArrayAdapter<FamilyJourneyRepository.Member> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_dropdown_item_1line,
                        new ArrayList<>(accessibleMembers)
                );
        memberInput.setAdapter(adapter);
        memberInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= accessibleMembers.size()) {
                return;
            }
            selectedMember = accessibleMembers.get(position);
            memberInput.setText(selectedMember.displayName, false);
            loadSelectedDay();
        });

        selectedMember = null;
        for (FamilyJourneyRepository.Member member : accessibleMembers) {
            if ((!previousUid.isEmpty() && previousUid.equals(member.uid))
                    || (previousUid.isEmpty() && member.self)) {
                selectedMember = member;
                break;
            }
        }
        if (selectedMember == null && !accessibleMembers.isEmpty()) {
            selectedMember = accessibleMembers.get(0);
        }

        if (selectedMember == null) {
            progress.setVisibility(View.GONE);
            memberInput.setText("");
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.family_journey_access_denied);
            resetSummary();
            return;
        }
        memberInput.setText(selectedMember.displayName, false);
        openMapButton.setEnabled(true);
        loadSelectedDay();
    }

    private void selectDate(long timestamp) {
        selectedDateStart = FamilyJourneyPolicy.startOfDay(timestamp);
        updateDateLabel();
        loadSelectedDay();
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(selectedDateStart);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(year, month, day, 0, 0, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    selectDate(selected.getTimeInMillis());
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateDateLabel() {
        selectedDateView.setText(getString(
                R.string.family_journey_selected_date,
                DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(new Date(selectedDateStart))
        ));
    }

    private void loadSelectedDay() {
        FamilyJourneyRepository.Member member = selectedMember;
        if (member == null) {
            return;
        }
        progress.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        timeline.removeAllViews();
        openMapButton.setEnabled(true);
        repository.loadDay(
                member.uid,
                FamilyJourneyPolicy.dayKey(selectedDateStart),
                new FamilyJourneyRepository.PointsCallback() {
                    @Override
                    public void onLoaded(
                            @NonNull List<FamilyJourneyPoint> points
                    ) {
                        progress.setVisibility(View.GONE);
                        currentSummary = FamilyJourneySummary.from(points);
                        renderSummary(currentSummary);
                        renderTimeline(currentSummary);
                        renderDeleteActions();
                    }

                    @Override
                    public void onError(@NonNull String reason) {
                        progress.setVisibility(View.GONE);
                        currentSummary = null;
                        resetSummary();
                        openMapButton.setEnabled(true);
                        emptyView.setVisibility(View.VISIBLE);
                        emptyView.setText(
                                "HISTORY_ACCESS_DENIED".equals(reason)
                                        ? R.string.family_journey_access_denied
                                        : R.string.family_journey_error
                        );
                        renderDeleteActions();
                    }
                }
        );
    }

    private void renderRecordingState() {
        FamilyJourneyRepository.PrivacySettings settings = ownSettings;
        boolean enabled = settings != null && settings.historyEnabled;
        int accentRes = enabled ? R.color.fh_success : R.color.fh_warning;
        int containerRes = enabled
                ? R.color.fh_success_container
                : R.color.fh_warning_container;
        int accent = ContextCompat.getColor(this, accentRes);
        recordingCard.setCardBackgroundColor(ContextCompat.getColor(
                this,
                containerRes
        ));
        recordingCard.setStrokeColor(accent);
        recordingTitle.setText(enabled
                ? R.string.family_journey_recording_on
                : R.string.family_journey_recording_off);
        recordingTitle.setTextColor(accent);
        recordingDetail.setText(enabled
                ? getString(
                        R.string.family_journey_recording_on_detail,
                        settings.retentionDays
                )
                : getString(R.string.family_journey_recording_off_detail));
    }

    private void renderSummary(@NonNull FamilyJourneySummary summary) {
        distanceView.setText(formatDistance(summary.totalDistanceMeters));
        durationView.setText(formatDuration(
                Math.max(0L, summary.endedAt - summary.startedAt)
        ));
        pointsView.setText(String.valueOf(summary.points.size()));
        visitsView.setText(String.valueOf(summary.safePlaceVisits.size()));
        String start = summary.startPlace.isEmpty()
                ? getString(R.string.family_journey_no_place)
                : summary.startPlace;
        String end = summary.endPlace.isEmpty()
                ? getString(R.string.family_journey_no_place)
                : summary.endPlace;
        startEndView.setText(getString(
                R.string.family_journey_start_end,
                start,
                end
        ));
        openMapButton.setEnabled(selectedMember != null);
        boolean hasPoints = !summary.points.isEmpty();
        emptyView.setVisibility(hasPoints ? View.GONE : View.VISIBLE);
        emptyView.setText(R.string.family_journey_empty);
    }

    private void renderTimeline(@NonNull FamilyJourneySummary summary) {
        timeline.removeAllViews();
        for (FamilyJourneySummary.MovementSegment segment : summary.segments) {
            addTimelineCard(
                    movementTitle(segment.movementType),
                    getString(
                            R.string.family_journey_segment,
                            time(segment.startedAt) + "–" + time(segment.endedAt),
                            formatDuration(segment.durationMs()),
                            formatDistance(segment.distanceMeters)
                    ),
                    movementAccent(segment.movementType),
                    movementContainer(segment.movementType)
            );
        }
        for (FamilyJourneySummary.SafePlaceVisit visit
                : summary.safePlaceVisits) {
            addTimelineCard(
                    getString(
                            R.string.family_journey_safe_visit,
                            visit.safePlaceName
                    ),
                    getString(
                            R.string.family_journey_safe_visit_detail,
                            time(visit.arrivedAt),
                            time(visit.leftAt),
                            formatDuration(visit.durationMs())
                    ),
                    R.color.fh_success,
                    R.color.fh_success_container
            );
        }
    }

    private void addTimelineCard(
            @NonNull String title,
            @NonNull String detail,
            int accentColorRes,
            int containerColorRes
    ) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        card.setLayoutParams(params);
        card.setRadius(dp(16));
        card.setCardElevation(0F);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, accentColorRes));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                containerColorRes
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15F);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(ContextCompat.getColor(this, accentColorRes));
        content.addView(titleView);

        TextView detailView = new TextView(this);
        detailView.setText(detail);
        detailView.setTextSize(13F);
        detailView.setTextColor(ContextCompat.getColor(
                this,
                R.color.fh_text_secondary
        ));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(4);
        content.addView(detailView, detailParams);
        card.addView(content);
        timeline.addView(card);
    }

    private void showPrivacyDialog() {
        FamilyJourneyRepository.PrivacySettings settings = ownSettings;
        FamilyJourneyRepository.Session activeSession = session;
        if (settings == null || activeSession == null) {
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), 0);

        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText(R.string.family_journey_enable_recording);
        enabled.setChecked(settings.historyEnabled);
        content.addView(enabled);

        TextView retentionLabel = label(R.string.family_journey_retention);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(14);
        content.addView(retentionLabel, labelParams);

        RadioGroup retention = new RadioGroup(this);
        retention.setOrientation(RadioGroup.VERTICAL);
        int[] days = {7, 30, 90};
        int[] labels = {
                R.string.family_journey_retention_7,
                R.string.family_journey_retention_30,
                R.string.family_journey_retention_90
        };
        for (int index = 0; index < days.length; index++) {
            android.widget.RadioButton radio = new android.widget.RadioButton(this);
            radio.setId(10_000 + days[index]);
            radio.setText(labels[index]);
            radio.setTag(days[index]);
            radio.setChecked(settings.retentionDays == days[index]);
            retention.addView(radio);
        }
        content.addView(retention);

        TextView trustedLabel = label(R.string.family_journey_trusted_title);
        LinearLayout.LayoutParams trustedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        trustedParams.topMargin = dp(12);
        content.addView(trustedLabel, trustedParams);

        TextView trustedDetail = new TextView(this);
        trustedDetail.setText(R.string.family_journey_trusted_detail);
        trustedDetail.setTextSize(12F);
        trustedDetail.setTextColor(ContextCompat.getColor(
                this,
                R.color.fh_text_secondary
        ));
        content.addView(trustedDetail);

        Map<String, CheckBox> viewerChecks = new HashMap<>();
        for (FamilyJourneyRepository.Member member : allMembers) {
            if (member.uid.equals(activeSession.uid)) {
                continue;
            }
            CheckBox check = new CheckBox(this);
            check.setText(member.displayName + " • " + member.role);
            check.setChecked(Boolean.TRUE.equals(
                    settings.viewers.get(member.uid)
            ));
            content.addView(check);
            viewerChecks.put(member.uid, check);
        }

        MaterialButton revokeAll = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        revokeAll.setText(R.string.family_journey_revoke_all_viewers);
        revokeAll.setTextSize(12F);
        LinearLayout.LayoutParams revokeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        revokeParams.topMargin = dp(8);
        revokeAll.setLayoutParams(revokeParams);
        revokeAll.setOnClickListener(ignored -> {
            for (CheckBox checkBox : viewerChecks.values()) {
                checkBox.setChecked(false);
            }
        });
        content.addView(revokeAll);

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.family_journey_privacy_title)
                        .setView(content)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(
                                R.string.family_journey_save_privacy,
                                null
                        )
                        .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener(v -> {
            int selectedRetention = 30;
            View checked = retention.findViewById(
                    retention.getCheckedRadioButtonId()
            );
            if (checked != null && checked.getTag() instanceof Integer) {
                selectedRetention = (Integer) checked.getTag();
            }
            Map<String, Boolean> viewers = new HashMap<>();
            for (Map.Entry<String, CheckBox> entry
                    : viewerChecks.entrySet()) {
                viewers.put(entry.getKey(), entry.getValue().isChecked());
            }
            repository.saveOwnPrivacy(
                    enabled.isChecked(),
                    selectedRetention,
                    viewers,
                    new FamilyJourneyRepository.ActionCallback() {
                        @Override
                        public void onSuccess() {
                            dialog.dismiss();
                            Toast.makeText(
                                    FamilyJourneyActivity.this,
                                    R.string.family_journey_privacy_saved,
                                    Toast.LENGTH_SHORT
                            ).show();
                            loadOverview(true);
                        }

                        @Override
                        public void onError(@NonNull String reason) {
                            Toast.makeText(
                                    FamilyJourneyActivity.this,
                                    R.string.family_journey_error,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
            );
        }));
        dialog.show();
    }

    private TextView label(int textRes) {
        TextView view = new TextView(this);
        view.setText(textRes);
        view.setTextSize(14F);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(ContextCompat.getColor(this, R.color.fh_text_primary));
        return view;
    }

    private void openRouteMap() {
        FamilyJourneyRepository.Member member = selectedMember;
        FamilyJourneySummary summary = currentSummary;
        if (member == null) {
            Toast.makeText(
                    this,
                    R.string.family_journey_access_denied,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (summary == null || summary.points.isEmpty()) {
            Toast.makeText(
                    this,
                    R.string.family_journey_map_no_points_action,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        Intent intent = new Intent(this, FamilyJourneyMapActivity.class);
        intent.putExtra(FamilyJourneyMapActivity.EXTRA_MEMBER_UID, member.uid);
        intent.putExtra(FamilyJourneyMapActivity.EXTRA_MEMBER_NAME,
                member.displayName);
        intent.putExtra(FamilyJourneyMapActivity.EXTRA_DAY_KEY,
                FamilyJourneyPolicy.dayKey(selectedDateStart));
        intent.putExtra(FamilyJourneyMapActivity.EXTRA_DATE_LABEL,
                DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(new Date(selectedDateStart)));
        startActivity(intent);
    }

    private void renderDeleteActions() {
        boolean own = selectedMember != null && selectedMember.self;
        deleteActions.setVisibility(own ? View.VISIBLE : View.GONE);
    }

    private void confirmDeleteDay() {
        if (selectedMember == null || !selectedMember.self) {
            Toast.makeText(this,
                    R.string.family_journey_own_only_delete,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.family_journey_delete_day)
                .setMessage(R.string.family_journey_delete_day_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        repository.deleteOwnDay(
                                FamilyJourneyPolicy.dayKey(selectedDateStart),
                                deleteCallback()
                        ))
                .show();
    }

    private void confirmDeleteAll() {
        if (selectedMember == null || !selectedMember.self) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.family_journey_delete_all)
                .setMessage(R.string.family_journey_delete_all_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        repository.deleteAllOwnHistory(deleteCallback()))
                .show();
    }

    private FamilyJourneyRepository.ActionCallback deleteCallback() {
        return new FamilyJourneyRepository.ActionCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(
                        FamilyJourneyActivity.this,
                        R.string.family_journey_deleted,
                        Toast.LENGTH_SHORT
                ).show();
                loadSelectedDay();
            }

            @Override
            public void onError(@NonNull String reason) {
                Toast.makeText(
                        FamilyJourneyActivity.this,
                        R.string.family_journey_error,
                        Toast.LENGTH_LONG
                ).show();
            }
        };
    }

    private void resetSummary() {
        currentSummary = null;
        distanceView.setText("0 km");
        durationView.setText("0 min");
        pointsView.setText("0");
        visitsView.setText("0");
        startEndView.setText("");
        openMapButton.setEnabled(selectedMember != null);
        timeline.removeAllViews();
    }

    @NonNull
    private String movementTitle(@NonNull String movement) {
        String normalized = FamilyJourneyPolicy.normalizeMovement(movement);
        return normalized.substring(0, 1)
                + normalized.substring(1).toLowerCase(Locale.getDefault());
    }

    private int movementAccent(@NonNull String movement) {
        switch (FamilyJourneyPolicy.normalizeMovement(movement)) {
            case "TRAVELLING":
                return R.color.fh_primary;
            case "CYCLING":
                return R.color.fh_secondary;
            case "WALKING":
                return R.color.fh_success;
            case "STATIONARY":
                return R.color.fh_warning;
            default:
                return R.color.fh_info;
        }
    }

    private int movementContainer(@NonNull String movement) {
        switch (FamilyJourneyPolicy.normalizeMovement(movement)) {
            case "TRAVELLING":
                return R.color.fh_primary_container;
            case "CYCLING":
                return R.color.fh_secondary_container;
            case "WALKING":
                return R.color.fh_success_container;
            case "STATIONARY":
                return R.color.fh_warning_container;
            default:
                return R.color.fh_info_container;
        }
    }

    @NonNull
    private String formatDistance(double meters) {
        if (meters < 1000D) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.getDefault(), "%.1f km", meters / 1000D);
    }

    @NonNull
    private String formatDuration(long durationMs) {
        long minutes = Math.max(0L, durationMs / 60_000L);
        long hours = minutes / 60L;
        long remaining = minutes % 60L;
        if (hours > 0L) {
            return hours + "h " + remaining + "m";
        }
        return minutes + " min";
    }

    @NonNull
    private String time(long timestamp) {
        return DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
