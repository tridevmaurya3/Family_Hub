package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tridev.familyhub.core.security.AccountDataScope;
import com.tridev.familyhub.core.security.AccountScopePolicy;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Durable, account-owned mutations. A server acknowledgement clears only its own revision. */
public final class FamilySyncOutbox {
    private static Context context;
    private static final Map<String, Task<Void>> running = new HashMap<>();
    private static final Map<String, Map<String,String>> rejected = new HashMap<>();
    private static final Map<String, Exception> failures = new HashMap<>();
    private static final Map<String, FamilyCollaborationPublisher.PublishedCallback> callbacks = new HashMap<>();
    private FamilySyncOutbox() { }

    public static synchronized void initialize(Context app) {
        if (context != null) return;
        context = app.getApplicationContext();
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            String uid = AccountDataScope.uid();
            if (!uid.isEmpty()) {
                schedule(uid);
                com.tridev.familyhub.core.security.AccountReminderRestore.restore(context,uid);
                com.tridev.familyhub.backup.BackupScheduler.sync(context);
            }
        });
    }

    private static SharedPreferences preferences(String uid) {
        return context.getSharedPreferences("family_sync_outbox_" + AccountScopePolicy.key(uid), Context.MODE_PRIVATE);
    }

    static synchronized void enqueue(String uid, String module, String cloudId, Map<String,Object> values,
                                    FamilyCollaborationPublisher.PublishedCallback callback) {
        if (context == null || !uid.equals(AccountDataScope.uid())) return;
        String key = module + "/" + cloudId;
        SharedPreferences prefs = preferences(uid);
        try {
            JSONObject old = new JSONObject(prefs.getString("pending:" + key, "{}"));
            Map<String,Object> merged = FamilyMutationPolicy.merge(decode(old.optJSONObject("values")),old.optBoolean("delete"),values);
            JSONObject record = new JSONObject().put("module", module).put("cloudId", cloudId)
                    .put("revision", UUID.randomUUID().toString()).put("delete", false)
                    .put("familyId", old.optString("familyId", prefs.getString("family:" + key, "")))
                    .put("updatedAt", System.currentTimeMillis()).put("values", new JSONObject(merged));
            if (!prefs.edit().putString("pending:" + key, record.toString()).commit())
                throw new IllegalStateException("Pending change could not be saved");
            callbacks.put(uid + "/" + key, callback);
            schedule(uid);
            drain(uid);
        } catch (JSONException error) { throw new IllegalStateException("Pending change could not be encoded", error); }
    }

    static synchronized void remove(String uid, String module, String familyId, String cloudId) {
        if (context == null || !uid.equals(AccountDataScope.uid()) || cloudId.trim().isEmpty()) return;
        String key = module + "/" + cloudId;
        SharedPreferences prefs = preferences(uid);
        try {
            JSONObject old = new JSONObject(prefs.getString("pending:" + key, "{}"));
            String family = familyId.isEmpty() ? old.optString("familyId", prefs.getString("family:" + key, "")) : familyId;
            SharedPreferences.Editor edit = prefs.edit();
            if (family.isEmpty()) edit.remove("pending:" + key);
            else edit.putString("pending:" + key, new JSONObject().put("module", module).put("cloudId", cloudId)
                    .put("revision", UUID.randomUUID().toString()).put("delete", true).put("familyId", family).toString());
            if (!edit.commit()) throw new IllegalStateException("Pending deletion could not be saved");
            callbacks.remove(uid + "/" + key);
            schedule(uid);
            drain(uid);
        } catch (JSONException error) { throw new IllegalStateException("Pending deletion could not be encoded", error); }
    }

    static synchronized boolean pendingDelete(String uid, String module, String cloudId) {
        if (context == null || uid.isEmpty()) return false;
        try { return new JSONObject(preferences(uid).getString("pending:" + module + "/" + cloudId,"{}")).optBoolean("delete"); }
        catch (JSONException error) { return false; }
    }

    private static void schedule(String uid) {
        if (context == null || uid.isEmpty()) return;
        Data data = new Data.Builder().putString(AccountDataScope.EXTRA_UID, uid).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(FamilySyncRetryWorker.class)
                .setInputData(data).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("family_sync_" + AccountScopePolicy.key(uid), ExistingWorkPolicy.APPEND_OR_REPLACE, work);
    }

    public static synchronized Task<Void> drain(String uid) {
        if (context == null || uid.isEmpty() || !uid.equals(AccountDataScope.uid()))
            return Tasks.forException(new IllegalStateException("Account changed"));
        Task<Void> active = running.get(uid);
        if (active != null) return active;
        TaskCompletionSource<Void> completion = new TaskCompletionSource<>();
        running.put(uid, completion.getTask());
        rejected.put(uid,new HashMap<>());
        failures.remove(uid);
        next(uid, completion);
        return completion.getTask();
    }

    private static synchronized void next(String uid, TaskCompletionSource<Void> completion) {
        if (!uid.equals(AccountDataScope.uid())) { finish(uid, completion, new IllegalStateException("Account changed")); return; }
        SharedPreferences prefs = preferences(uid);
        String selected = null;
        for (String key : new TreeSet<>(prefs.getAll().keySet())) {
            if (key.startsWith("pending:")) {
                try {
                    String revision = new JSONObject(prefs.getString(key,"{}")).optString("revision");
                    if (!revision.equals(rejected.get(uid).get(key))) { selected = key; break; }
                } catch (JSONException error) { finish(uid,completion,error); return; }
            }
        }
        if (selected == null) { finish(uid, completion, failures.get(uid)); return; }
        final String key = selected;
        final JSONObject record;
        try { record = new JSONObject(prefs.getString(key, "{}")); }
        catch (JSONException error) { finish(uid, completion, error); return; }
        String family = record.optString("familyId");
        if (!family.isEmpty()) { send(uid, key, record, completion); return; }
        new FamilyAccountRepository().loadSession(new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
            @Override public void onSuccess(FamilyAccountRepository.SessionState state) {
                synchronized (FamilySyncOutbox.class) {
                    if (!uid.equals(AccountDataScope.uid()) || state == null || !state.isActive() || state.familyId == null) {
                        finish(uid, completion, new IllegalStateException("Active family unavailable")); return;
                    }
                    try {
                        // A deletion or later edit while resolving the family invalidates this revision.
                        JSONObject latest = new JSONObject(prefs.getString(key, "{}"));
                        if (!FamilyMutationPolicy.acknowledged(record.optString("revision"),latest.optString("revision"))) { next(uid, completion); return; }
                        record.put("familyId", state.familyId);
                        if (!prefs.edit().putString(key, record.toString()).putString("family:" + key.substring(8), state.familyId).commit()) {
                            finish(uid, completion, new IllegalStateException("Family binding could not be saved")); return;
                        }
                        send(uid, key, record, completion);
                    } catch (JSONException error) { finish(uid, completion, error); }
                }
            }
            @Override public void onError(@NonNull Exception error) { finish(uid, completion, error); }
        });
    }

    private static synchronized void send(String uid, String key, JSONObject record, TaskCompletionSource<Void> completion) {
        if (!uid.equals(AccountDataScope.uid())) { finish(uid, completion, new IllegalStateException("Account changed")); return; }
        String family = record.optString("familyId"), module = record.optString("module"), id = record.optString("cloudId");
        com.google.firebase.database.DatabaseReference reference = FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(family).child(module).child(id);
        Map<String,Object> payload = decode(record.optJSONObject("values"));
        payload.put("cloudId", id); payload.put("familyId", family); payload.put("updatedByUid", uid);
        payload.put("updatedAt", record.optLong("updatedAt")); payload.put("serverUpdatedAt", ServerValue.TIMESTAMP);
        Task<Void> write = record.optBoolean("delete") ? reference.removeValue() : reference.updateChildren(payload);
        write.addOnSuccessListener(unused -> {
            synchronized (FamilySyncOutbox.class) {
                SharedPreferences prefs = preferences(uid);
                try {
                    JSONObject latest = new JSONObject(prefs.getString(key, "{}"));
                    if (FamilyMutationPolicy.acknowledged(record.optString("revision"),latest.optString("revision"))) {
                        if (!prefs.edit().remove(key).commit()) { finish(uid, completion, new IllegalStateException("Acknowledgement could not be saved")); return; }
                        FamilyCollaborationPublisher.PublishedCallback callback = callbacks.remove(uid + "/" + key.substring(8));
                        if (callback != null && !record.optBoolean("delete") && uid.equals(AccountDataScope.uid())) callback.onPublished(id, family, uid);
                    }
                    next(uid, completion);
                } catch (JSONException error) { finish(uid, completion, error); }
            }
        }).addOnFailureListener(error -> {
            synchronized (FamilySyncOutbox.class) {
                rejected.get(uid).put(key,record.optString("revision"));
                failures.put(uid,error);
                next(uid,completion);
            }
        });
    }

    private static synchronized void finish(String uid, TaskCompletionSource<Void> completion, Exception error) {
        running.remove(uid);
        rejected.remove(uid);
        failures.remove(uid);
        if (error == null) completion.trySetResult(null); else completion.trySetException(error);
    }

    private static Map<String,Object> decode(JSONObject object) {
        Map<String,Object> result = new HashMap<>();
        if (object == null) return result;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); result.put(key, value(object.opt(key))); }
        return result;
    }
    private static Object value(Object value) {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return decode((JSONObject)value);
        if (value instanceof JSONArray) {
            List<Object> values = new ArrayList<>(); JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); i++) values.add(value(array.opt(i)));
            return values;
        }
        return value;
    }
}
