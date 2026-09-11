from pathlib import Path

root = Path('.')
frag = root / 'app/src/main/java/com/tridev/familyhub/feature/dashboard/DashboardFragment.java'
card = root / 'app/src/main/java/com/tridev/familyhub/core/ui/cards/ActionCardView.java'
layout = root / 'app/src/main/res/layout/fragment_dashboard.xml'
strings = root / 'app/src/main/res/values/strings_dashboard_todo.xml'


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 anchor, found {count}')
    return text.replace(old, new, 1)

# Opt-in centering for dashboard card values only.
text = card.read_text(encoding='utf-8')
text = replace_once(text,
    'import android.util.AttributeSet;\n',
    'import android.util.AttributeSet;\nimport android.view.Gravity;\n',
    'ActionCardView Gravity import')
anchor = '''        secondaryValue.setTextColor(secondary);\n    }\n}'''
replacement = '''        secondaryValue.setTextColor(secondary);\n    }\n\n    /** Centers only the live value/detail lines; title/icon layout stays unchanged. */\n    public void setValueTextCentered(boolean centered) {\n        int gravity = centered\n                ? Gravity.CENTER\n                : (Gravity.START | Gravity.CENTER_VERTICAL);\n        primaryValue.setGravity(gravity);\n        secondaryValue.setGravity(gravity);\n        primaryValue.setTextAlignment(centered\n                ? TEXT_ALIGNMENT_CENTER : TEXT_ALIGNMENT_VIEW_START);\n        secondaryValue.setTextAlignment(centered\n                ? TEXT_ALIGNMENT_CENTER : TEXT_ALIGNMENT_VIEW_START);\n    }\n}'''
text = replace_once(text, anchor, replacement, 'ActionCardView method')
card.write_text(text, encoding='utf-8')

# Dashboard: wire Family To-Do card to existing repository and realtime listener.
text = frag.read_text(encoding='utf-8')
text = replace_once(text,
    'import com.tridev.familyhub.data.local.entity.FamilyMember;\n',
    'import com.tridev.familyhub.data.local.entity.FamilyMember;\nimport com.tridev.familyhub.data.local.entity.FamilyTask;\n',
    'FamilyTask import')
text = replace_once(text,
    'import com.tridev.familyhub.data.repository.DashboardRepository;\n',
    'import com.tridev.familyhub.data.repository.DashboardRepository;\nimport com.tridev.familyhub.data.repository.FamilyTaskRepository;\n',
    'FamilyTaskRepository import')
text = replace_once(text,
    'import com.tridev.familyhub.feature.safety.FamilySafetyCenterActivity;\n',
    'import com.tridev.familyhub.feature.safety.FamilySafetyCenterActivity;\nimport com.tridev.familyhub.feature.tasks.FamilyTasksFragment;\n',
    'FamilyTasksFragment import')
text = replace_once(text,
    '    private DashboardRepository dashboardRepository;\n',
    '    private DashboardRepository dashboardRepository;\n    @Nullable private FamilyTaskRepository taskRepository;\n',
    'task repository field')
text = replace_once(text,
    '        dashboardRepository = new DashboardRepository(requireContext());\n\n        bindStatusCards();',
    '        dashboardRepository = new DashboardRepository(requireContext());\n        taskRepository = new FamilyTaskRepository(requireContext());\n\n        bindStatusCards();',
    'task repository init')
text = replace_once(text,
    '        setupActionCards();\n        setupNotificationAction();',
    '        setupActionCards();\n        startTaskRealtimeSummary();\n        setupNotificationAction();',
    'start task summary')

setup_anchor = '''        binding.actionFamilyLive.setOnClickListener(\n                v -> openActivity(FamilyAutomationActivity.class));\n\n        binding.actionDocuments.setModel(new ActionCardModel('''
setup_replacement = '''        binding.actionFamilyLive.setOnClickListener(\n                v -> openActivity(FamilyAutomationActivity.class));\n        binding.actionTodo.setOnClickListener(\n                v -> openFeature(new FamilyTasksFragment()));\n\n        // Keep titles/icons unchanged; center only value/detail text on dashboard cards.\n        binding.actionPlanner.setValueTextCentered(true);\n        binding.actionGrocery.setValueTextCentered(true);\n        binding.actionDocuments.setValueTextCentered(true);\n        binding.actionVehicles.setValueTextCentered(true);\n        binding.actionNotes.setValueTextCentered(true);\n        binding.actionFamilyLive.setValueTextCentered(true);\n        binding.actionTodo.setValueTextCentered(true);\n\n        binding.actionTodo.setModel(new ActionCardModel(\n                getString(R.string.family_tasks_title),\n                getString(R.string.dashboard_todo_pending_today, 0, 0),\n                getString(R.string.dashboard_todo_completed, 0),\n                R.drawable.ic_family_task,\n                R.color.fh_success,\n                R.color.fh_success_container\n        ));\n\n        binding.actionDocuments.setModel(new ActionCardModel('''
text = replace_once(text, setup_anchor, setup_replacement, 'setupActionCards To-Do')

