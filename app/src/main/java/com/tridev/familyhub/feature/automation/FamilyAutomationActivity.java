package com.tridev.familyhub.feature.automation;

import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.SafePlace;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Office 365-style management screen for Phase 6 family automations. */
public final class FamilyAutomationActivity extends AppCompatActivity {

    private final FamilyAutomationRepository repository =
            new FamilyAutomationRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private View progress;
    private TextView errorView;
    private TextView activeCountView;
    private TextView scheduleCountView;
    private TextView eventCountView;
    private LinearLayout rulesList;
    private LinearLayout eventsList;
    private View addRoutineButton;
    private View addScheduleButton;

    @Nullable private FamilyAutomationRepository.Session session;
    @NonNull private List<FamilyAutomationRepository.Member> allMembers =
            new ArrayList<>();
    @NonNull private List<FamilyAutomationRepository.Member> manageableMembers =
            new ArrayList<>();
    @NonNull private List<FamilyAutomationRule> rules = new ArrayList<>();
    @NonNull private List<FamilyAutomationEvent> events = new ArrayList<>();
    @NonNull private List<SafePlace> safePlaces = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_family_automation);

        progress = findViewById(R.id.progressAutomation);
        errorView = findViewById(R.id.textAutomationError);
        activeCountView = findViewById(R.id.textAutomationActiveCount);
        scheduleCountView = findViewById(R.id.textAutomationScheduleCount);
        eventCountView = findViewById(R.id.textAutomationEventCount);
        rulesList = findViewById(R.id.listAutomationRules);
        eventsList = findViewById(R.id.listAutomationEvents);
        addRoutineButton = findViewById(R.id.buttonAutomationAddRoutine);
        addScheduleButton = findViewById(R.id.buttonAutomationAddSchedule);

        findViewById(R.id.buttonAutomationBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonAutomationRefresh).setOnClickListener(v ->
                loadOverview());
        addRoutineButton.setOnClickListener(v -> openEditor(null, false));
        addScheduleButton.setOnClickListener(v -> openEditor(null, true));

        addRoutineButton.setEnabled(false);
        addScheduleButton.setEnabled(false);
        loadSafePlaces();
    }

    @Override
    protected void onStart() {
        super.onStart();
        loadOverview();
    }

    private void loadSafePlaces() {
        executor.execute(() -> {
            List<SafePlace> loaded = FamilyHubDatabase.getInstance(this)
                    .safePlaceDao()
                    .getAll();
            runOnUiThread(() -> safePlaces = new ArrayList<>(loaded));
        });
    }

    private void loadOverview() {
        progress.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        repository.loadOverview(new FamilyAutomationRepository.OverviewCallback() {
            @Override
            public void onLoaded(
                    @NonNull FamilyAutomationRepository.Session loadedSession,
                    @NonNull List<FamilyAutomationRepository.Member> loadedAll,
                    @NonNull List<FamilyAutomationRepository.Member> visibleMembers,
                    @NonNull List<FamilyAutomationRepository.Member> loadedManageable,
                    @NonNull List<FamilyAutomationRule> loadedRules,
                    @NonNull List<FamilyAutomationEvent> loadedEvents
            ) {
                session = loadedSession;
                allMembers = new ArrayList<>(loadedAll);
                manageableMembers = new ArrayList<>(loadedManageable);
                rules = new ArrayList<>(loadedRules);
                events = new ArrayList<>(loadedEvents);
                progress.setVisibility(View.GONE);
                addRoutineButton.setEnabled(!manageableMembers.isEmpty());
                addScheduleButton.setEnabled(!manageableMembers.isEmpty());
                renderOverview();
            }

            @Override
            public void onError(@NonNull String reason) {
                progress.setVisibility(View.GONE);
                errorView.setVisibility(View.VISIBLE);
                errorView.setText(R.string.family_automation_error);
            }
        });
    }

    private void renderOverview() {
        int active = 0;
        int schedules = 0;
        for (FamilyAutomationRule rule : rules) {
            if (rule.enabled) {
                active++;
            }
            if (rule.isScheduledSharing()) {
                schedules++;
            }
        }
        activeCountView.setText(String.valueOf(active));
        scheduleCountView.setText(String.valueOf(schedules));
        eventCountView.setText(String.valueOf(events.size()));
        renderRules();
        renderEvents();
    }

    private void renderRules() {
        rulesList.removeAllViews();
        if (rules.isEmpty()) {
            rulesList.addView(emptyCard(
                    R.string.family_automation_empty_rules,
                    R.color.fh_primary,
                    R.color.fh_primary_container
            ));
            return;
        }
        for (FamilyAutomationRule rule : rules) {
            FamilyAutomationRepository.Member member = memberByUid(
                    rule.targetUid
            );
            boolean manageable = member != null && member.manageable;
            int accent = rule.enabled ? R.color.fh_primary : R.color.fh_outline;
            int container = rule.enabled
                    ? R.color.fh_primary_container
                    : R.color.fh_surface_container;
            String timeDetail = rule.isScheduledSharing()
                    ? getString(
                    R.string.family_automation_schedule_time,
                    formatMinute(rule.startMinute),
                    formatMinute(rule.endMinute),
                    formatDays(rule.daysMask)
            )
                    : getString(
                    R.string.family_automation_rule_time,
                    formatMinute(rule.startMinute),
                    formatDays(rule.daysMask)
            );
            String memberDetail = getString(
                    R.string.family_automation_rule_member,
                    rule.targetName,
                    typeLabel(rule.type)
            );
            String detail = memberDetail + "\n" + timeDetail;
            if (rule.isPlaceRule()) {
                detail += "\n" + rule.placeName + " • "
                        + rule.graceMinutes + " min grace";
            }
            if (!rule.enabled) {
                detail += "\n" + getString(
                        R.string.family_automation_rule_disabled
                );
            }
            MaterialCardView card = informationCard(
                    rule.safeTitle(),
                    detail,
                    accent,
                    container
            );
            if (manageable) {
                card.setClickable(true);
                card.setFocusable(true);
                card.setOnClickListener(v ->
                        openEditor(rule, rule.isScheduledSharing()));
            }
            rulesList.addView(card);
        }
    }

    private void renderEvents() {
        eventsList.removeAllViews();
        if (events.isEmpty()) {
            eventsList.addView(emptyCard(
                    R.string.family_automation_empty_events,
                    R.color.fh_info,
                    R.color.fh_info_container
            ));
            return;
        }
        int count = Math.min(30, events.size());
        for (int index = 0; index < count; index++) {
            FamilyAutomationEvent event = events.get(index);
            boolean warning = FamilyAutomationEvent.SEVERITY_WARNING.equals(
                    event.severity
            );
            String title = event.targetName + " • "
                    + eventLabel(event.type);
            String detail = (event.detail == null ? "" : event.detail.trim())
                    + "\n"
                    + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT
            ).format(new Date(event.occurredAt));
            eventsList.addView(informationCard(
                    title,
                    detail,
                    warning ? R.color.fh_warning : R.color.fh_info,
                    warning
                            ? R.color.fh_warning_container
                            : R.color.fh_info_container
            ));
        }
    }

    private void openEditor(
            @Nullable FamilyAutomationRule existing,
            boolean scheduleMode
    ) {
        if (manageableMembers.isEmpty()) {
            return;
        }
        if (!scheduleMode && safePlaces.isEmpty()) {
            Toast.makeText(
                    this,
                    R.string.family_automation_no_safe_places,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        EditorState editor = new EditorState();
        editor.member = existing == null
                ? defaultMember()
                : memberByUid(existing.targetUid);
        if (editor.member == null || !editor.member.manageable) {
            editor.member = defaultMember();
        }
        editor.type = existing == null
                ? (scheduleMode
                ? FamilyAutomationRule.TYPE_SCHEDULED_SHARING
                : FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL)
                : existing.type;
        editor.place = existing == null
                ? defaultPlace(editor.member)
                : placeForRule(existing);
        editor.startMinute = existing == null
                ? (scheduleMode ? 7 * 60 : 18 * 60)
                : existing.startMinute;
        editor.endMinute = existing == null
                ? 21 * 60
                : existing.endMinute;
        editor.graceMinutes = existing == null
                ? 30
                : existing.graceMinutes;

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroll.addView(content);

        MaterialAutoCompleteTextView memberInput = dropdown(
                R.string.family_automation_member,
                manageableMembers,
                content
        );
        memberInput.setText(editor.member.displayName, false);
        memberInput.setOnItemClickListener((parent, view, position, id) -> {
            editor.member = manageableMembers.get(position);
            if (!scheduleMode) {
                editor.place = defaultPlace(editor.member);
            }
        });

        List<TypeOption> typeOptions = new ArrayList<>();
        if (scheduleMode) {
            typeOptions.add(new TypeOption(
                    FamilyAutomationRule.TYPE_SCHEDULED_SHARING,
                    getString(R.string.family_automation_type_schedule)
            ));
        } else {
            typeOptions.add(new TypeOption(
                    FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL,
                    getString(R.string.family_automation_type_arrival)
            ));
            typeOptions.add(new TypeOption(
                    FamilyAutomationRule.TYPE_EXPECTED_DEPARTURE,
                    getString(R.string.family_automation_type_departure)
            ));
            typeOptions.add(new TypeOption(
                    FamilyAutomationRule.TYPE_LATE_RETURN,
                    getString(R.string.family_automation_type_late_return)
            ));
        }
        MaterialAutoCompleteTextView typeInput = dropdown(
                R.string.family_automation_rule_type,
                typeOptions,
                content
        );
        typeInput.setText(typeOption(editor.type, typeOptions).label, false);
        typeInput.setOnItemClickListener((parent, view, position, id) ->
                editor.type = typeOptions.get(position).value);

        TextInputLayout titleLayout = textField(
                R.string.family_automation_rule_name,
                content
        );
        TextInputEditText titleInput = new TextInputEditText(this);
        titleInput.setSingleLine(true);
        titleInput.setText(existing == null ? "" : existing.title);
        titleLayout.addView(titleInput);

        MaterialAutoCompleteTextView placeInput = null;
        List<PlaceOption> placeOptions = buildPlaceOptions(existing);
        if (!scheduleMode) {
            placeInput = dropdown(
                    R.string.family_automation_place,
                    placeOptions,
                    content
            );
            if (editor.place != null) {
                placeInput.setText(editor.place.label, false);
            }
            MaterialAutoCompleteTextView finalPlaceInput = placeInput;
            placeInput.setOnItemClickListener((parent, view, position, id) -> {
                editor.place = placeOptions.get(position);
                finalPlaceInput.setText(editor.place.label, false);
            });
        }

        MaterialButton startButton = timeButton(
                scheduleMode
                        ? R.string.family_automation_start_time
                        : R.string.family_automation_expected_time,
                editor.startMinute,
                content
        );
        startButton.setOnClickListener(v -> showTimePicker(
                editor.startMinute,
                minute -> {
                    editor.startMinute = minute;
                    startButton.setText(timeButtonText(
                            scheduleMode
                                    ? R.string.family_automation_start_time
                                    : R.string.family_automation_expected_time,
                            minute
                    ));
                }
        ));

        if (scheduleMode) {
            MaterialButton endButton = timeButton(
                    R.string.family_automation_end_time,
                    editor.endMinute,
                    content
            );
            endButton.setOnClickListener(v -> showTimePicker(
                    editor.endMinute,
                    minute -> {
                        editor.endMinute = minute;
                        endButton.setText(timeButtonText(
                                R.string.family_automation_end_time,
                                minute
                        ));
                    }
            ));
        } else {
            List<GraceOption> graceOptions = graceOptions();
            MaterialAutoCompleteTextView graceInput = dropdown(
                    R.string.family_automation_grace,
                    graceOptions,
                    content
            );
            graceInput.setText(graceOption(
                    editor.graceMinutes,
                    graceOptions
            ).label, false);
            graceInput.setOnItemClickListener((parent, view, position, id) ->
                    editor.graceMinutes = graceOptions.get(position).minutes);
        }

        TextView daysLabel = sectionLabel(
                R.string.family_automation_days
        );
        LinearLayout.LayoutParams daysLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        daysLabelParams.topMargin = dp(12);
        content.addView(daysLabel, daysLabelParams);

        int initialMask = existing == null
                ? FamilyAutomationPolicy.WEEKDAYS_MASK
                : existing.daysMask;
        CheckBox[] dayChecks = createDayChecks(content, initialMask);

        MaterialSwitch notifySwitch = new MaterialSwitch(this);
        notifySwitch.setText(R.string.family_automation_notify_trusted);
        notifySwitch.setChecked(existing == null
                || existing.notifyTrustedViewers);
        content.addView(notifySwitch);

        MaterialSwitch enabledSwitch = new MaterialSwitch(this);
        enabledSwitch.setText(R.string.family_automation_enabled);
        enabledSwitch.setChecked(existing == null || existing.enabled);
        content.addView(enabledSwitch);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(scheduleMode
                        ? R.string.family_automation_schedule_editor_title
                        : R.string.family_automation_rule_editor_title)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.family_automation_save, null);
        if (existing != null) {
            builder.setNeutralButton(
                    R.string.family_automation_delete,
                    null
            );
        }
        AlertDialog dialog = builder.create();
        MaterialAutoCompleteTextView finalPlaceInput = placeInput;
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        int daysMask = resolveDaysMask(dayChecks);
                        String title = titleInput.getText() == null
                                ? ""
                                : titleInput.getText().toString().trim();
                        if (title.isEmpty()
                                || editor.member == null
                                || daysMask == 0
                                || (!scheduleMode && editor.place == null)) {
                            Toast.makeText(
                                    this,
                                    R.string.family_automation_invalid,
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }
                        FamilyAutomationRule rule = existing == null
                                ? new FamilyAutomationRule()
                                : copyRule(existing);
                        rule.targetUid = editor.member.uid;
                        rule.targetName = editor.member.displayName;
                        rule.title = title;
                        rule.type = editor.type;
                        rule.daysMask = daysMask;
                        rule.startMinute = editor.startMinute;
                        rule.endMinute = editor.endMinute;
                        rule.graceMinutes = scheduleMode
                                ? 0
                                : editor.graceMinutes;
                        rule.enabled = enabledSwitch.isChecked();
                        rule.notifyTrustedViewers =
                                notifySwitch.isChecked();
                        if (scheduleMode) {
                            rule.placeName = "";
                            rule.latitude = 0D;
                            rule.longitude = 0D;
                            rule.radiusMeters = 150D;
                        } else {
                            PlaceOption place = editor.place;
                            rule.placeName = place.label;
                            rule.latitude = place.latitude;
                            rule.longitude = place.longitude;
                            rule.radiusMeters = place.radiusMeters;
                        }
                        saveRule(dialog, rule);
                    });
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> confirmDelete(
                                dialog,
                                existing
                        ));
            }
        });
        dialog.show();
    }

    private void saveRule(
            @NonNull AlertDialog dialog,
            @NonNull FamilyAutomationRule rule
    ) {
        repository.saveRule(rule, new FamilyAutomationRepository.ActionCallback() {
            @Override
            public void onSuccess() {
                dialog.dismiss();
                Toast.makeText(
                        FamilyAutomationActivity.this,
                        R.string.family_automation_saved,
                        Toast.LENGTH_SHORT
                ).show();
                FamilyAutomationScheduler.scheduleNow(
                        FamilyAutomationActivity.this
                );
                loadOverview();
            }

            @Override
            public void onError(@NonNull String reason) {
                Toast.makeText(
                        FamilyAutomationActivity.this,
                        R.string.family_automation_error,
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    private void confirmDelete(
            @NonNull AlertDialog editorDialog,
            @NonNull FamilyAutomationRule rule
    ) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(
                        R.string.family_automation_delete_confirm,
                        rule.safeTitle()
                ))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(
                        R.string.family_automation_delete,
                        (dialog, which) -> repository.deleteRule(
                                rule,
                                new FamilyAutomationRepository.ActionCallback() {
                                    @Override
                                    public void onSuccess() {
                                        editorDialog.dismiss();
                                        Toast.makeText(
                                                FamilyAutomationActivity.this,
                                                R.string.family_automation_deleted,
                                                Toast.LENGTH_SHORT
                                        ).show();
                                        FamilyAutomationScheduler.scheduleNow(
                                                FamilyAutomationActivity.this
                                        );
                                        loadOverview();
                                    }

                                    @Override
                                    public void onError(
                                            @NonNull String reason
                                    ) {
                                        Toast.makeText(
                                                FamilyAutomationActivity.this,
                                                R.string.family_automation_error,
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                }
                        ))
                .show();
    }

    @NonNull
    private MaterialAutoCompleteTextView dropdown(
            int hintRes,
            @NonNull List<?> values,
            @NonNull LinearLayout parent
    ) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hintRes);
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        LinearLayout.LayoutParams params = fieldParams();
        parent.addView(layout, params);

        MaterialAutoCompleteTextView input =
                new MaterialAutoCompleteTextView(this);
        input.setInputType(0);
        input.setSingleLine(true);
        input.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                values
        ));
        layout.addView(input, new TextInputLayout.LayoutParams(
                TextInputLayout.LayoutParams.MATCH_PARENT,
                TextInputLayout.LayoutParams.WRAP_CONTENT
        ));
        return input;
    }

    @NonNull
    private TextInputLayout textField(
            int hintRes,
            @NonNull LinearLayout parent
    ) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hintRes);
        parent.addView(layout, fieldParams());
        return layout;
    }

    @NonNull
    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        return params;
    }

    @NonNull
    private MaterialButton timeButton(
            int labelRes,
            int minute,
            @NonNull LinearLayout parent
    ) {
        MaterialButton button = new MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        button.setText(timeButtonText(labelRes, minute));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        parent.addView(button, fieldParams());
        return button;
    }

    @NonNull
    private String timeButtonText(int labelRes, int minute) {
        return getString(labelRes) + ": " + formatMinute(minute);
    }

    private void showTimePicker(int initialMinute, @NonNull MinuteCallback callback) {
        new TimePickerDialog(
                this,
                (view, hourOfDay, minute) ->
                        callback.onSelected(hourOfDay * 60 + minute),
                initialMinute / 60,
                initialMinute % 60,
                false
        ).show();
    }

    @NonNull
    private CheckBox[] createDayChecks(
            @NonNull LinearLayout parent,
            int initialMask
    ) {
        int[] labels = {
                R.string.family_automation_day_mon,
                R.string.family_automation_day_tue,
                R.string.family_automation_day_wed,
                R.string.family_automation_day_thu,
                R.string.family_automation_day_fri,
                R.string.family_automation_day_sat,
                R.string.family_automation_day_sun
        };
        int[] bits = {
                FamilyAutomationPolicy.MONDAY,
                FamilyAutomationPolicy.TUESDAY,
                FamilyAutomationPolicy.WEDNESDAY,
                FamilyAutomationPolicy.THURSDAY,
                FamilyAutomationPolicy.FRIDAY,
                FamilyAutomationPolicy.SATURDAY,
                FamilyAutomationPolicy.SUNDAY
        };
        CheckBox[] checks = new CheckBox[7];
        LinearLayout firstRow = dayRow();
        LinearLayout secondRow = dayRow();
        parent.addView(firstRow);
        parent.addView(secondRow);
        for (int index = 0; index < checks.length; index++) {
            CheckBox check = new CheckBox(this);
            check.setText(labels[index]);
            check.setTag(bits[index]);
            check.setChecked((initialMask & bits[index]) != 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1F
            );
            (index < 4 ? firstRow : secondRow).addView(check, params);
            checks[index] = check;
        }
        return checks;
    }

    @NonNull
    private LinearLayout dayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private int resolveDaysMask(@NonNull CheckBox[] checks) {
        int mask = 0;
        for (CheckBox check : checks) {
            if (check.isChecked() && check.getTag() instanceof Integer) {
                mask |= (Integer) check.getTag();
            }
        }
        return mask;
    }

    @NonNull
    private TextView sectionLabel(int textRes) {
        TextView view = new TextView(this);
        view.setText(textRes);
        view.setTextSize(14F);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(ContextCompat.getColor(this, R.color.fh_text_primary));
        return view;
    }

    @NonNull
    private MaterialCardView informationCard(
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
        params.bottomMargin = dp(8);
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
        return card;
    }

    @NonNull
    private View emptyCard(int textRes, int accent, int container) {
        return informationCard(
                getString(textRes),
                "",
                accent,
                container
        );
    }

    @Nullable
    private FamilyAutomationRepository.Member memberByUid(
            @Nullable String uid
    ) {
        if (uid == null) {
            return null;
        }
        for (FamilyAutomationRepository.Member member : allMembers) {
            if (uid.equals(member.uid)) {
                return member;
            }
        }
        return null;
    }

    @NonNull
    private FamilyAutomationRepository.Member defaultMember() {
        for (FamilyAutomationRepository.Member member : manageableMembers) {
            if (member.self) {
                return member;
            }
        }
        return manageableMembers.get(0);
    }

    @Nullable
    private PlaceOption defaultPlace(
            @Nullable FamilyAutomationRepository.Member member
    ) {
        for (SafePlace place : safePlaces) {
            if (member == null
                    || place.memberUid.trim().isEmpty()
                    || place.memberUid.trim().equals(member.uid)) {
                return new PlaceOption(place);
            }
        }
        return safePlaces.isEmpty() ? null : new PlaceOption(safePlaces.get(0));
    }

    @NonNull
    private List<PlaceOption> buildPlaceOptions(
            @Nullable FamilyAutomationRule existing
    ) {
        List<PlaceOption> options = new ArrayList<>();
        for (SafePlace place : safePlaces) {
            options.add(new PlaceOption(place));
        }
        if (existing != null && existing.isPlaceRule()) {
            boolean found = false;
            for (PlaceOption option : options) {
                if (option.label.equals(existing.placeName)
                        && Math.abs(option.latitude - existing.latitude)
                        < 0.000001D
                        && Math.abs(option.longitude - existing.longitude)
                        < 0.000001D) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                options.add(0, new PlaceOption(existing));
            }
        }
        return options;
    }

    @Nullable
    private PlaceOption placeForRule(@NonNull FamilyAutomationRule rule) {
        for (PlaceOption option : buildPlaceOptions(rule)) {
            if (option.label.equals(rule.placeName)
                    && Math.abs(option.latitude - rule.latitude) < 0.000001D
                    && Math.abs(option.longitude - rule.longitude) < 0.000001D) {
                return option;
            }
        }
        return rule.isPlaceRule() ? new PlaceOption(rule) : null;
    }

    @NonNull
    private List<GraceOption> graceOptions() {
        List<GraceOption> values = new ArrayList<>();
        values.add(new GraceOption(15,
                getString(R.string.family_automation_grace_15)));
        values.add(new GraceOption(30,
                getString(R.string.family_automation_grace_30)));
        values.add(new GraceOption(45,
                getString(R.string.family_automation_grace_45)));
        values.add(new GraceOption(60,
                getString(R.string.family_automation_grace_60)));
        values.add(new GraceOption(90,
                getString(R.string.family_automation_grace_90)));
        return values;
    }

    @NonNull
    private GraceOption graceOption(
            int minutes,
            @NonNull List<GraceOption> options
    ) {
        for (GraceOption option : options) {
            if (option.minutes == minutes) {
                return option;
            }
        }
        return options.get(1);
    }

    @NonNull
    private TypeOption typeOption(
            @NonNull String value,
            @NonNull List<TypeOption> options
    ) {
        for (TypeOption option : options) {
            if (option.value.equals(value)) {
                return option;
            }
        }
        return options.get(0);
    }

    @NonNull
    private FamilyAutomationRule copyRule(
            @NonNull FamilyAutomationRule source
    ) {
        FamilyAutomationRule rule = new FamilyAutomationRule();
        rule.ruleId = source.ruleId;
        rule.familyId = source.familyId;
        rule.targetUid = source.targetUid;
        rule.targetName = source.targetName;
        rule.createdByUid = source.createdByUid;
        rule.title = source.title;
        rule.type = source.type;
        rule.placeName = source.placeName;
        rule.latitude = source.latitude;
        rule.longitude = source.longitude;
        rule.radiusMeters = source.radiusMeters;
        rule.daysMask = source.daysMask;
        rule.startMinute = source.startMinute;
        rule.endMinute = source.endMinute;
        rule.graceMinutes = source.graceMinutes;
        rule.enabled = source.enabled;
        rule.notifyTrustedViewers = source.notifyTrustedViewers;
        rule.createdAt = source.createdAt;
        rule.updatedAt = source.updatedAt;
        return rule;
    }

    @NonNull
    private String typeLabel(@NonNull String type) {
        switch (type) {
            case FamilyAutomationRule.TYPE_EXPECTED_DEPARTURE:
                return getString(R.string.family_automation_type_departure);
            case FamilyAutomationRule.TYPE_LATE_RETURN:
                return getString(R.string.family_automation_type_late_return);
            case FamilyAutomationRule.TYPE_SCHEDULED_SHARING:
                return getString(R.string.family_automation_type_schedule);
            case FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL:
            default:
                return getString(R.string.family_automation_type_arrival);
        }
    }

    @NonNull
    private String eventLabel(@NonNull String type) {
        return type.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @NonNull
    private String formatMinute(int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minute / 60);
        calendar.set(Calendar.MINUTE, minute % 60);
        return new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(calendar.getTime());
    }

    @NonNull
    private String formatDays(int mask) {
        if (mask == FamilyAutomationPolicy.ALL_DAYS_MASK) {
            return "Every day";
        }
        if (mask == FamilyAutomationPolicy.WEEKDAYS_MASK) {
            return "Weekdays";
        }
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        int[] bits = {
                FamilyAutomationPolicy.MONDAY,
                FamilyAutomationPolicy.TUESDAY,
                FamilyAutomationPolicy.WEDNESDAY,
                FamilyAutomationPolicy.THURSDAY,
                FamilyAutomationPolicy.FRIDAY,
                FamilyAutomationPolicy.SATURDAY,
                FamilyAutomationPolicy.SUNDAY
        };
        List<String> selected = new ArrayList<>();
        for (int index = 0; index < bits.length; index++) {
            if ((mask & bits[index]) != 0) {
                selected.add(names[index]);
            }
        }
        return String.join(", ", selected);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface MinuteCallback {
        void onSelected(int minute);
    }

    private static final class EditorState {
        @Nullable FamilyAutomationRepository.Member member;
        @NonNull String type = FamilyAutomationRule.TYPE_EXPECTED_ARRIVAL;
        @Nullable PlaceOption place;
        int startMinute;
        int endMinute;
        int graceMinutes;
    }

    private static final class TypeOption {
        @NonNull final String value;
        @NonNull final String label;

        TypeOption(@NonNull String value, @NonNull String label) {
            this.value = value;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class GraceOption {
        final int minutes;
        @NonNull final String label;

        GraceOption(int minutes, @NonNull String label) {
            this.minutes = minutes;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class PlaceOption {
        @NonNull final String label;
        final double latitude;
        final double longitude;
        final double radiusMeters;

        PlaceOption(@NonNull SafePlace place) {
            label = place.name.trim();
            latitude = place.latitude;
            longitude = place.longitude;
            radiusMeters = Math.max(50D, place.radiusMeters);
        }

        PlaceOption(@NonNull FamilyAutomationRule rule) {
            label = rule.placeName.trim();
            latitude = rule.latitude;
            longitude = rule.longitude;
            radiusMeters = Math.max(50D, rule.radiusMeters);
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }
}
