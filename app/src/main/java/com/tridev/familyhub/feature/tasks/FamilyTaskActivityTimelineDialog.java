package com.tridev.familyhub.feature.tasks;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.FamilyTask;
import com.tridev.familyhub.data.repository.FamilyTaskActivityRepository;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, privacy-safe activity timeline for Family To-Do. */
final class FamilyTaskActivityTimelineDialog {
    private FamilyTaskActivityTimelineDialog() { }

    static void show(@NonNull Context context, @NonNull FamilyTask initialTask) {
        new Controller(context, initialTask).show();
    }

    private static final class Controller {
        @NonNull private final Context context;
        @NonNull private final FamilyTaskActivityRepository repository;
        @NonNull private final String initialTaskId;
        @NonNull private final String initialTaskTitle;
        @NonNull private final ActivityAdapter adapter;
        @NonNull private final List<FamilyTaskActivityRepository.ActivityEvent> allEvents =
                new ArrayList<>();

        private MaterialAutoCompleteTextView taskInput;
        private MaterialAutoCompleteTextView memberInput;
        private MaterialAutoCompleteTextView typeInput;
        private TextView summary;
        private TextView empty;
        private RecyclerView recycler;
        private AlertDialog dialog;

        @NonNull private List<String> taskIds = new ArrayList<>();
        @NonNull private List<String> taskLabels = new ArrayList<>();
        @NonNull private List<String> memberValues = new ArrayList<>();
        @NonNull private List<String> memberLabels = new ArrayList<>();
        @NonNull private List<String> typeValues = new ArrayList<>();
        @NonNull private List<String> typeLabels = new ArrayList<>();

        @NonNull private String selectedTaskId;
        @NonNull private String selectedMember = "";
        @NonNull private String selectedType = "";

        Controller(@NonNull Context context, @NonNull FamilyTask initialTask) {
            this.context = context;
            repository = new FamilyTaskActivityRepository(context);
            initialTaskId = safe(initialTask.cloudId);
            initialTaskTitle = safe(initialTask.title);
            selectedTaskId = initialTaskId;
            adapter = new ActivityAdapter(context);
        }

        void show() {
            LinearLayout root = buildContent();
            dialog = new MaterialAlertDialogBuilder(context,
                    R.style.ThemeOverlay_FamilyHub_FormDialog)
                    .setTitle(R.string.family_tasks_activity_title)
                    .setView(root)
                    .setNegativeButton(R.string.family_tasks_activity_close, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            });
            dialog.setOnDismissListener(ignored -> repository.stopObserving());
            dialog.show();
            repository.startObserving(this::reload);
            reload();
        }

        @NonNull
        private LinearLayout buildContent() {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(10), dp(12), dp(8));
            root.setBackground(context.getDrawable(R.drawable.bg_form_three_tone));

            TextView privacy = text(context.getString(R.string.family_tasks_activity_privacy),
                    11f, false);
            privacy.setTextColor(context.getColor(R.color.fh_text_secondary));
            root.addView(privacy, matchWrap());

            taskInput = addDropdown(root, R.string.family_tasks_activity_filter_task);
            memberInput = addDropdown(root, R.string.family_tasks_activity_filter_member);
            typeInput = addDropdown(root, R.string.family_tasks_activity_filter_type);

            summary = text("", 12f, true);
            summary.setTextColor(context.getColor(R.color.fh_form_accent));
            LinearLayout.LayoutParams summaryParams = matchWrap();
            summaryParams.topMargin = dp(10);
            root.addView(summary, summaryParams);

            empty = text(context.getString(R.string.family_tasks_activity_empty), 13f, false);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(context.getColor(R.color.fh_text_secondary));
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            empty.setVisibility(View.GONE);
            root.addView(empty, matchWrap());

