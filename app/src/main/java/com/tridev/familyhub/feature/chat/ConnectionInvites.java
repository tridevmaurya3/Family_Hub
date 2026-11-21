package com.tridev.familyhub.feature.chat;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class ConnectionInvites {
    private final DatabaseReference root;
    private final Runnable changed;
    private final Map<String, ValueEventListener> watches = new HashMap<>();
    private final Map<String, Invite> invites = new LinkedHashMap<>();
    private String account;
    private ValueEventListener indexListener;

    ConnectionInvites(DatabaseReference root, Runnable changed) {
        this.root = root;
        this.changed = changed;
    }

    static boolean validCode(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    List<Invite> entries() {
        return new ArrayList<>(invites.values());
    }

    private DatabaseReference invite(String code) {
        return root.child("chatConnectionInvites").child(code);
    }

    private DatabaseReference index(String uid) {
        return root.child("chatConnectionIndex").child(uid);
    }

    void bind(String uid) {
        if (indexListener != null) index(account).removeEventListener(indexListener);
        watches.forEach((code, listener) -> invite(code).removeEventListener(listener));
        watches.clear();
        invites.clear();
        account = uid;
        indexListener = uid == null ? null : listen(index(uid), snapshot -> {
            if (!uid.equals(account)) return;
            for (String code : new ArrayList<>(watches.keySet())) {
                if (!snapshot.hasChild(code)) {
                    invite(code).removeEventListener(watches.remove(code));
                    invites.remove(code);
                }
            }
            for (DataSnapshot child : snapshot.getChildren()) {
                String code = child.getKey();
                if (!validCode(code) || watches.containsKey(code)) continue;
                watches.put(code, listen(invite(code), value -> {
                    if (!uid.equals(account) || !watches.containsKey(code)) return;
                    Invite entry = parse(value);
                    if (entry == null) invites.remove(code);
                    else invites.put(code, entry);
                    changed.run();
                }, () -> {
                    if (!uid.equals(account)) return;
                    invites.remove(code);
                    changed.run();
                }));
            }
            changed.run();
        }, () -> {
            if (uid.equals(account)) {
                watches.forEach((code, listener) -> invite(code).removeEventListener(listener));
                watches.clear();
                invites.clear();
                changed.run();
            }
        });
    }

    void create(String name, Consumer<String> done) {
        String uid = account;
        if (uid == null) { done.accept(null); return; }
        String code = java.util.UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new HashMap<>();
        data.put("owner", uid);
        data.put("ownerName", displayName(name));
        data.put("createdAt", ServerValue.TIMESTAMP);
        data.put("status", "open");
        invite(code).setValue(data).addOnCompleteListener(task ->
                done.accept(task.isSuccessful() && uid.equals(account) ? code : null));
    }

    void claim(String code, String name, Consumer<Invite> done) {
        String uid = account;
        if (!validCode(code) || uid == null) { done.accept(null); return; }
        invite(code).get().addOnCompleteListener(task -> {
            Invite entry = task.isSuccessful() ? parse(task.getResult()) : null;
            if (entry == null || !uid.equals(account) || uid.equals(entry.owner)) { done.accept(null); return; }
            if (uid.equals(entry.recipient) && ("pending".equals(entry.status) || "accepted".equals(entry.status))) {
                done.accept(entry);
                return;
            }
            if (!"open".equals(entry.status)) { done.accept(null); return; }
            String path = "chatConnectionInvites/" + code + "/";
            Map<String, Object> update = new HashMap<>();
            update.put(path + "recipient", uid);
            String recipientName = displayName(name);
            update.put(path + "recipientName", recipientName);
            update.put(path + "status", "pending");
            update.put("chatConnectionIndex/" + uid + "/" + code, true);
            update.put("chatConnectionIndex/" + entry.owner + "/" + code, true);
            root.updateChildren(update).addOnCompleteListener(write ->
                    done.accept(write.isSuccessful() && uid.equals(account)
                            ? new Invite(code, entry.owner, entry.ownerName, uid, recipientName, "pending", entry.createdAt)
                            : null));
        });
    }

    void respond(Invite invite, boolean accept, Consumer<Boolean> done) {
        String uid = account;
        if (uid == null || !uid.equals(invite.recipient)) { done.accept(false); return; }
        invite(invite.code).child("status").setValue(accept ? "accepted" : "declined")
                .addOnCompleteListener(task -> done.accept(task.isSuccessful() && uid.equals(account)));
    }

    private static String displayName(String name) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? "Family Hub user" : value.substring(0, Math.min(80, value.length()));
    }

    private static ValueEventListener listen(DatabaseReference ref, Consumer<DataSnapshot> read, Runnable cancelled) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot data) { read.accept(data); }
            @Override public void onCancelled(@NonNull DatabaseError error) { cancelled.run(); }
        };
        ref.addValueEventListener(listener);
        return listener;
    }

    private static Invite parse(DataSnapshot data) {
        String owner = data.child("owner").getValue(String.class);
        String ownerName = data.child("ownerName").getValue(String.class);
        String status = data.child("status").getValue(String.class);
        Long createdAt = data.child("createdAt").getValue(Long.class);
        if (owner == null || ownerName == null || status == null || createdAt == null) return null;
        return new Invite(data.getKey(), owner, ownerName, data.child("recipient").getValue(String.class),
                data.child("recipientName").getValue(String.class), status, createdAt);
    }

    static final class Invite {
        final String code, owner, ownerName, recipient, recipientName, status;
        final long createdAt;

        Invite(String code, String owner, String ownerName, String recipient, String recipientName,
               String status, long createdAt) {
            this.code = code;
            this.owner = owner;
            this.ownerName = ownerName;
            this.recipient = recipient;
            this.recipientName = recipientName;
            this.status = status;
            this.createdAt = createdAt;
        }

        boolean pendingFor(String uid) {
            return "pending".equals(status) && recipient != null && recipient.equals(uid)
                    && createdAt + 86_400_000L > System.currentTimeMillis();
        }
    }
}
