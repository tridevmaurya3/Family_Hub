package com.tridev.familyhub.feature.tasks;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.tasks.FamilyTaskScheduler;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskRepository;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * Review-first Android share target for turning explicitly shared message text into a task.
 * Family Hub never requests SMS inbox permissions and never stores the raw shared message.
 */
public final class FamilyTaskSmsSuggestionActivity extends AppCompatActivity {
    private static final String PREFS = "family_task_sms_suggestion_history";
    private static final String STATE_TITLE = "sms_task_title";
    private static final String STATE_DUE = "sms_task_due";
    private static final String STATE_PRIORITY = "sms_task_priority";
    private static final String STATE_FINGERPRINT = "sms_task_fingerprint";
    private static final String STATE_ACTIONABLE = "sms_task_actionable";

    private TextInputEditText titleInput;
    private MaterialAutoCompleteTextView priorityInput;
    private MaterialButton dueButton;
    private long dueAt;
    @NonNull private String fingerprint = "";
    private boolean actionable;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, R.string.family_tasks_sms_sign_in_first,
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String initialTitle;
        String initialPriority;
        if (savedInstanceState != null) {
            initialTitle = safe(savedInstanceState.getString(STATE_TITLE));
            dueAt = savedInstanceState.getLong(STATE_DUE, defaultDue());
            initialPriority = safe(savedInstanceState.getString(STATE_PRIORITY));
            fingerprint = safe(savedInstanceState.getString(STATE_FINGERPRINT));
            actionable = savedInstanceState.getBoolean(STATE_ACTIONABLE, true);
        } else {
            String shared = readSharedText(getIntent());
            clearRawShare(getIntent());
            if (shared.isEmpty()) {
                Toast.makeText(this, R.string.family_tasks_sms_invalid,
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            FamilyTaskSmsSuggestionParser.Suggestion suggestion =
                    FamilyTaskSmsSuggestionParser.parse(shared, System.currentTimeMillis());
            initialTitle = suggestion.title;
            dueAt = suggestion.dueAt;
            initialPriority = suggestion.priority;
            fingerprint = suggestion.fingerprint;
            actionable = suggestion.actionable;
            // Drop the only app-level reference to the raw text immediately after local parsing.
            shared = "";
        }

        setContentView(buildContent(initialTitle, initialPriority));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TITLE, title());
        outState.putLong(STATE_DUE, dueAt);
        outState.putString(STATE_PRIORITY, safe(priorityInput == null
                ? "" : String.valueOf(priorityInput.getText())));
        outState.putString(STATE_FINGERPRINT, fingerprint);
        outState.putBoolean(STATE_ACTIONABLE, actionable);
    }

