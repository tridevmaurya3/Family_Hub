package com.tridev.familyhub.feature.chat;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Keeps delivery listeners active while the signed-in app process is running. */
public final class FamilyChatStore {
    static final String GROUP = "";
    private static final FamilyChatStore INSTANCE = new FamilyChatStore();
    static FamilyChatStore get() { return INSTANCE; }

    private final DatabaseReference root = FirebaseDatabase.getInstance().getReference();
    private final List<Runnable> observers = new ArrayList<>();
    private final Map<String, ThreadState> threads = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Map<String, Boolean> writingReceipts = new HashMap<>();
    private Query sessionQuery;
    private Query membershipQuery;
    private ValueEventListener sessionListener;
    private ValueEventListener membershipListener;
    private String uid;
    private String family;
    private boolean connected;
    private boolean started;
    private String status = "Connecting…";

    private FamilyChatStore() {}

    public static void startForApp() { INSTANCE.start(); }

    private void start() {
        if (started) return;
        started = true;
        FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            String signedIn = auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid();
            if (java.util.Objects.equals(signedIn, uid)) return;
            if (sessionQuery != null) sessionQuery.removeEventListener(sessionListener);
            sessionQuery = null;
            clearFamily();
            pending.clear();
            uid = signedIn;
            status = signedIn == null ? "Sign in to use Family Chat" : "Connecting…";
            notifyObservers();
            if (signedIn == null) return;
            sessionQuery = root.child("users").child(signedIn).child("familyId");
            sessionListener = listen(sessionQuery, snapshot -> {
                if (signedIn.equals(uid)) selectFamily(snapshot.getValue(String.class));
            }, error -> {
                if (!signedIn.equals(uid)) return;
                selectFamily(null);
                status = "Chat is unavailable";
                notifyObservers();
            });
        });
        listen(root.child(".info").child("connected"), snapshot -> {
            connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
            if (connected) for (ThreadState thread : threads.values()) acknowledgeDelivery(thread);
            notifyObservers();
        }, error -> {});
    }

    private void selectFamily(String id) {
        if (id != null && id.isEmpty()) id = null;
        if (id != null && id.equals(family)) return;
        clearFamily();
        family = id;
        status = id == null ? "Join a family to use Family Chat" : "Connecting…";
        notifyObservers();
        if (id == null) return;
        String expectedFamily = id;
        membershipQuery = root.child("memberships").child(id);
        membershipListener = listen(membershipQuery, snapshot -> {
            if (!expectedFamily.equals(family)) return;
            names.clear();
            for (DataSnapshot member : snapshot.getChildren()) {
                if (!"ACTIVE".equals(member.child("status").getValue(String.class))) continue;
                String name = member.child("displayName").getValue(String.class);
                names.put(member.getKey(), name == null || name.trim().isEmpty() ? "Family member" : name);
            }
            if (!names.containsKey(uid)) {
                clearThreads();
                names.clear();
                status = "Active family membership is required";
                notifyObservers();
                return;
            }
            status = "";
            for (String peer : new ArrayList<>(threads.keySet())) {
                if (!GROUP.equals(peer) && !names.containsKey(peer)) removeThread(peer);
            }
            addThread(GROUP);
            for (String peer : names.keySet()) if (!peer.equals(uid)) addThread(peer);
            notifyObservers();
        }, error -> {
            if (!expectedFamily.equals(family)) return;
            clearThreads();
            names.clear();
            status = "Family Chat is unavailable";
            notifyObservers();
        });
    }

    private void addThread(String peer) {
        if (threads.containsKey(peer)) return;
        DatabaseReference base = root.child("familyChat").child(family);
        DatabaseReference messages;
        if (GROUP.equals(peer)) {
            messages = base.child("group");
        } else {
            String first = uid.compareTo(peer) < 0 ? uid : peer;
            String second = first.equals(uid) ? peer : uid;
            messages = base.child("direct").child(first).child(second);
        }
        ThreadState thread = new ThreadState(peer, messages);
        threads.put(peer, thread);
        thread.listener = listen(thread.query, snapshot -> {
            if (threads.get(peer) != thread) return;
            thread.messages.clear();
            thread.error = false;
            for (DataSnapshot child : snapshot.getChildren()) {
                String sender = child.child("sender").getValue(String.class);
                String body = child.child("body").getValue(String.class);
                Long at = child.child("at").getValue(Long.class);
                if (sender != null && body != null && at != null) {
                    thread.messages.add(new Message(child, sender, body, at));
                }
            }
            acknowledgeDelivery(thread);
            notifyObservers();
        }, error -> {
            if (threads.get(peer) != thread) return;
            thread.messages.clear();
            thread.error = true;
            notifyObservers();
        });
    }

    private void acknowledgeDelivery(ThreadState thread) {
        if (!connected) return;
        for (Message message : thread.messages) receipt(thread, message, false);
    }

    void receipt(ThreadState thread, Message message, boolean read) {
        if (!connected || uid == null || own(message) || threads.get(thread.peer) != thread
                || message.received(uid, read)) return;
        DatabaseReference path = message.snapshot.getRef().child("receipts").child(uid);
        String key = path.toString();
        if (writingReceipts.containsKey(key)) return;
        writingReceipts.put(key, read);
        Map<String, Object> update = new HashMap<>();
        if (!message.received(uid, false)) update.put("deliveredAt", ServerValue.TIMESTAMP);
        if (read) update.put("readAt", ServerValue.TIMESTAMP);
        path.updateChildren(update).addOnCompleteListener(task -> {
            writingReceipts.remove(key);
            // A read request can arrive while the delivery write is in flight.
            if (task.isSuccessful() && !read) notifyObservers();
        });
    }

    void send(ThreadState thread, String body, Runnable failed) {
        if (uid == null || threads.get(thread.peer) != thread || thread.error) return;
        DatabaseReference message = thread.ref.push();
        String key = message.getKey();
        if (key == null) return;
        pending.add(key);
        String sender = uid;
        Map<String, Object> payload = new HashMap<>();
        payload.put("sender", sender);
        payload.put("body", body);
        payload.put("at", ServerValue.TIMESTAMP);
        message.setValue(payload).addOnCompleteListener(task -> {
            pending.remove(key);
            if (!task.isSuccessful() && sender.equals(uid)) failed.run();
            notifyObservers();
        });
    }

    private void clearFamily() {
        if (membershipQuery != null) membershipQuery.removeEventListener(membershipListener);
        membershipQuery = null;
        clearThreads();
        names.clear();
        family = null;
    }

    private void clearThreads() {
        for (String peer : new ArrayList<>(threads.keySet())) removeThread(peer);
    }

    private void removeThread(String peer) {
        ThreadState thread = threads.remove(peer);
        if (thread != null) thread.query.removeEventListener(thread.listener);
    }

    void observe(Runnable listener) { observers.add(listener); }
    void removeObserver(Runnable listener) { observers.remove(listener); }
    private void notifyObservers() {
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }

    List<ThreadState> threads() { return new ArrayList<>(threads.values()); }
    ThreadState thread(String peer) { return threads.get(peer); }
    boolean own(Message message) { return message.sender.equals(uid); }
    boolean online() { return connected; }
    String status() { return status; }
    String name(String id) { return names.getOrDefault(id, "Family member"); }
    String title(ThreadState thread) { return GROUP.equals(thread.peer) ? "Family Group" : name(thread.peer); }
    boolean unread(Message message) { return !own(message) && uid != null && !message.received(uid, true); }
    long listTime(Message message) {
        if (own(message) || uid == null) return message.at;
        Long receivedAt = message.snapshot.child("receipts").child(uid)
                .child("deliveredAt").getValue(Long.class);
        return receivedAt == null ? message.at : receivedAt;
    }
    boolean sending(Message message) { return pending.contains(message.snapshot.getKey()); }

    boolean acknowledged(ThreadState thread, Message message, boolean read) {
        if (!GROUP.equals(thread.peer)) return message.received(thread.peer, read);
        boolean hasRecipient = false;
        for (String recipient : names.keySet()) {
            if (recipient.equals(message.sender)) continue;
            hasRecipient = true;
            if (!message.received(recipient, read)) return false;
        }
        return hasRecipient;
    }

    static final class ThreadState {
        final String peer;
        final DatabaseReference ref;
        final Query query;
        final List<Message> messages = new ArrayList<>();
        ValueEventListener listener;
        boolean error;
        ThreadState(String peer, DatabaseReference ref) {
            this.peer = peer;
            this.ref = ref;
            query = ref.orderByChild("at").limitToLast(60);
        }
    }

    static final class Message {
        final DataSnapshot snapshot;
        final String sender;
        final String body;
        final long at;
        Message(DataSnapshot snapshot, String sender, String body, long at) {
            this.snapshot = snapshot;
            this.sender = sender;
            this.body = body;
            this.at = at;
        }
        boolean received(String recipient, boolean read) {
            return snapshot.child("receipts").child(recipient)
                    .child(read ? "readAt" : "deliveredAt").exists();
        }
    }

    private static ValueEventListener listen(Query query, Consumer<DataSnapshot> data,
                                              Consumer<DatabaseError> failure) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) { data.accept(snapshot); }
            @Override public void onCancelled(@NonNull DatabaseError error) { failure.accept(error); }
        };
        query.addValueEventListener(listener);
        return listener;
    }
}
