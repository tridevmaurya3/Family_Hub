package com.tridev.familyhub.data.repository;

import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persists ownership even when an upload finishes after the original form was closed. */
final class FamilySyncAcknowledgements {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FamilySyncAcknowledgements() { }
    static Task<Void> apply(Context context, String uid, String module, String id, String family, long submittedAt) {
        return Tasks.call(executor, () -> {
            FamilyHubDatabase db = FamilyHubDatabase.getForAccount(context,uid);
            db.runInTransaction(() -> {
                switch (module) {
                    case "familyProfiles": { db.familyMemberDao().markSynced(id,submittedAt); break; }
                    case "notes": { NoteEntry row=db.noteDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.noteDao().update(row);} break; }
                    case "planner": { PlannerItem row=db.plannerItemDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.plannerItemDao().update(row);} break; }
                    case "reminders": { Reminder row=db.reminderDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.reminderDao().update(row);} break; }
                    case "tasks": { FamilyTask row=db.familyTaskDao().getByCloudId(id); if(row!=null && row.shared){row.familyId=family;row.updatedByUid=uid;db.familyTaskDao().update(row);} break; }
                    case "finance": { FinanceEntry row=db.financeEntryDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.financeEntryDao().update(row);} break; }
                    case "health": { HealthRecord row=db.healthRecordDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.healthRecordDao().update(row);} break; }
                    case "vehicles": { Vehicle row=db.vehicleDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.vehicleDao().update(row);} break; }
                    case "properties": { PropertyEntry row=db.propertyDao().getByCloudId(id); if(row!=null && row.isShared){row.familyId=family;row.updatedByUid=uid;db.propertyDao().update(row);} break; }
                }
            });
            return null;
        });
    }
}