            recycler = new RecyclerView(context);
            recycler.setLayoutManager(new LinearLayoutManager(context));
            recycler.setAdapter(adapter);
            recycler.setClipToPadding(false);
            recycler.setPadding(0, dp(8), 0, dp(12));
            int targetHeight = Math.min(dp(500),
                    Math.max(dp(260), (int) (context.getResources()
                            .getDisplayMetrics().heightPixels * 0.50f)));
            root.addView(recycler, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, targetHeight));
            return root;
        }

        private void reload() {
            repository.loadAll(events -> {
                allEvents.clear();
                allEvents.addAll(events);
                rebuildFilters();
                applyFilters();
            });
        }

        private void rebuildFilters() {
            Map<String, String> tasks = new LinkedHashMap<>();
            if (!initialTaskId.isEmpty()) {
                tasks.put(initialTaskId, initialTaskTitle.isEmpty()
                        ? context.getString(R.string.family_tasks_activity_task_fallback)
                        : initialTaskTitle);
            }
            Set<String> members = new LinkedHashSet<>();
            for (FamilyTaskActivityRepository.ActivityEvent event : allEvents) {
                if (!event.taskCloudId.isEmpty()) {
                    tasks.putIfAbsent(event.taskCloudId,
                            event.taskTitle.isEmpty()
                                    ? context.getString(R.string.family_tasks_activity_task_fallback)
                                    : event.taskTitle);
                }
                if (!event.actorName.isEmpty()) members.add(event.actorName);
            }

            taskIds = new ArrayList<>();
            taskLabels = new ArrayList<>();
            taskIds.add("");
            taskLabels.add(context.getString(R.string.family_tasks_activity_all_tasks));
            for (Map.Entry<String, String> task : tasks.entrySet()) {
                taskIds.add(task.getKey());
                taskLabels.add(task.getValue());
            }
            if (!selectedTaskId.isEmpty() && !taskIds.contains(selectedTaskId)) {
                selectedTaskId = "";
            }
            taskInput.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, taskLabels));
            int taskIndex = Math.max(0, taskIds.indexOf(selectedTaskId));
            taskInput.setText(taskLabels.get(taskIndex), false);
            taskInput.setOnItemClickListener((parent, view, position, id) -> {
                selectedTaskId = taskIds.get(position);
                applyFilters();
            });

            memberValues = new ArrayList<>();
            memberLabels = new ArrayList<>();
            memberValues.add("");
            memberLabels.add(context.getString(R.string.family_tasks_activity_all_members));
            for (String member : members) {
                memberValues.add(member);
                memberLabels.add(member);
            }
            if (!selectedMember.isEmpty() && !memberValues.contains(selectedMember)) {
                selectedMember = "";
            }
            memberInput.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, memberLabels));
            int memberIndex = Math.max(0, memberValues.indexOf(selectedMember));
            memberInput.setText(memberLabels.get(memberIndex), false);
            memberInput.setOnItemClickListener((parent, view, position, id) -> {
                selectedMember = memberValues.get(position);
                applyFilters();
            });

            typeValues = new ArrayList<>();
            typeLabels = new ArrayList<>();
            addType("", R.string.family_tasks_activity_all_types);
            addType(FamilyTaskActivityRepository.EVENT_CREATE,
                    R.string.family_tasks_activity_created);
            addType(FamilyTaskActivityRepository.EVENT_EDIT,
                    R.string.family_tasks_activity_edited);
            addType(FamilyTaskActivityRepository.EVENT_ASSIGN,
                    R.string.family_tasks_activity_assigned);
            addType(FamilyTaskActivityRepository.EVENT_COMPLETE,
                    R.string.family_tasks_activity_completed);
            addType(FamilyTaskActivityRepository.EVENT_REOPEN,
                    R.string.family_tasks_activity_reopened);
            addType(FamilyTaskActivityRepository.EVENT_DELETE,
                    R.string.family_tasks_activity_deleted);
            if (!selectedType.isEmpty() && !typeValues.contains(selectedType)) selectedType = "";
            typeInput.setAdapter(new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, typeLabels));
            int typeIndex = Math.max(0, typeValues.indexOf(selectedType));
            typeInput.setText(typeLabels.get(typeIndex), false);
            typeInput.setOnItemClickListener((parent, view, position, id) -> {
                selectedType = typeValues.get(position);
                applyFilters();
            });
        }

        private void addType(@NonNull String value, int labelRes) {
            typeValues.add(value);
            typeLabels.add(context.getString(labelRes));
        }

        private void applyFilters() {
            List<FamilyTaskActivityRepository.ActivityEvent> visible = new ArrayList<>();
            for (FamilyTaskActivityRepository.ActivityEvent event : allEvents) {
                if (!selectedTaskId.isEmpty()
                        && !selectedTaskId.equals(event.taskCloudId)) continue;
                if (!selectedMember.isEmpty()
                        && !selectedMember.equals(event.actorName)) continue;
                if (!selectedType.isEmpty()
                        && !selectedType.equals(event.eventType)) continue;
                visible.add(event);
            }
            adapter.submit(visible);
            summary.setText(context.getString(
                    R.string.family_tasks_activity_result_count, visible.size()));
            boolean none = visible.isEmpty();
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            recycler.setVisibility(none ? View.GONE : View.VISIBLE);
        }

        @NonNull
        private MaterialAutoCompleteTextView addDropdown(@NonNull LinearLayout root,
                                                         int hintRes) {
            TextInputLayout layout = new TextInputLayout(context);
            layout.setHint(context.getString(hintRes));
            layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
            LinearLayout.LayoutParams layoutParams = matchWrap();
            layoutParams.topMargin = dp(10);
            root.addView(layout, layoutParams);

            MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(context);
            input.setInputType(InputType.TYPE_NULL);
            input.setSingleLine(true);
            input.setTextSize(12f);
            input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            input.setPadding(dp(10), 0, dp(8), 0);
            input.setDropDownHeight(dp(240));
            input.setDropDownVerticalOffset(dp(6));
            input.setPopupBackgroundDrawable(context.getDrawable(R.drawable.bg_premium_dropdown_popup));
            input.setOnClickListener(v -> input.showDropDown());
            layout.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(52)));
            return input;
        }

        @NonNull
        private TextView text(@NonNull String value, float size, boolean bold) {
            TextView view = new TextView(context);
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(context.getColor(R.color.fh_text_primary));
            if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            return view;
        }

        @NonNull
        private LinearLayout.LayoutParams matchWrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private static final class ActivityAdapter
            extends RecyclerView.Adapter<ActivityAdapter.Holder> {
        @NonNull private final Context context;
        @NonNull private final List<FamilyTaskActivityRepository.ActivityEvent> events =
                new ArrayList<>();

        ActivityAdapter(@NonNull Context context) { this.context = context; }

        void submit(@NonNull List<FamilyTaskActivityRepository.ActivityEvent> updated) {
            events.clear();
            events.addAll(updated);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(context);
            card.setCardElevation(0f);
            card.setRadius(dp(context, 15));
            card.setStrokeWidth(dp(context, 1));
            card.setStrokeColor(context.getColor(R.color.fh_form_outline));
            card.setCardBackgroundColor(context.getColor(R.color.fh_form_surface));
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(context, 8);
            card.setLayoutParams(cardParams);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 14), dp(context, 12),
                    dp(context, 14), dp(context, 12));
            card.addView(content);

            TextView action = makeText(context, 12f, true);
            action.setTextColor(context.getColor(R.color.fh_module_grocery));
            content.addView(action, matchWrapParams());

            TextView task = makeText(context, 15f, true);
            LinearLayout.LayoutParams taskParams = matchWrapParams();
            taskParams.topMargin = dp(context, 3);
            content.addView(task, taskParams);

            TextView detail = makeText(context, 12f, false);
            detail.setTextColor(context.getColor(R.color.fh_text_secondary));
            LinearLayout.LayoutParams detailParams = matchWrapParams();
            detailParams.topMargin = dp(context, 4);
            content.addView(detail, detailParams);

            TextView meta = makeText(context, 11f, false);
            meta.setTextColor(context.getColor(R.color.fh_text_secondary));
            LinearLayout.LayoutParams metaParams = matchWrapParams();
            metaParams.topMargin = dp(context, 7);
            content.addView(meta, metaParams);
            return new Holder(card, action, task, detail, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            FamilyTaskActivityRepository.ActivityEvent event = events.get(position);
            holder.action.setText(eventLabel(context, event.eventType));
            holder.task.setText(event.taskTitle.isEmpty()
                    ? context.getString(R.string.family_tasks_activity_task_fallback)
                    : event.taskTitle);
            if (FamilyTaskActivityRepository.EVENT_ASSIGN.equals(event.eventType)
                    && !event.detail.isEmpty()) {
                holder.detail.setText(context.getString(
                        R.string.family_tasks_activity_assigned_to, event.detail));
                holder.detail.setVisibility(View.VISIBLE);
            } else {
                holder.detail.setText("");
                holder.detail.setVisibility(View.GONE);
            }
            String when = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(event.eventAt));
            holder.meta.setText(context.getString(R.string.family_tasks_activity_meta,
                    event.actorName.isEmpty()
                            ? context.getString(R.string.family_tasks_activity_all_members)
                            : event.actorName,
                    when));
        }

        @Override public int getItemCount() { return events.size(); }

        private static final class Holder extends RecyclerView.ViewHolder {
            final TextView action;
            final TextView task;
            final TextView detail;
            final TextView meta;

            Holder(@NonNull View itemView, @NonNull TextView action,
                   @NonNull TextView task, @NonNull TextView detail,
                   @NonNull TextView meta) {
                super(itemView);
                this.action = action;
                this.task = task;
                this.detail = detail;
                this.meta = meta;
            }
        }
    }

    @NonNull
    private static String eventLabel(@NonNull Context context, @NonNull String type) {
        if (FamilyTaskActivityRepository.EVENT_CREATE.equals(type)) {
            return context.getString(R.string.family_tasks_activity_created);
        }
        if (FamilyTaskActivityRepository.EVENT_EDIT.equals(type)) {
            return context.getString(R.string.family_tasks_activity_edited);
        }
        if (FamilyTaskActivityRepository.EVENT_ASSIGN.equals(type)) {
            return context.getString(R.string.family_tasks_activity_assigned);
        }
        if (FamilyTaskActivityRepository.EVENT_COMPLETE.equals(type)) {
            return context.getString(R.string.family_tasks_activity_completed);
        }
        if (FamilyTaskActivityRepository.EVENT_REOPEN.equals(type)) {
            return context.getString(R.string.family_tasks_activity_reopened);
        }
        return context.getString(R.string.family_tasks_activity_deleted);
    }

    private static TextView makeText(@NonNull Context context, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(context.getColor(R.color.fh_text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
