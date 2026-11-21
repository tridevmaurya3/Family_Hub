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

    private ChatNotifications notifications;
    private final DatabaseReference root = FirebaseDatabase.getInstance().getReference();
    private final List<Runnable> observers = new ArrayList<>();
    private final Map<String, ThreadState> threads = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Map<String, Boolean> writingReceipts = new HashMap<>();
    private Query sessionQuery;
    private Query membershipQuery;
    private DatabaseReference roomIndex;
    private ValueEventListener roomIndexListener;
    private final java.util.Set<String> indexedRooms = new java.util.HashSet<>();
    private ValueEventListener sessionListener;
    private ValueEventListener membershipListener;
    private String uid;
    private String family;
    private boolean connected;
    private boolean started;
    private String status = "Connecting…";

    private FamilyChatStore() {}

    public static void startForApp(android.content.Context context) {
        INSTANCE.notifications = new ChatNotifications(context, INSTANCE);
        INSTANCE.start();
    }

    private void start() {
        if (started) return;
        started = true;
        FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            String signedIn = auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid();
            if (java.util.Objects.equals(signedIn, uid)) return;
            if (sessionQuery != null) sessionQuery.removeEventListener(sessionListener);
            sessionQuery = null;
            if (notifications != null) notifications.clear();
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
                stopRooms();
                clearThreads();
                names.clear();
                status = "Active family membership is required";
                notifyObservers();
                return;
            }
            status = "";
            for (String peer : new ArrayList<>(threads.keySet())) {
                if (!GROUP.equals(peer) && !peer.startsWith("room:") && !names.containsKey(peer)) removeThread(peer);
            }
            watchRooms();
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

    private void watchRooms() {
        if (roomIndex != null) return;
        String expectedFamily = family;
        DatabaseReference base = root.child("familyChat").child(family);
        roomIndex = base.child("roomIndex").child(uid);
        DatabaseReference index = roomIndex;
        roomIndexListener = listen(index, snapshot -> {
            indexedRooms.clear();
            for (DataSnapshot entry : snapshot.getChildren()) {
                if (Boolean.TRUE.equals(entry.getValue(Boolean.class))) indexedRooms.add(entry.getKey());
            }
            for (String peer : new ArrayList<>(threads.keySet())) {
                if (peer.startsWith("room:") && !indexedRooms.contains(peer.substring(5))) removeThread(peer);
            }
            for (DataSnapshot entry : snapshot.getChildren()) {
                String room = entry.getKey();
                if (!Boolean.TRUE.equals(entry.getValue(Boolean.class))
                        || threads.containsKey("room:" + room)) continue;
                DatabaseReference details = base.child("rooms").child(room);
                com.google.android.gms.tasks.Task<DataSnapshot> title = details.child("title").get();
                com.google.android.gms.tasks.Task<DataSnapshot> members = details.child("members").get();
                com.google.android.gms.tasks.Tasks.whenAllSuccess(title, members).addOnSuccessListener(result -> {
                    if (roomIndex != index || !expectedFamily.equals(family) || !indexedRooms.contains(room)) return;
                    addRoom(room, details, title.getResult().getValue(String.class), members.getResult());
                });
            }
            notifyObservers();
        }, error -> {
            indexedRooms.clear();
            for (String peer : new ArrayList<>(threads.keySet())) {
                if (peer.startsWith("room:")) removeThread(peer);
            }
            notifyObservers();
        });
    }

    private void stopRooms() {
        if (roomIndex == null) return;
        roomIndex.removeEventListener(roomIndexListener);
        roomIndex = null;
        indexedRooms.clear();
    }

    private void addRoom(String room, DatabaseReference details, String title, DataSnapshot membership) {
        String key = "room:" + room;
        if (threads.containsKey(key) || title == null
                || !Boolean.TRUE.equals(membership.child(uid).getValue(Boolean.class))) return;
        List<String> members = new ArrayList<>();
        for (DataSnapshot member : membership.getChildren()) members.add(member.getKey());
        watchThread(new ThreadState(key, details.child("messages"), roomFiles(room), title, members));
        notifyObservers();
    }

    private DatabaseReference roomFiles(String room) {
        return root.child("familyChat").child(family).child("files").child("rooms").child(room);
    }

    Map<String, String> groupCandidates() {
        Map<String, String> candidates = new LinkedHashMap<>(names);
        candidates.remove(uid);
        return candidates;
    }

    void createRoom(String title, List<String> selected, String photo, Consumer<String> success, Runnable failed) {
        if (uid == null || family == null || !names.containsKey(uid) || selected.isEmpty() || selected.contains(uid)
                || !names.keySet().containsAll(selected)) {
            failed.run();
            return;
        }
        String expectedFamily = family;
        String creator = uid;
        DatabaseReference base = root.child("familyChat").child(family);
        DatabaseReference room = base.child("rooms").push();
        String roomId = room.getKey();
        if (roomId == null) {
            failed.run();
            return;
        }
        Map<String, Object> changes = new HashMap<>();
        String path = "rooms/" + roomId + "/";
        changes.put(path + "title", title);
        changes.put(path + "creator", creator);
        if (photo != null) changes.put(path + "photo", photo);
        List<String> members = new ArrayList<>(new java.util.LinkedHashSet<>(selected));
        members.add(creator);
        for (String member : members) {
            changes.put(path + "members/" + member, true);
            changes.put("roomIndex/" + member + "/" + roomId, true);
        }
        base.updateChildren(changes).addOnCompleteListener(task -> {
            if (!expectedFamily.equals(family) || !creator.equals(uid)) return;
            if (!task.isSuccessful()) {
                failed.run();
                return;
            }
            String key = "room:" + roomId;
            if (!threads.containsKey(key)) {
                watchThread(new ThreadState(key, room.child("messages"), roomFiles(roomId), title, members));
            }
            success.accept(key);
            notifyObservers();
        });
    }

    private void addThread(String peer) {
        if (threads.containsKey(peer)) return;
        DatabaseReference base = root.child("familyChat").child(family);
        DatabaseReference messages;
        DatabaseReference files;
        if (GROUP.equals(peer)) {
            messages = base.child("group");
            files = base.child("files").child("group");
        } else {
            String first = uid.compareTo(peer) < 0 ? uid : peer;
            String second = first.equals(uid) ? peer : uid;
            messages = base.child("direct").child(first).child(second);
            files = base.child("files").child("direct").child(first).child(second);
        }
        watchThread(new ThreadState(peer, messages, files, null, null));
    }

    private void watchThread(ThreadState thread) {
        String peer = thread.peer;
        threads.put(peer, thread);
        if (thread.members != null) {
            DatabaseReference room = thread.ref.getParent();
            thread.membersListener = listen(room.child("members"), snapshot -> {
                if (threads.get(peer) != thread) return;
                if (!Boolean.TRUE.equals(snapshot.child(uid).getValue(Boolean.class))) {
                    removeThread(peer);
                } else {
                    thread.members.clear();
                    for (DataSnapshot member : snapshot.getChildren()) thread.members.add(member.getKey());
                }
                notifyObservers();
            }, error -> {
                if (threads.get(peer) != thread) return;
                removeThread(peer);
                notifyObservers();
            });
            thread.adminsListener = listen(room.child("admins"), snapshot -> {
                if (threads.get(peer) != thread) return;
                thread.admins.clear();
                for (DataSnapshot member : snapshot.getChildren()) {
                    if (Boolean.TRUE.equals(member.getValue(Boolean.class))) thread.admins.add(member.getKey());
                }
                notifyObservers();
            }, error -> {
                thread.admins.clear();
                if (threads.get(peer) == thread) notifyObservers();
            });
            thread.titleListener = listen(room.child("title"), snapshot -> {
                if (threads.get(peer) != thread) return;
                String title = snapshot.getValue(String.class);
                if (title != null && !title.equals(thread.roomTitle)) {
                    thread.roomTitle = title;
                    notifyObservers();
                }
            }, error -> {});
            room.child("creator").get().addOnSuccessListener(snapshot -> {
                if (threads.get(peer) != thread) return;
                thread.creator = snapshot.getValue(String.class);
                notifyObservers();
            });
            thread.photoListener = listen(room.child("photo"), snapshot -> {
                if (threads.get(peer) != thread) return;
                thread.photo = decodeGroupPhoto(snapshot.getValue(String.class));
                notifyObservers();
            }, error -> {
                if (threads.get(peer) != thread) return;
                thread.photo = null;
                notifyObservers();
            });
        }
        thread.listener = listen(thread.query, snapshot -> {
            if (threads.get(peer) != thread) return;
            thread.messages.clear();
            thread.error = false;
            for (DataSnapshot child : snapshot.getChildren()) {
                Message message = parseMessage(child);
                if (message != null) thread.messages.add(message);
            }
            if (notifications != null) notifications.onMessages(thread);
            acknowledgeDelivery(thread);
            notifyObservers();
        }, error -> {
            if (threads.get(peer) != thread) return;
            thread.messages.clear();
            thread.error = true;
            notifyObservers();
        });
    }

    private static Message parseMessage(DataSnapshot data) {
        String sender = data.child("sender").getValue(String.class);
        String body = data.child("body").getValue(String.class);
        Long at = data.child("at").getValue(Long.class);
        return sender != null && body != null && at != null ? new Message(data, sender, body, at) : null;
    }

    void loadMessage(ThreadState thread, String key, Consumer<Message> callback) {
        if (!canUse(thread) || key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            callback.accept(null);
            return;
        }
        thread.ref.child(key).get().addOnCompleteListener(task ->
                callback.accept(task.isSuccessful() && canUse(thread) ? parseMessage(task.getResult()) : null));
    }

    void history(ThreadState thread, Message before, long from, long to,
                 Consumer<List<Message>> callback) {
        if (!canUse(thread)) {
            callback.accept(null);
            return;
        }
        Query query = thread.ref.orderByChild("at").startAt(from);
        query = before == null ? query.endAt(to) : query.endBefore(before.at, before.snapshot.getKey());
        query.limitToLast(100).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || !canUse(thread)) {
                callback.accept(null);
                return;
            }
            List<Message> messages = new ArrayList<>();
            for (DataSnapshot snapshot : task.getResult().getChildren()) {
                Message message = parseMessage(snapshot);
                if (message != null) messages.add(0, message);
            }
            callback.accept(messages);
        });
    }

    void pinned(ThreadState thread, Consumer<List<Message>> callback) {
        if (!canUse(thread)) {
            callback.accept(null);
            return;
        }
        thread.ref.orderByChild("pinned").equalTo(true).limitToLast(50).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || !canUse(thread)) {
                callback.accept(null);
                return;
            }
            List<Message> messages = new ArrayList<>();
            for (DataSnapshot snapshot : task.getResult().getChildren()) {
                Message message = parseMessage(snapshot);
                if (message != null && !message.deleted()) messages.add(0, message);
            }
            callback.accept(messages);
        });
    }

    void pin(ThreadState thread, Message message, Consumer<Boolean> complete) {
        if (!canUse(thread)) return;
        message.snapshot.getRef().child("pinned").setValue(message.pinned() ? null : true)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    void react(ThreadState thread, Message message, String reaction, Consumer<Boolean> complete) {
        if (!canUse(thread)) return;
        String previous = message.snapshot.child("reactions").child(uid).getValue(String.class);
        message.snapshot.getRef().child("reactions").child(uid).setValue(reaction.equals(previous) ? null : reaction)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    List<String> participants(ThreadState thread) {
        if (thread.isGroup()) return groupMembers(thread);
        return java.util.Arrays.asList(uid, thread.peer);
    }

    static android.graphics.Bitmap decodeGroupPhoto(String encoded) {
        if (encoded == null || encoded.length() > 64000) return null;
        try {
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 320 || bounds.outHeight > 320) return null;
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException | OutOfMemoryError error) {
            return null;
        }
    }

    boolean canEditGroupPhoto(ThreadState thread) {
        return canUse(thread) && thread.members != null && (uid.equals(thread.creator) || thread.admins.contains(uid));
    }

    boolean ownsGroup(ThreadState thread) {
        return canUse(thread) && thread.members != null && uid.equals(thread.creator);
    }

    void changeGroupRole(ThreadState thread, String member, boolean promote, Consumer<Boolean> complete) {
        if (!ownsGroup(thread) || uid.equals(member) || !thread.members.contains(member)) {
            complete.accept(false);
            return;
        }
        thread.ref.getParent().child("admins").child(member).setValue(promote ? true : null)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    void changeGroupMember(ThreadState thread, String member, boolean add, Consumer<Boolean> complete) {
        if (!canEditGroupPhoto(thread) || member.equals(thread.creator) || (add && !names.containsKey(member))
                || (!add && thread.admins.contains(member) && !ownsGroup(thread))) {
            complete.accept(false);
            return;
        }
        String room = thread.peer.substring(5);
        Map<String, Object> changes = new HashMap<>();
        changes.put("rooms/" + room + "/members/" + member, add ? true : null);
        changes.put("roomIndex/" + member + "/" + room, add ? true : null);
        if (!add && ownsGroup(thread) && thread.admins.contains(member)) {
            changes.put("rooms/" + room + "/admins/" + member, null);
        }
        root.child("familyChat").child(family).updateChildren(changes)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    void renameGroup(ThreadState thread, String title, Consumer<Boolean> complete) {
        if (!canEditGroupPhoto(thread)) {
            complete.accept(false);
            return;
        }
        thread.ref.getParent().child("title").setValue(title)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    void editText(ThreadState thread, Message message, String body, boolean delete, Consumer<Boolean> complete) {
        if (!canUse(thread) || !own(message) || !"text".equals(message.kind()) || message.deleted()) {
            complete.accept(false);
            return;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put("body", delete ? "Message deleted" : body);
        updates.put(delete ? "deletedAt" : "editedAt", ServerValue.TIMESTAMP);
        message.snapshot.getRef().updateChildren(updates)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    void setGroupPhoto(ThreadState thread, String photo, Consumer<Boolean> complete) {
        if (!canEditGroupPhoto(thread)) {
            complete.accept(false);
            return;
        }
        thread.ref.getParent().child("photo").setValue(photo)
                .addOnCompleteListener(task -> complete.accept(task.isSuccessful()));
    }

    List<String> groupMembers(ThreadState thread) {
        if (thread.members == null) return new ArrayList<>(names.keySet());
        List<String> active = new ArrayList<>();
        for (String member : thread.members) if (names.containsKey(member)) active.add(member);
        return active;
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
        send(thread, body, java.util.Collections.emptyMap(), null, failed);
    }

    void send(ThreadState thread, String body, Map<String, Object> details, String file, Runnable failed) {
        send(thread, body, details, file, failed, success -> {});
    }

    void send(ThreadState thread, String body, Map<String, Object> details, String file,
              Runnable failed, Consumer<Boolean> completed) {
        if (uid == null || threads.get(thread.peer) != thread || thread.error) {
            failed.run();
            completed.accept(false);
            return;
        }
        DatabaseReference message = thread.ref.push();
        String key = message.getKey();
        if (key == null) {
            failed.run();
            completed.accept(false);
            return;
        }
        pending.add(key);
        String sender = uid;
        Map<String, Object> payload = new HashMap<>();
        payload.put("sender", sender);
        payload.put("body", body);
        payload.put("at", ServerValue.TIMESTAMP);
        payload.putAll(details);
        com.google.android.gms.tasks.Task<Void> write;
        if (file == null) {
            write = message.setValue(payload);
        } else {
            Map<String, Object> changes = new HashMap<>();
            changes.put(relativePath(message), payload);
            changes.put(relativePath(thread.files.child(key)), file);
            write = root.updateChildren(changes);
        }
        write.addOnCompleteListener(task -> {
            pending.remove(key);
            if (!task.isSuccessful() && sender.equals(uid)) failed.run();
            completed.accept(task.isSuccessful());
            notifyObservers();
        });
    }

    private String relativePath(DatabaseReference ref) {
        List<String> parts = new ArrayList<>();
        for (DatabaseReference node = ref; node.getParent() != null; node = node.getParent()) {
            parts.add(node.getKey());
        }
        java.util.Collections.reverse(parts);
        return String.join("/", parts);
    }

    String vote(Message message) {
        return uid == null ? null : message.snapshot.child("votes").child(uid).getValue(String.class);
    }

    void vote(ThreadState thread, Message message, String option, Runnable failed) {
        if (!canUse(thread)) return;
        message.snapshot.getRef().child("votes").child(uid).setValue(option)
                .addOnFailureListener(error -> failed.run());
    }

    String statusFamily() { return family; }

    String accountId() { return uid; }

    private DatabaseReference savedRef(ThreadState thread) {
        String key = java.util.UUID.nameUUIDFromBytes(thread.ref.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return root.child("chatSaved").child(uid).child(key);
    }

    void saved(ThreadState thread, Consumer<DataSnapshot> callback) {
        if (!canUse(thread)) { callback.accept(null); return; }
        savedRef(thread).get().addOnCompleteListener(task ->
                callback.accept(task.isSuccessful() && canUse(thread) ? task.getResult() : null));
    }

    void importSaved(ThreadState thread, String key) {
        if (!canUse(thread) || !key.matches("[A-Za-z0-9_-]{1,128}")) return;
        savedRef(thread).child(key).runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @NonNull @Override public com.google.firebase.database.Transaction.Result doTransaction(
                    @NonNull com.google.firebase.database.MutableData data) {
                if (data.getValue() != null) return com.google.firebase.database.Transaction.abort();
                data.setValue(true);
                return com.google.firebase.database.Transaction.success(data);
            }
            @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {}
        });
    }

    void saveForMe(ThreadState thread, String key, boolean saved, Consumer<Boolean> callback) {
        if (!canUse(thread)) { callback.accept(false); return; }
        savedRef(thread).child(key).setValue(saved).addOnCompleteListener(task -> callback.accept(task.isSuccessful()));
    }

    void acknowledgeAnnouncement(ThreadState thread, Message message, Consumer<Boolean> callback) {
        if (!canUse(thread) || own(message)) { callback.accept(false); return; }
        message.snapshot.getRef().child("acks").child(uid).setValue(true)
                .addOnCompleteListener(task -> callback.accept(task.isSuccessful()));
    }

    void setChecklistItem(ThreadState thread, Message message, String key, boolean checked,
                          Consumer<Boolean> callback) {
        if (!canUse(thread)) { callback.accept(false); return; }
        message.snapshot.getRef().child("checks").child(key).setValue(checked ? uid : null)
                .addOnCompleteListener(task -> callback.accept(task.isSuccessful()));
    }

    boolean canUse(ThreadState thread) {
        return uid != null && threads.get(thread.peer) == thread && !thread.error;
    }

    private void clearFamily() {
        stopRooms();
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
        if (thread == null) return;
        thread.query.removeEventListener(thread.listener);
        if (thread.photoListener != null) thread.ref.getParent().child("photo").removeEventListener(thread.photoListener);
        if (thread.titleListener != null) thread.ref.getParent().child("title").removeEventListener(thread.titleListener);
        if (thread.membersListener != null) thread.ref.getParent().child("members").removeEventListener(thread.membersListener);
        if (thread.adminsListener != null) thread.ref.getParent().child("admins").removeEventListener(thread.adminsListener);
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
    String title(ThreadState thread) {
        if (thread.roomTitle != null) return thread.roomTitle;
        return GROUP.equals(thread.peer) ? "Family Group" : name(thread.peer);
    }
    boolean unread(Message message) { return !own(message) && uid != null && !message.received(uid, true); }
    long listTime(Message message) {
        if (own(message) || uid == null) return message.at;
        Long receivedAt = message.snapshot.child("receipts").child(uid)
                .child("deliveredAt").getValue(Long.class);
        return receivedAt == null ? message.at : receivedAt;
    }
    boolean sending(Message message) { return pending.contains(message.snapshot.getKey()); }

    boolean acknowledged(ThreadState thread, Message message, boolean read) {
        if (!thread.isGroup()) return message.received(thread.peer, read);
        boolean hasRecipient = false;
        for (String recipient : thread.members == null ? names.keySet() : thread.members) {
            if (!names.containsKey(recipient)) continue;
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
        final DatabaseReference files;
        final List<Message> messages = new ArrayList<>();
        final java.util.Set<String> notificationSeen = new java.util.HashSet<>();
        boolean notificationsInitialized;
        ValueEventListener listener;
        ValueEventListener photoListener;
        String creator;
        android.graphics.Bitmap photo;
        boolean error;
        String roomTitle;
        ValueEventListener titleListener;
        ValueEventListener membersListener;
        ValueEventListener adminsListener;
        final java.util.Set<String> admins = new java.util.HashSet<>();
        final List<String> members;
        ThreadState(String peer, DatabaseReference ref, DatabaseReference files, String roomTitle, List<String> members) {
            this.roomTitle = roomTitle;
            this.members = members;
            this.peer = peer;
            this.ref = ref;
            this.files = files;
            query = ref.orderByChild("at").limitToLast(60);
        }

        boolean isGroup() {
            return GROUP.equals(peer) || members != null;
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
        boolean deleted() { return snapshot.hasChild("deletedAt"); }

        boolean edited() { return !deleted() && snapshot.hasChild("editedAt"); }

        Long pollDeadline() { return snapshot.child("closesAt").getValue(Long.class); }

        boolean pollClosed() {
            Long deadline = pollDeadline();
            return deadline != null && deadline <= System.currentTimeMillis();
        }

        boolean pinned() { return Boolean.TRUE.equals(snapshot.child("pinned").getValue(Boolean.class)); }

        String kind() {
            String type = snapshot.child("kind").getValue(String.class);
            return type == null ? "text" : type;
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
