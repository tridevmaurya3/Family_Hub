package com.tridev.familyhub.feature.chat;

import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ChatStatus {
    private final FamilyChatFragment host;
    private final FamilyChatStore store;
    private AlertDialog dialog;

    ChatStatus(FamilyChatFragment host, FamilyChatStore store) {
        this.host = host;
        this.store = store;
    }

    void open() {
        String uid = store.accountId();
        String family = store.statusFamily();
        if (uid == null || family == null) return;
        DatabaseReference statuses = FirebaseDatabase.getInstance().getReference("familyTextStatus").child(family);
        LinearLayout rows = new LinearLayout(host.requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * rows.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, padding);
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(rows);
        dialog = new ChatFormDialog(host.requireContext()).setTitle("Family Status").setView(scroll)
                .setNeutralButton("Remove mine", (d, w) -> {
                    if (active(family, uid)) statuses.child(uid).removeValue()
                            .addOnFailureListener(error -> notice("Unable to remove status"));
                })
                .setNegativeButton("Close", null)
                .setPositiveButton("Post text", (d, w) -> {
                    if (active(family, uid)) compose(statuses.child(uid), family, uid);
                }).show();
        AlertDialog listing = dialog;
        Map<String, String> members = new LinkedHashMap<>();
        members.put(uid, "You");
        members.putAll(store.groupCandidates());
        for (Map.Entry<String, String> member : members.entrySet()) {
            statuses.child(member.getKey()).get().addOnSuccessListener(snapshot -> {
                if (!listing.isShowing() || !active(family, uid)) return;
                String body = snapshot.child("body").getValue(String.class);
                Long postedAt = snapshot.child("postedAt").getValue(Long.class);
                if (body == null || postedAt == null) return;
                long remaining = postedAt + 86400000L - System.currentTimeMillis();
                if (remaining <= 0) return;
                TextView text = new TextView(rows.getContext());
                text.setText(member.getValue() + "\n" + body);
                text.setTextIsSelectable(true);
                text.setPadding(0, padding / 2, 0, padding / 2);
                LinearLayout card = new LinearLayout(rows.getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.addView(text);
                DatabaseReference receipts = statuses.getRoot().child("familyStatusReceipts")
                        .child(family).child(member.getKey()).child(Long.toString(postedAt));
                if (uid.equals(member.getKey())) {
                    addAction(card, "Viewers and likes", () -> receipts.get().addOnSuccessListener(data -> {
                        if (!listing.isShowing() || !active(family, uid)) return;
                        StringBuilder list = new StringBuilder();
                        for (com.google.firebase.database.DataSnapshot viewer : data.getChildren()) {
                            String id = viewer.getKey();
                            if (id == null) continue;
                            if (list.length() > 0) list.append("\n");
                            list.append(store.name(id));
                            if ("liked".equals(viewer.getValue(String.class))) list.append("  ♥");
                        }
                        new ChatFormDialog(host.requireContext()).setTitle("Viewers and likes")
                                .setMessage(list.length() == 0 ? "No views yet" : list.toString())
                                .setPositiveButton("Close", null).show();
                    }).addOnFailureListener(error -> notice("Unable to load viewers")));
                } else {
                    // A receipt records opening the post. Keep an existing like when reopening.
                    text.setText(member.getValue());
                    addAction(card, "View status", () -> {
                        if (!active(family, uid)) return;
                        text.setText(member.getValue() + "\n" + body);
                        receipts.child(uid).runTransaction(new com.google.firebase.database.Transaction.Handler() {
                            public com.google.firebase.database.Transaction.Result doTransaction(
                                    com.google.firebase.database.MutableData current) {
                                if (current.getValue() == null) current.setValue("seen");
                                return com.google.firebase.database.Transaction.success(current);
                            }
                            public void onComplete(com.google.firebase.database.DatabaseError error,
                                    boolean committed, com.google.firebase.database.DataSnapshot value) {
                                if (error != null) notice("View receipt unavailable");
                            }
                        });
                    });
                    addAction(card, "Like status", () -> receipts.child(uid).setValue("liked")
                            .addOnSuccessListener(done -> notice("Status liked"))
                            .addOnFailureListener(error -> notice("Unable to like status")));
                }
                if (!uid.equals(member.getKey())) {
                    com.google.android.material.button.MaterialButton reply =
                            new com.google.android.material.button.MaterialButton(rows.getContext(), null,
                                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
                    reply.setText("Reply privately");
                    reply.setOnClickListener(v -> {
                        if (!active(family, uid)) return;
                        listing.dismiss();
                        host.replyToStatus(member.getKey());
                    });
                    card.addView(reply);
                }
                rows.addView(card);
                rows.postDelayed(() -> rows.removeView(card), remaining);
            });
        }
    }

    private void addAction(LinearLayout row, String title, Runnable action) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(
                row.getContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button);
    }

    private void compose(DatabaseReference path, String family, String uid) {
        EditText input = new EditText(host.requireContext());
        input.setHint("Write a status");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        LinearLayout form = new LinearLayout(host.requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * form.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, inset);
        form.addView(ChatFormDialog.outlined(input));
        com.google.android.material.checkbox.MaterialCheckBox restricted =
                new com.google.android.material.checkbox.MaterialCheckBox(host.requireContext());
        restricted.setText("Only selected family members");
        form.addView(restricted);
        LinearLayout people = new LinearLayout(host.requireContext());
        people.setOrientation(LinearLayout.VERTICAL);
        form.addView(people);
        people.setVisibility(android.view.View.GONE);
        Map<String, Boolean> audience = new LinkedHashMap<>();
        for (Map.Entry<String, String> member : store.groupCandidates().entrySet()) {
            com.google.android.material.checkbox.MaterialCheckBox check =
                    new com.google.android.material.checkbox.MaterialCheckBox(host.requireContext());
            check.setText(member.getValue());
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) audience.put(member.getKey(), true);
                else audience.remove(member.getKey());
            });
            people.addView(check);
        }
        restricted.setOnCheckedChangeListener((button, checked) ->
                people.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE));
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(form);
        dialog = new ChatFormDialog(host.requireContext()).setTitle("New text status")
                .setMessage("Choose who can see your status for 24 hours.")
                .setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Post", null).show();
        AlertDialog editor = dialog;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!active(family, uid)) return;
            String body = input.getText().toString().trim();
            if (body.isEmpty()) { input.setError("Enter a status"); return; }
            if (restricted.isChecked() && audience.isEmpty()) {
                notice("Select at least one family member");
                return;
            }
            view.setEnabled(false);
            Map<String, Object> value = new LinkedHashMap<>();
            if (restricted.isChecked()) value.put("audience", new LinkedHashMap<>(audience));
            value.put("body", body);
            value.put("postedAt", ServerValue.TIMESTAMP);
            path.setValue(value).addOnCompleteListener(task -> {
                if (!editor.isShowing()) return;
                if (task.isSuccessful()) editor.dismiss();
                else { view.setEnabled(true); notice("Unable to publish status"); }
            });
        });
    }

    private boolean active(String family, String uid) {
        return host.isAdded() && host.getView() != null
                && Objects.equals(family, store.statusFamily()) && Objects.equals(uid, store.accountId());
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void notice(String message) {
        if (host.isAdded()) Toast.makeText(host.requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
