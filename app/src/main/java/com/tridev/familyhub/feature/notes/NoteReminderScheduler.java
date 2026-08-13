package com.tridev.familyhub.feature.notes;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.tridev.familyhub.data.local.entity.NoteEntry;
import java.util.concurrent.TimeUnit;
public final class NoteReminderScheduler {
 private NoteReminderScheduler(){}
 public static void sync(@NonNull Context c,@NonNull NoteEntry n){cancel(c,n.id);long d=n.reminderAt-System.currentTimeMillis();if(n.id<=0||d<=0)return;Data data=new Data.Builder().putString("title",n.title).putLong("id",n.id).build();OneTimeWorkRequest r=new OneTimeWorkRequest.Builder(NoteReminderWorker.class).setInputData(data).setInitialDelay(d,TimeUnit.MILLISECONDS).build();WorkManager.getInstance(c).enqueueUniqueWork("note_reminder_"+n.id,ExistingWorkPolicy.REPLACE,r);}
 public static void cancel(@NonNull Context c,long id){if(id>0)WorkManager.getInstance(c).cancelUniqueWork("note_reminder_"+id);}
}