# Refresh task counts when returning to Dashboard as well as from realtime callbacks.
text = replace_once(text,
    '''        if (dashboardRepository != null) {\n            renderHeader();\n            loadDashboardData();\n        }''',
    '''        if (dashboardRepository != null) {\n            renderHeader();\n            loadDashboardData();\n            loadTaskSummary();\n        }''',
    'onResume task summary')

insert_before = '''    private void loadDashboardData() {'''
methods = '''    private void startTaskRealtimeSummary() {\n        FamilyTaskRepository repository = taskRepository;\n        if (repository == null) return;\n        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {\n            @Override public void onChanged(@NonNull FamilyTask task) {\n                loadTaskSummary();\n            }\n\n            @Override public void onRemoved(long localId) {\n                loadTaskSummary();\n            }\n        });\n        loadTaskSummary();\n    }\n\n    private void loadTaskSummary() {\n        FamilyTaskRepository repository = taskRepository;\n        if (repository == null) return;\n        repository.loadAll("", tasks -> {\n            if (binding == null) return;\n            int pending = 0;\n            int today = 0;\n            int completed = 0;\n            long todayStart = startOfToday();\n            Calendar tomorrow = Calendar.getInstance();\n            tomorrow.setTimeInMillis(todayStart);\n            tomorrow.add(Calendar.DAY_OF_YEAR, 1);\n            long tomorrowStart = tomorrow.getTimeInMillis();\n\n            for (FamilyTask task : tasks) {\n                if (FamilyTask.STATUS_COMPLETED.equals(task.status)) {\n                    completed++;\n                } else {\n                    pending++;\n                    if (task.dueAt >= todayStart && task.dueAt < tomorrowStart) {\n                        today++;\n                    }\n                }\n            }\n\n            binding.actionTodo.setModel(new ActionCardModel(\n                    getString(R.string.family_tasks_title),\n                    getString(R.string.dashboard_todo_pending_today, pending, today),\n                    getString(R.string.dashboard_todo_completed, completed),\n                    R.drawable.ic_family_task,\n                    R.color.fh_success,\n                    R.color.fh_success_container\n            ));\n            binding.actionTodo.setValueTextCentered(true);\n        });\n    }\n\n'''
text = replace_once(text, insert_before, methods + insert_before, 'task summary methods')

text = replace_once(text,
    '''        if (dashboardRepository != null) {\n            dashboardRepository.close();\n            dashboardRepository = null;\n        }\n        financeStatusCard = null;''',
    '''        if (dashboardRepository != null) {\n            dashboardRepository.close();\n            dashboardRepository = null;\n        }\n        if (taskRepository != null) {\n            taskRepository.stopRealtimeSync();\n            taskRepository = null;\n        }\n        financeStatusCard = null;''',
    'task repository destroy')
frag.write_text(text, encoding='utf-8')

# Add a dedicated full-width To-Do row below the existing six cards.
text = layout.read_text(encoding='utf-8')
anchor = '''        <LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="96dp"\n            android:layout_marginTop="@dimen/space_8"\n            android:orientation="horizontal">\n\n            <com.tridev.familyhub.core.ui.cards.ActionCardView\n                android:id="@+id/action_notes"\n                android:layout_width="0dp"\n                android:layout_height="match_parent"\n                android:layout_marginEnd="@dimen/space_4"\n                android:layout_weight="1" />\n\n            <com.tridev.familyhub.core.ui.cards.ActionCardView\n                android:id="@+id/action_family_live"\n                android:layout_width="0dp"\n                android:layout_height="match_parent"\n                android:layout_marginStart="@dimen/space_4"\n                android:layout_weight="1" />\n        </LinearLayout>\n\n        </LinearLayout>'''
replacement = '''        <LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="96dp"\n            android:layout_marginTop="@dimen/space_8"\n            android:orientation="horizontal">\n\n            <com.tridev.familyhub.core.ui.cards.ActionCardView\n                android:id="@+id/action_notes"\n                android:layout_width="0dp"\n                android:layout_height="match_parent"\n                android:layout_marginEnd="@dimen/space_4"\n                android:layout_weight="1" />\n\n            <com.tridev.familyhub.core.ui.cards.ActionCardView\n                android:id="@+id/action_family_live"\n                android:layout_width="0dp"\n                android:layout_height="match_parent"\n                android:layout_marginStart="@dimen/space_4"\n                android:layout_weight="1" />\n        </LinearLayout>\n\n        <LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="96dp"\n            android:layout_marginTop="@dimen/space_8"\n            android:orientation="horizontal">\n\n            <com.tridev.familyhub.core.ui.cards.ActionCardView\n                android:id="@+id/action_todo"\n                android:layout_width="match_parent"\n                android:layout_height="match_parent" />\n        </LinearLayout>\n\n        </LinearLayout>'''
text = replace_once(text, anchor, replacement, 'To-Do dashboard row')
layout.write_text(text, encoding='utf-8')

strings.write_text('''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="dashboard_todo_pending_today">%1$d pending • %2$d today</string>\n    <string name="dashboard_todo_completed">%1$d completed</string>\n</resources>\n''', encoding='utf-8')
