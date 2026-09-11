from pathlib import Path
import re

root = Path('.')
frag = root / 'app/src/main/java/com/tridev/familyhub/feature/dashboard/DashboardFragment.java'
layout = root / 'app/src/main/res/layout/fragment_dashboard.xml'
strings = root / 'app/src/main/res/values/strings.xml'


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 anchor, found {count}')
    return text.replace(old, new, 1)

# 1) Dashboard layout: remove Daily Briefing card and move To-Do to the top of Action Center.
text = layout.read_text(encoding='utf-8')
pattern = re.compile(r'\n        <com\.google\.android\.material\.card\.MaterialCardView\n            android:id="@\+id/dashboard_daily_briefing_card".*?\n        </com\.google\.android\.material\.card\.MaterialCardView>\n', re.S)
text, count = pattern.subn('\n', text, count=1)
if count != 1:
    raise SystemExit(f'Daily Briefing card removal: expected 1, found {count}')

todo_block = '''        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="96dp"
            android:layout_marginTop="@dimen/space_8"
            android:orientation="horizontal">

            <com.tridev.familyhub.core.ui.cards.ActionCardView
                android:id="@+id/action_todo"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
        </LinearLayout>

'''
text = replace_once(text, todo_block, '', 'remove old To-Do row')
insert_anchor = '''        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="96dp"
            android:layout_marginTop="@dimen/space_8"
            android:orientation="horizontal">

            <com.tridev.familyhub.core.ui.cards.ActionCardView
                android:id="@+id/action_planner"'''
text = replace_once(text, insert_anchor, todo_block + insert_anchor, 'insert To-Do first')
layout.write_text(text, encoding='utf-8')

# 2) DashboardFragment: remove briefing code; include To-Do in Family Priorities.
text = frag.read_text(encoding='utf-8')
text = replace_once(text,
    '    private static final String KEY_BRIEFING_EXPANDED = "briefing_expanded";\n',
    '',
    'remove briefing preference key')
text = replace_once(text,
    '        setupDailyBriefingPreference();\n',
    '',
    'remove briefing setup call')

start = text.find('    private void setupDailyBriefingPreference() {')
end = text.find('    private void setupDashboardSectionPreferences() {', start)
if start < 0 or end < 0:
    raise SystemExit('briefing preference methods anchors not found')
text = text[:start] + text[end:]

text = replace_once(text,
    '                    renderDailyBriefing(data);\n',
    '',
    'remove renderDailyBriefing call')
start = text.find('    private void renderDailyBriefing(@NonNull DashboardData data) {')
end = text.find('    private void renderPrioritySummary(@NonNull DashboardData data) {', start)
if start < 0 or end < 0:
    raise SystemExit('renderDailyBriefing method anchors not found')
text = text[:start] + text[end:]

text = replace_once(text,
    '    @Nullable private FamilyTaskRepository taskRepository;\n',
    '    @Nullable private FamilyTaskRepository taskRepository;\n    @Nullable private DashboardData latestDashboardData;\n    private int taskPendingCount;\n',
    'priority task fields')

text = replace_once(text,
    '                    binding.dashboardLoading.setVisibility(View.GONE);\n                    binding.dashboardErrorCard.setVisibility(View.GONE);\n                    renderFinance(data.getStats());',
    '                    binding.dashboardLoading.setVisibility(View.GONE);\n                    binding.dashboardErrorCard.setVisibility(View.GONE);\n                    latestDashboardData = data;\n                    renderFinance(data.getStats());',
    'latest dashboard data')

text = replace_once(text,
    '            binding.actionTodo.setModel(new ActionCardModel(',
    '            taskPendingCount = pending;\n            DashboardData priorityData = latestDashboardData;\n            if (priorityData != null) {\n                renderPrioritySummary(priorityData);\n            }\n\n            binding.actionTodo.setModel(new ActionCardModel(',
    'refresh priorities from task summary')

text = replace_once(text,
    '        int pending = stats.getPlannerOpen() + stats.getGroceryPending();\n',
    '        int pending = stats.getPlannerOpen() + stats.getGroceryPending() + taskPendingCount;\n',
    'priority pending total')
text = replace_once(text,
    '                R.string.dashboard_pending_breakdown,\n                stats.getPlannerOpen(), stats.getGroceryPending()));',
    '                R.string.dashboard_pending_breakdown,\n                stats.getPlannerOpen(), stats.getGroceryPending(), taskPendingCount));',
    'priority pending breakdown')

old_method = '''    private void openPendingPriority(@NonNull DashboardStats stats) {
        int planner = stats.getPlannerOpen();
        int grocery = stats.getGroceryPending();
        if (planner <= 0 && grocery <= 0) {
            showNoPriorityData();
        } else if (planner > 0 && grocery <= 0) {
            openFeature(new PlannerFragment());
        } else if (grocery > 0 && planner <= 0) {
            openFeature(new GroceryFragment());
        } else {
            String[] labels = {
                    getString(R.string.dashboard_priority_planner, planner),
                    getString(R.string.dashboard_priority_grocery, grocery)
            };
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dashboard_priority_choose)
                    .setItems(labels, (dialog, which) -> {
                        if (which == 0) openFeature(new PlannerFragment());
                        else openFeature(new GroceryFragment());
                    }).show();
        }
    }
'''
new_method = '''    private void openPendingPriority(@NonNull DashboardStats stats) {
        int planner = stats.getPlannerOpen();
        int grocery = stats.getGroceryPending();
        int todo = taskPendingCount;

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if (planner > 0) {
            labels.add(getString(R.string.dashboard_priority_planner, planner));
            actions.add(() -> openFeature(new PlannerFragment()));
        }
        if (grocery > 0) {
            labels.add(getString(R.string.dashboard_priority_grocery, grocery));
            actions.add(() -> openFeature(new GroceryFragment()));
        }
        if (todo > 0) {
            labels.add(getString(R.string.dashboard_priority_todo, todo));
            actions.add(() -> openFeature(new FamilyTasksFragment()));
        }

        if (labels.isEmpty()) {
            showNoPriorityData();
        } else if (labels.size() == 1) {
            actions.get(0).run();
        } else {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dashboard_priority_choose)
                    .setItems(labels.toArray(new String[0]),
                            (dialog, which) -> actions.get(which).run())
                    .show();
        }
    }
'''
text = replace_once(text, old_method, new_method, 'openPendingPriority')

text = replace_once(text,
    '            taskRepository = null;\n        }\n        financeStatusCard = null;',
    '            taskRepository = null;\n        }\n        latestDashboardData = null;\n        taskPendingCount = 0;\n        financeStatusCard = null;',
    'destroy task priority state')
frag.write_text(text, encoding='utf-8')

# 3) Strings: show To-Do in pending breakdown and chooser.
text = strings.read_text(encoding='utf-8')
text = replace_once(text,
    '<string name="dashboard_pending_breakdown">Planner %1$d · Grocery %2$d</string>',
    '<string name="dashboard_pending_breakdown">Planner %1$d · Grocery %2$d · To-Do %3$d</string>',
    'pending breakdown string')
text = replace_once(text,
    '<string name="dashboard_priority_grocery">Grocery — %1$d pending</string>',
    '<string name="dashboard_priority_grocery">Grocery — %1$d pending</string>\n    <string name="dashboard_priority_todo">To-Do — %1$d pending</string>',
    'To-Do priority string')
strings.write_text(text, encoding='utf-8')