    @NonNull
    private ScrollView buildContent(@NonNull String initialTitle,
                                    @NonNull String initialPriority) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_page_three_tone);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = text(getString(R.string.family_tasks_sms_title), 23, true);
        root.addView(heading, matchWrap());

        TextView privacy = text(getString(R.string.family_tasks_sms_privacy), 12, false);
        privacy.setTextColor(getColor(R.color.fh_text_secondary));
        LinearLayout.LayoutParams privacyParams = matchWrap();
        privacyParams.topMargin = dp(6);
        root.addView(privacy, privacyParams);

        if (!actionable) {
            MaterialCardView noticeCard = new MaterialCardView(this);
            noticeCard.setCardElevation(0f);
            noticeCard.setRadius(dp(16));
            noticeCard.setStrokeWidth(dp(1));
            noticeCard.setStrokeColor(getColor(R.color.fh_warning));
            noticeCard.setCardBackgroundColor(getColor(R.color.fh_warning_container));
            TextView notice = text(getString(R.string.family_tasks_sms_unclear), 12, false);
            notice.setPadding(dp(14), dp(12), dp(14), dp(12));
            noticeCard.addView(notice);
            LinearLayout.LayoutParams noticeParams = matchWrap();
            noticeParams.topMargin = dp(14);
            root.addView(noticeCard, noticeParams);
        }

        MaterialCardView formCard = new MaterialCardView(this);
        formCard.setCardElevation(0f);
        formCard.setRadius(dp(18));
        formCard.setStrokeWidth(dp(1));
        formCard.setStrokeColor(getColor(R.color.fh_outline_variant));
        formCard.setCardBackgroundColor(getColor(R.color.fh_surface));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(16);
        root.addView(formCard, cardParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(16), dp(16), dp(16));
        formCard.addView(form);

        TextView review = text(getString(R.string.family_tasks_sms_review_hint), 13, true);
        form.addView(review, matchWrap());

        TextInputLayout titleLayout = new TextInputLayout(this);
        titleLayout.setHint(R.string.family_tasks_sms_task_title);
        titleLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        titleInput = new TextInputEditText(this);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setMaxLines(3);
        titleInput.setText(initialTitle);
        titleLayout.addView(titleInput, matchWrap());
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(14);
        form.addView(titleLayout, titleParams);

        dueButton = new MaterialButton(this);
        dueButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        dueButton.setOnClickListener(v -> pickDue());
        LinearLayout.LayoutParams dueParams = matchHeight(dp(52));
        dueParams.topMargin = dp(12);
        form.addView(dueButton, dueParams);
        updateDueText();

        String[] priorityLabels = {
                getString(R.string.task_priority_normal),
                getString(R.string.task_priority_high),
                getString(R.string.task_priority_urgent)
        };
        TextInputLayout priorityLayout = new TextInputLayout(this);
        priorityLayout.setHint(R.string.family_tasks_sms_priority);
        priorityLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        priorityLayout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        priorityInput = new MaterialAutoCompleteTextView(this);
        priorityInput.setInputType(InputType.TYPE_NULL);
        priorityInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, priorityLabels));
        priorityInput.setText(priorityLabel(initialPriority), false);
        priorityInput.setOnClickListener(v -> priorityInput.showDropDown());
        priorityLayout.addView(priorityInput, matchWrap());
        LinearLayout.LayoutParams priorityParams = matchWrap();
        priorityParams.topMargin = dp(12);
        form.addView(priorityLayout, priorityParams);

        TextView detail = text(getString(R.string.family_tasks_sms_privacy_detail), 11, false);
        detail.setTextColor(getColor(R.color.fh_text_secondary));
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(12);
        form.addView(detail, detailParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(16);
        root.addView(actions, actionsParams);

        MaterialButton cancel = new MaterialButton(this);
        cancel.setText(R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        cancelParams.setMarginEnd(dp(5));
        actions.addView(cancel, cancelParams);

        MaterialButton create = new MaterialButton(this);
        create.setText(R.string.family_tasks_sms_create);
        create.setOnClickListener(v -> createReviewedTask());
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                0, dp(50), 1.35f);
        createParams.setMarginStart(dp(5));
        actions.addView(create, createParams);

        return scroll;
    }

    private void createReviewedTask() {
        String taskTitle = title();
        if (taskTitle.isEmpty()) {
            titleInput.setError(getString(R.string.family_tasks_required));
            titleInput.requestFocus();
            return;
        }
        if (wasAlreadyUsed()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.family_tasks_sms_duplicate_title)
                    .setMessage(R.string.family_tasks_sms_duplicate_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.family_tasks_sms_create_again,
                            (dialog, which) -> persistReviewedTask(taskTitle))
                    .show();
            return;
        }
        persistReviewedTask(taskTitle);
    }

    private void persistReviewedTask(@NonNull String taskTitle) {
        FamilyTask task = new FamilyTask();
        task.title = taskTitle.length() > 120
                ? taskTitle.substring(0, 120).trim() : taskTitle;
        task.notes = "";
        task.dueAt = dueAt;
        task.priority = priorityValue();
        task.repeatType = FamilyTask.REPEAT_NONE;
        task.reminderEnabled = dueAt > System.currentTimeMillis();
        task.reminderMinutesBefore = 30;
        task.sourceType = "SMS_SUGGESTION";
        task.sourceRecordId = fingerprint;
        task.shared = true;

        new FamilyTaskRepository(this).save(task, () -> {
            if (task.reminderEnabled) FamilyTaskScheduler.schedule(this, task);
            rememberUsed();
            Toast.makeText(this, R.string.family_tasks_sms_saved,
                    Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void pickDue() {
        Calendar selected = Calendar.getInstance();
        selected.setTimeInMillis(dueAt > 0L ? dueAt : defaultDue());
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar date = Calendar.getInstance();
            date.setTimeInMillis(dueAt > 0L ? dueAt : defaultDue());
            date.set(year, month, day);
            new TimePickerDialog(this, (timePicker, hour, minute) -> {
                date.set(Calendar.HOUR_OF_DAY, hour);
                date.set(Calendar.MINUTE, minute);
                date.set(Calendar.SECOND, 0);
                date.set(Calendar.MILLISECOND, 0);
                dueAt = date.getTimeInMillis();
                updateDueText();
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), false).show();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDueText() {
        if (dueButton == null) return;
        String formatted = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(dueAt));
        dueButton.setText(getString(R.string.family_tasks_sms_due, formatted));
    }

    @NonNull
    private String priorityLabel(@NonNull String priority) {
        if (FamilyTask.PRIORITY_URGENT.equals(priority)) {
            return getString(R.string.task_priority_urgent);
        }
        if (FamilyTask.PRIORITY_HIGH.equals(priority)) {
            return getString(R.string.task_priority_high);
        }
        return getString(R.string.task_priority_normal);
    }

    @NonNull
    private String priorityValue() {
        String selected = safe(priorityInput == null ? "" : String.valueOf(priorityInput.getText()));
        if (selected.equals(getString(R.string.task_priority_urgent))) {
            return FamilyTask.PRIORITY_URGENT;
        }
        if (selected.equals(getString(R.string.task_priority_high))) {
            return FamilyTask.PRIORITY_HIGH;
        }
        return FamilyTask.PRIORITY_NORMAL;
    }

    private boolean wasAlreadyUsed() {
        return !fingerprint.isEmpty() && getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("used:" + fingerprint, false);
    }

    private void rememberUsed() {
        if (fingerprint.isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("used:" + fingerprint, true).apply();
    }

    @NonNull
    private String title() {
        return safe(titleInput == null || titleInput.getText() == null
                ? "" : titleInput.getText().toString());
    }

    private long defaultDue() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 18);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 15L * 60L * 1000L) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    @NonNull
    private static String readSharedText(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return "";
        CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (value == null) return "";
        String text = value.toString().trim();
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }

    private static void clearRawShare(@Nullable Intent intent) {
        if (intent == null) return;
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.removeExtra(Intent.EXTRA_HTML_TEXT);
        intent.setClipData(null);
    }

    private TextView text(@NonNull String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(R.color.fh_text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
