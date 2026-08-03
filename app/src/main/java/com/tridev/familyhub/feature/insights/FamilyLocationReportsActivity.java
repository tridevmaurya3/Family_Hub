package com.tridev.familyhub.feature.insights;

import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.journey.FamilyJourneyPoint;
import com.tridev.familyhub.feature.journey.FamilyJourneyRepository;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Office 365-style privacy-controlled location analytics dashboard. */
public final class FamilyLocationReportsActivity extends AppCompatActivity {

    private final FamilyJourneyRepository repository =
            new FamilyJourneyRepository();
    private final ExecutorService exportExecutor =
            Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pdfDestination =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/pdf"
                    ),
                    uri -> exportReport(uri, true)
            );
    private final ActivityResultLauncher<String> excelDestination =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    ),
                    uri -> exportReport(uri, false)
            );

    private MaterialAutoCompleteTextView memberInput;
    private MaterialButtonToggleGroup periodGroup;
    private TextView rangeView;
    private View progress;
    private TextView emptyView;
    private View content;
    private TextView distanceView;
    private TextView movingView;
    private TextView safeTimeView;
    private TextView activeDaysView;
    private FamilyInsightsBarChartView movementChart;
    private FamilyInsightsBarChartView memberChart;
    private LinearLayout placesList;
    private LinearLayout insightsList;
    private LinearLayout membersList;

    @NonNull private List<FamilyJourneyRepository.Member> accessibleMembers =
            new ArrayList<>();
    @NonNull private List<MemberSelection> selections = new ArrayList<>();
    @Nullable private MemberSelection selectedSelection;
    @Nullable private FamilyLocationReport currentReport;
    @NonNull private FamilyReportRange currentRange =
            FamilyReportRange.weekly(System.currentTimeMillis());
    private long customStart = System.currentTimeMillis();

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_location_reports);

        memberInput = findViewById(R.id.inputReportsMember);
        periodGroup = findViewById(R.id.groupReportsPeriod);
        rangeView = findViewById(R.id.textReportsRange);
        progress = findViewById(R.id.progressReports);
        emptyView = findViewById(R.id.textReportsEmpty);
        content = findViewById(R.id.layoutReportsContent);
        distanceView = findViewById(R.id.textReportsDistance);
        movingView = findViewById(R.id.textReportsMoving);
        safeTimeView = findViewById(R.id.textReportsSafeTime);
        activeDaysView = findViewById(R.id.textReportsActiveDays);
        movementChart = findViewById(R.id.chartReportsMovement);
        memberChart = findViewById(R.id.chartReportsMembers);
        placesList = findViewById(R.id.listReportsPlaces);
        insightsList = findViewById(R.id.listReportsInsights);
        membersList = findViewById(R.id.listReportsMembers);

        findViewById(R.id.buttonReportsBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonReportsRefresh).setOnClickListener(v ->
                loadReport());
        findViewById(R.id.buttonReportsExportPdf).setOnClickListener(v ->
                beginExport(true));
        findViewById(R.id.buttonReportsExportExcel).setOnClickListener(v ->
                beginExport(false));

        periodGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) {
                return;
            }
            long now = System.currentTimeMillis();
            if (checkedId == R.id.buttonReportDaily) {
                currentRange = FamilyReportRange.daily(now);
                updateRangeAndLoad();
            } else if (checkedId == R.id.buttonReportWeekly) {
                currentRange = FamilyReportRange.weekly(now);
                updateRangeAndLoad();
            } else if (checkedId == R.id.buttonReportMonthly) {
                currentRange = FamilyReportRange.monthly(now);
                updateRangeAndLoad();
            } else if (checkedId == R.id.buttonReportCustom) {
                showCustomStartPicker();
            }
        });

        updateRangeLabel();
        resetReport();
        loadMemberAccess();
    }

    private void loadMemberAccess() {
        showLoading();
        repository.loadOverview(new FamilyJourneyRepository.OverviewCallback() {
            @Override
            public void onLoaded(
                    @NonNull FamilyJourneyRepository.Session session,
                    @NonNull List<FamilyJourneyRepository.Member> allMembers,
                    @NonNull List<FamilyJourneyRepository.Member> loadedAccessible,
                    @NonNull FamilyJourneyRepository.PrivacySettings ownSettings
            ) {
                accessibleMembers = new ArrayList<>(loadedAccessible);
                bindMemberOptions();
                loadReport();
            }

            @Override
            public void onError(@NonNull String reason) {
                showError();
            }
        });
    }

    private void bindMemberOptions() {
        selections = new ArrayList<>();
        selections.add(new MemberSelection(null,
                getString(R.string.family_reports_all_accessible)));
        for (FamilyJourneyRepository.Member member : accessibleMembers) {
            selections.add(new MemberSelection(member, member.displayName));
        }
        ArrayAdapter<MemberSelection> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                selections
        );
        memberInput.setAdapter(adapter);
        memberInput.setOnItemClickListener((parent, view, position, id) -> {
            selectedSelection = selections.get(position);
            loadReport();
        });
        selectedSelection = selections.get(0);
        memberInput.setText(selectedSelection.label, false);
    }

    private void updateRangeAndLoad() {
        updateRangeLabel();
        if (!accessibleMembers.isEmpty()) {
            loadReport();
        }
    }

    private void updateRangeLabel() {
        rangeView.setText(getString(
                R.string.family_reports_range,
                currentRange.displayLabel()
        ));
    }

    private void loadReport() {
        if (accessibleMembers.isEmpty()) {
            showError();
            return;
        }
        showLoading();
        List<FamilyJourneyRepository.Member> selected = selectedMembers();
        repository.loadRange(
                selected,
                currentRange,
                new FamilyJourneyRepository.RangeCallback() {
                    @Override
                    public void onLoaded(
                            @NonNull FamilyJourneyRepository.Session session,
                            @NonNull List<FamilyJourneyRepository.Member> members,
                            @NonNull Map<String, List<FamilyJourneyPoint>> points
                    ) {
                        currentReport = FamilyLocationReportAnalyzer.analyze(
                                currentRange,
                                members,
                                points,
                                System.currentTimeMillis()
                        );
                        renderReport(currentReport);
                    }

                    @Override
                    public void onError(@NonNull String reason) {
                        showError();
                    }
                }
        );
    }

    @NonNull
    private List<FamilyJourneyRepository.Member> selectedMembers() {
        MemberSelection selection = selectedSelection;
        if (selection == null || selection.member == null) {
            return new ArrayList<>(accessibleMembers);
        }
        return Collections.singletonList(selection.member);
    }

    private void renderReport(@NonNull FamilyLocationReport report) {
        progress.setVisibility(View.GONE);
        boolean empty = report.activeMemberDays <= 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyView.setText(R.string.family_reports_empty);
        content.setVisibility(View.VISIBLE);

        distanceView.setText(FamilyLocationReportAnalyzer.formatDistance(
                report.totalDistanceMeters));
        movingView.setText(FamilyLocationReportAnalyzer.formatDuration(
                report.totalMovingDurationMs));
        safeTimeView.setText(FamilyLocationReportAnalyzer.formatDuration(
                report.totalSafePlaceDurationMs));
        activeDaysView.setText(String.valueOf(report.activeMemberDays));

        renderMovementChart(report);
        renderMemberChart(report);
        renderPlaces(report);
        renderInsights(report);
        renderMembers(report);
    }

    private void renderMovementChart(@NonNull FamilyLocationReport report) {
        List<String> labels = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        String[] order = {"STATIONARY", "WALKING", "CYCLING",
                "TRAVELLING", "UNKNOWN"};
        for (String type : order) {
            labels.add(titleCase(type));
            Long duration = report.movementDurationMs.get(type);
            values.add(duration == null ? 0F : duration / 60_000F);
        }
        movementChart.setData(labels, values, " min");
    }

    private void renderMemberChart(@NonNull FamilyLocationReport report) {
        List<String> labels = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        for (FamilyLocationReport.MemberReport member : report.members) {
            labels.add(member.displayName);
            values.add((float) (member.totalDistanceMeters / 1_000D));
        }
        memberChart.setData(labels, values, " km");
    }

    private void renderPlaces(@NonNull FamilyLocationReport report) {
        placesList.removeAllViews();
        if (report.familySafePlaces.isEmpty()) {
            placesList.addView(infoCard(
                    getString(R.string.family_reports_no_safe_place),
                    getString(R.string.family_reports_places_detail),
                    R.color.fh_success,
                    R.color.fh_success_container
            ));
            return;
        }
        for (FamilyLocationReport.PlaceStat place : report.familySafePlaces) {
            placesList.addView(infoCard(
                    place.name,
                    getString(
                            R.string.family_reports_place_row,
                            place.name,
                            place.visitCount,
                            FamilyLocationReportAnalyzer.formatDuration(
                                    place.durationMs
                            )
                    ),
                    R.color.fh_success,
                    R.color.fh_success_container
            ));
        }
    }

    private void renderInsights(@NonNull FamilyLocationReport report) {
        insightsList.removeAllViews();
        for (FamilyLocationReport.Insight insight : report.familyInsights) {
            insightsList.addView(insightCard("Family", insight));
        }
        for (FamilyLocationReport.MemberReport member : report.members) {
            for (FamilyLocationReport.Insight insight : member.insights) {
                insightsList.addView(insightCard(member.displayName, insight));
            }
        }
        if (insightsList.getChildCount() == 0) {
            insightsList.addView(infoCard(
                    getString(R.string.family_reports_insights_title),
                    getString(R.string.family_reports_empty),
                    R.color.fh_warning,
                    R.color.fh_warning_container
            ));
        }
    }

    private void renderMembers(@NonNull FamilyLocationReport report) {
        membersList.removeAllViews();
        for (FamilyLocationReport.MemberReport member : report.members) {
            String detail = getString(
                    R.string.family_reports_member_row,
                    FamilyLocationReportAnalyzer.formatDistance(
                            member.totalDistanceMeters
                    ),
                    FamilyLocationReportAnalyzer.formatDuration(
                            member.movingDurationMs
                    ),
                    member.activeDays
            );
            if (!member.mostVisitedPlace.isEmpty()) {
                detail += "\n" + getString(
                        R.string.family_reports_most_visited,
                        member.mostVisitedPlace
                );
            }
            membersList.addView(infoCard(
                    member.displayName + " • " + member.role,
                    detail,
                    R.color.fh_primary,
                    R.color.fh_primary_container
            ));
        }
    }

    @NonNull
    private View insightCard(
            @NonNull String owner,
            @NonNull FamilyLocationReport.Insight insight
    ) {
        int accent = R.color.fh_info;
        int container = R.color.fh_info_container;
        if (FamilyLocationReport.Insight.DELAY.equals(insight.type)
                || FamilyLocationReport.Insight.MISSED_VISIT.equals(
                insight.type)) {
            accent = R.color.fh_warning;
            container = R.color.fh_warning_container;
        }
        return infoCard(owner + " — " + insight.title,
                insight.detail, accent, container);
    }

    @NonNull
    private View infoCard(
            @NonNull String title,
            @NonNull String detail,
            int accentColor,
            int containerColor
    ) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        card.setRadius(dp(16));
        card.setCardElevation(0F);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, accentColor));
        card.setCardBackgroundColor(ContextCompat.getColor(this, containerColor));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15F);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(ContextCompat.getColor(this, accentColor));
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
        return card;
    }

    private void showCustomStartPicker() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(currentRange.startAt);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(year, month, day, 0, 0, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    customStart = selected.getTimeInMillis();
                    showCustomEndPicker();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.setTitle(R.string.family_reports_choose_start);
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.setOnCancelListener(ignored ->
                periodGroup.check(R.id.buttonReportWeekly));
        dialog.show();
    }

    private void showCustomEndPicker() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(customStart);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(year, month, day, 0, 0, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    currentRange = FamilyReportRange.custom(
                            customStart,
                            selected.getTimeInMillis()
                    );
                    updateRangeAndLoad();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.setTitle(R.string.family_reports_choose_end);
        dialog.getDatePicker().setMinDate(customStart);
        Calendar maximum = Calendar.getInstance();
        maximum.setTimeInMillis(customStart);
        maximum.add(Calendar.DAY_OF_YEAR,
                FamilyReportRange.MAX_CUSTOM_DAYS - 1);
        dialog.getDatePicker().setMaxDate(Math.min(
                System.currentTimeMillis(),
                maximum.getTimeInMillis()
        ));
        dialog.setOnCancelListener(ignored ->
                periodGroup.check(R.id.buttonReportWeekly));
        dialog.show();
    }

    private void beginExport(boolean pdf) {
        if (currentReport == null) {
            Toast.makeText(this,
                    R.string.family_reports_export_wait,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String range = currentRange.displayLabel()
                .replace(' ', '_')
                .replace('–', '-');
        if (pdf) {
            pdfDestination.launch("Family_Location_Report_" + range + ".pdf");
        } else {
            excelDestination.launch("Family_Location_Report_" + range + ".xlsx");
        }
    }

    private void exportReport(@Nullable Uri destination, boolean pdf) {
        FamilyLocationReport report = currentReport;
        if (destination == null || report == null) {
            return;
        }
        exportExecutor.execute(() -> {
            try {
                if (pdf) {
                    FamilyLocationReportExporter.writePdf(
                            getApplicationContext(),
                            destination,
                            report
                    );
                } else {
                    FamilyLocationReportExporter.writeXlsx(
                            getApplicationContext(),
                            destination,
                            report
                    );
                }
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.family_reports_export_success,
                        Toast.LENGTH_SHORT
                ).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.family_reports_export_error,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showLoading() {
        currentReport = null;
        progress.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        content.setVisibility(View.INVISIBLE);
    }

    private void showError() {
        currentReport = null;
        progress.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(R.string.family_reports_error);
    }

    private void resetReport() {
        distanceView.setText("0 km");
        movingView.setText("0 min");
        safeTimeView.setText("0 min");
        activeDaysView.setText("0");
        movementChart.setData(Collections.emptyList(),
                Collections.emptyList(), "");
        memberChart.setData(Collections.emptyList(),
                Collections.emptyList(), "");
        placesList.removeAllViews();
        insightsList.removeAllViews();
        membersList.removeAllViews();
    }

    @NonNull
    private String titleCase(@NonNull String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty()
                ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        exportExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class MemberSelection {
        @Nullable final FamilyJourneyRepository.Member member;
        @NonNull final String label;

        MemberSelection(
                @Nullable FamilyJourneyRepository.Member member,
                @NonNull String label
        ) {
            this.member = member;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }
}
