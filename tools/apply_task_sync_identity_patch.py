from pathlib import Path

path = Path('app/src/main/java/com/tridev/familyhub/data/repository/FamilyTaskRepository.java')
text = path.read_text(encoding='utf-8')

def once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 anchor, found {count}')
    text = text.replace(old, new, 1)

once('import java.util.List;\nimport java.util.Map;\n',
     'import java.util.List;\nimport java.util.Locale;\nimport java.util.Map;\n',
     'Locale import')

once('''            List<FamilyTask> tasks = clean.isEmpty() ? dao.getAll() : dao.search(clean);\n            mainHandler.post(() -> callback.onLoaded(tasks));''',
     '''            List<FamilyTask> tasks = clean.isEmpty() ? dao.getAll() : dao.search(clean);\n            for (FamilyTask task : tasks) task.priority = normalizePriority(task.priority);\n            mainHandler.post(() -> callback.onLoaded(tasks));''',
     'load priority normalization')

once('''        DATABASE_EXECUTOR.execute(() -> {\n            boolean inserting = task.id == 0L;\n            FamilyTask previous = inserting ? null : dao.getById(task.id);\n            boolean assignmentChanged = previous != null && assignmentChanged(previous, task);\n            boolean detailsChanged = previous != null && detailsChanged(previous, task);\n\n            long now = System.currentTimeMillis();\n            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();\n            if (task.createdAt == 0L) task.createdAt = now;\n            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();''',
     '''        DATABASE_EXECUTOR.execute(() -> {\n            // Preserve one canonical identity across Add/Edit and across devices.\n            // Existing Room identity wins first; cloudId then makes detached/replayed saves idempotent.\n            FamilyTask byId = task.id == 0L ? null : dao.getById(task.id);\n            if (byId != null && task.cloudId.trim().isEmpty()) task.cloudId = byId.cloudId;\n            if (task.cloudId.trim().isEmpty()) task.cloudId = UUID.randomUUID().toString();\n            FamilyTask byCloudId = dao.getByCloudId(task.cloudId);\n            FamilyTask previous = byCloudId != null ? byCloudId : byId;\n            if (previous != null) task.id = previous.id;\n            boolean inserting = previous == null;\n            boolean assignmentChanged = previous != null && assignmentChanged(previous, task);\n            boolean detailsChanged = previous != null && detailsChanged(previous, task);\n\n            long now = System.currentTimeMillis();\n            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();\n            if (task.createdAt == 0L) task.createdAt = previous == null ? now : previous.createdAt;\n            if (task.createdByUid.isEmpty() && previous != null) task.createdByUid = previous.createdByUid;\n            if (task.createdByName.isEmpty() && previous != null) task.createdByName = previous.createdByName;\n            task.priority = normalizePriority(task.priority);''',
     'idempotent save identity')

once('''            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();\n            if (task.cloudId.isEmpty()) task.cloudId = UUID.randomUUID().toString();\n            if (task.createdAt == 0L) task.createdAt = now;''',
     '''            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();\n            FamilyTask persisted = task.id == 0L ? null : dao.getById(task.id);\n            if (persisted != null && task.cloudId.trim().isEmpty()) task.cloudId = persisted.cloudId;\n            if (task.cloudId.trim().isEmpty()) task.cloudId = UUID.randomUUID().toString();\n            task.priority = normalizePriority(task.priority);\n            if (task.createdAt == 0L) task.createdAt = persisted == null ? now : persisted.createdAt;''',
     'completion identity guard')

once('''    private void publish(@NonNull FamilyTask task) {\n        Map<String, Object> values = new HashMap<>();''',
     '''    private void publish(@NonNull FamilyTask task) {\n        task.priority = normalizePriority(task.priority);\n        Map<String, Object> values = new HashMap<>();''',
     'publish normalization')

once('''            task.priority = fallback(text(s, "priority"), FamilyTask.PRIORITY_NORMAL);''',
     '''            task.priority = normalizePriority(text(s, "priority"));''',
     'remote priority normalization')

once('''    @NonNull private static String fallback(String value, String fallback) {\n        return value.isEmpty() ? fallback : value;\n    }\n}''',
     '''    @NonNull private static String fallback(String value, String fallback) {\n        return value.isEmpty() ? fallback : value;\n    }\n\n    @NonNull\n    private static String normalizePriority(@Nullable String value) {\n        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);\n        if (FamilyTask.PRIORITY_URGENT.equals(normalized)) return FamilyTask.PRIORITY_URGENT;\n        if (FamilyTask.PRIORITY_HIGH.equals(normalized)) return FamilyTask.PRIORITY_HIGH;\n        return FamilyTask.PRIORITY_NORMAL;\n    }\n}''',
     'priority helper')

path.write_text(text, encoding='utf-8')
