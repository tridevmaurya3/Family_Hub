package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A device-only queue. A stable Firebase message ID makes delivery retries idempotent. */
public final class ChatScheduledMessages {
    private static final Object LOCK = new Object();
    private static final String PREFIX = "scheduled-chat-";

    static final class Entry {
        final String id, uid, path, body, version;
        final long due;
        final boolean sending;

        Entry(String id, String uid, String path, String body, long due, String version, boolean sending) {
            this.id = id;
            this.uid = uid;
            this.path = path;
            this.body = body;
            this.due = due;
            this.version = version;
            this.sending = sending;
        }

        String encode() throws JSONException {
            return new JSONObject().put("uid", uid).put("path", path).put("body", body)
                    .put("due", due).put("version", version).put("sending", sending).toString();
        }

        static Entry decode(String id, String raw) {
            if (raw == null) return null;
            try {
                JSONObject json = new JSONObject(raw);
                return new Entry(id, json.getString("uid"), json.getString("path"), json.getString("body"),
                        json.getLong("due"), json.getString("version"), json.optBoolean("sending"));
            } catch (JSONException error) {
                return null;
            }
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("family_chat_schedule", Context.MODE_PRIVATE);
    }

    static List<Entry> list(Context context, String uid, String path) {
        List<Entry> rows = new ArrayList<>();
        preferences(context).getAll().forEach((id, value) -> {
            if (!(value instanceof String)) return;
            Entry entry = Entry.decode(id, (String) value);
            if (entry != null && entry.uid.equals(uid) && entry.path.equals(path)) rows.add(entry);
        });
        rows.sort(Comparator.comparingLong(entry -> entry.due));
        return rows;
    }

    static boolean put(Context context, String uid, String path, String body, long due, Entry previous) {
        if (uid == null || body.trim().isEmpty() || body.length() > 2000 || due <= System.currentTimeMillis()) return false;
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (previous != null) {
                Entry current = Entry.decode(previous.id, preferences.getString(previous.id, null));
                if (current == null || current.sending || !current.version.equals(previous.version)
                        || !current.uid.equals(uid) || !current.path.equals(path)) return false;
            }
            String id = previous == null ? UUID.randomUUID().toString() : previous.id;
            Entry entry = new Entry(id, uid, path, body.trim(), due, UUID.randomUUID().toString(), false);
            try {
                if (!preferences.edit().putString(id, entry.encode()).commit()) return false;
                OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(Sender.class)
                        .setInputData(new Data.Builder().putString("id", id).putString("version", entry.version).build())
                        .setInitialDelay(Math.max(0L, due - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build();
                WorkManager.getInstance(context).enqueueUniqueWork(PREFIX + id, ExistingWorkPolicy.REPLACE, work);
                return true;
            } catch (JSONException | RuntimeException error) {
                try {
                    if (previous == null) preferences.edit().remove(id).commit();
                    else preferences.edit().putString(id, previous.encode()).commit();
                } catch (JSONException ignored) {}
                return false;
            }
        }
    }

    static boolean cancel(Context context, Entry entry) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            Entry current = Entry.decode(entry.id, preferences.getString(entry.id, null));
            if (current == null || current.sending || !current.version.equals(entry.version)) return false;
            if (!preferences.edit().remove(entry.id).commit()) return false;
            WorkManager.getInstance(context).cancelUniqueWork(PREFIX + entry.id);
            return true;
        }
    }

    public static final class Sender extends Worker {
        public Sender(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

        @NonNull @Override public Result doWork() {
            String id = getInputData().getString("id");
            String version = getInputData().getString("version");
            if (id == null || version == null) return Result.failure();
            SharedPreferences preferences = preferences(getApplicationContext());
            Entry entry;
            synchronized (LOCK) {
                entry = Entry.decode(id, preferences.getString(id, null));
                if (entry == null || !version.equals(entry.version)) return Result.success();
                String uid = currentUid();
                if (!entry.uid.equals(uid)) return Result.retry();
                if (System.currentTimeMillis() < entry.due) return Result.retry();
                if (!entry.sending) {
                    try {
                        Entry sending = new Entry(id, entry.uid, entry.path, entry.body, entry.due, version, true);
                        if (!preferences.edit().putString(id, sending.encode()).commit()) return Result.retry();
                    } catch (JSONException error) {
                        return Result.failure();
                    }
                }
            }
            try {
                if (!entry.uid.equals(currentUid()) || isStopped()) return Result.retry();
                FirebaseDatabase database = FirebaseDatabase.getInstance();
                DatabaseReference message = database.getReferenceFromUrl(entry.path).child(PREFIX + id);
                com.google.firebase.database.DataSnapshot prior = Tasks.await(message.get(), 20, TimeUnit.SECONDS);
                if (!entry.uid.equals(currentUid()) || isStopped()) return Result.retry();
                if (!prior.exists()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("sender", entry.uid);
                    payload.put("body", entry.body);
                    payload.put("at", ServerValue.TIMESTAMP);
                    Tasks.await(message.setValue(payload), 20, TimeUnit.SECONDS);
                } else if (!entry.uid.equals(prior.child("sender").getValue(String.class))) {
                    return Result.failure();
                }
                preferences.edit().remove(id).commit();
                return Result.success();
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause();
                String message = cause == null ? "" : String.valueOf(cause.getMessage());
                if (message.toLowerCase(java.util.Locale.ROOT).contains("permission denied")
                        && entry.uid.equals(currentUid())) {
                    synchronized (LOCK) {
                        try {
                            Entry stopped = new Entry(id, entry.uid, entry.path, entry.body, entry.due, version, false);
                            if (!preferences.edit().putString(id, stopped.encode()).commit()) return Result.retry();
                        } catch (JSONException ignored) {}
                    }
                    return Result.failure();
                }
                return Result.retry();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.retry();
            } catch (java.util.concurrent.TimeoutException | IllegalArgumentException failure) {
                return Result.retry();
            }
        }

        private String currentUid() {
            com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return user == null ? null : user.getUid();
        }
    }
}
