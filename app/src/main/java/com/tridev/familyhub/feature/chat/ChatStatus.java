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
                .setPositiveButton("New status", (d, w) -> {
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
                DatabaseReference receipts = statuses.getRoot().child("familyStatusReceipts")
                        .child(family).child(member.getKey()).child(Long.toString(postedAt));
                String time = android.text.format.DateUtils.getRelativeTimeSpanString(
                        postedAt, System.currentTimeMillis(), 60000).toString();
                android.view.View entry = addAction(rows, member.getValue() + "  ·  " + time, () -> {
                    if (!active(family, uid) || postedAt + 86400000L <= System.currentTimeMillis()) return;
                    listing.dismiss();
                    showStatus(family, uid, member.getKey(), member.getValue(), body, receipts);
                });
                rows.postDelayed(() -> rows.removeView(entry), remaining);
            });
        }
    }

    private static final String[] REACTIONS = {"liked", "laugh", "wow", "sad", "clap", "fire"};
    private static final String[] EMOJI = {"❤️", "😂", "😮", "😢", "👏", "🔥"};

    private void showStatus(String family, String uid, String author, String name, String body,
                            DatabaseReference receipts) {
        boolean mine = uid.equals(author);
        LinearLayout content = new LinearLayout(host.requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * content.getResources().getDisplayMetrics().density);
        content.setPadding(inset, inset, inset, inset);
        TextView post = new TextView(content.getContext());
        post.setText(body);
        post.setTextSize(22);
        post.setGravity(android.view.Gravity.CENTER);
        post.setMinHeight(8 * inset);
        post.setTextIsSelectable(true);
        content.addView(post, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(content);
        dialog = new ChatFormDialog(host.requireContext()).setTitle(name).setView(scroll)
                .setNegativeButton("Back to statuses", (d, w) -> {
                    if (active(family, uid)) open();
                }).show();
        AlertDialog viewer = dialog;
        if (mine) {
            TextView feedback = new TextView(content.getContext());
            feedback.setText("Viewers and reactions");
            content.addView(feedback);
            receipts.get().addOnSuccessListener(data -> {
                if (!viewer.isShowing() || !active(family, uid)) return;
                StringBuilder result = new StringBuilder("Viewers and reactions");
                for (com.google.firebase.database.DataSnapshot item : data.getChildren()) {
                    if (item.getKey() == null) continue;
                    result.append("\n").append(store.name(item.getKey()));
                    String reaction = item.getValue(String.class);
                    for (int i = 0; i < REACTIONS.length; i++) {
                        if (REACTIONS[i].equals(reaction)) result.append("  ").append(EMOJI[i]);
                    }
                }
                if (!data.hasChildren()) result.append("\nNo views yet");
                feedback.setText(result);
            }).addOnFailureListener(error -> feedback.setText("Unable to load viewers"));
            return;
        }
        DatabaseReference receipt = receipts.child(uid);
        receipt.runTransaction(new com.google.firebase.database.Transaction.Handler() {
            public com.google.firebase.database.Transaction.Result doTransaction(
                    com.google.firebase.database.MutableData current) {
                if (current.getValue() == null) current.setValue("seen");
                return com.google.firebase.database.Transaction.success(current);
            }
            public void onComplete(com.google.firebase.database.DatabaseError error,
                                   boolean committed, com.google.firebase.database.DataSnapshot value) {}
        });
        com.google.android.material.chip.ChipGroup reactions =
                new com.google.android.material.chip.ChipGroup(content.getContext());
        reactions.setSingleSelection(true);
        content.addView(reactions);
        com.google.android.material.chip.Chip[] chips = new com.google.android.material.chip.Chip[EMOJI.length];
        for (int i = 0; i < EMOJI.length; i++) {
            final int index = i;
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(content.getContext());
            chip.setId(android.view.View.generateViewId());
            chip.setText(EMOJI[i]);
            chip.setContentDescription(new String[]{"Heart", "Laugh", "Surprised", "Sad", "Applause", "Fire"}[i]);
            chip.setCheckable(true);
            chips[i] = chip;
            reactions.addView(chip);
            chip.setOnClickListener(v -> {
                if (!active(family, uid)) return;
                receipt.setValue(chip.isChecked() ? REACTIONS[index] : "seen")
                        .addOnFailureListener(error -> {
                            reactions.clearCheck();
                            notice("Unable to save reaction");
                        });
            });
        }
        receipt.get().addOnSuccessListener(snapshot -> {
            if (!viewer.isShowing() || reactions.getCheckedChipId() != android.view.View.NO_ID) return;
            for (int i = 0; i < REACTIONS.length; i++) {
                if (REACTIONS[i].equals(snapshot.getValue(String.class))) reactions.check(chips[i].getId());
            }
        });
        FamilyChatStore.ThreadState direct = store.thread(author);
        if (direct == null || !store.canUse(direct)) return;
        EditText reply = new EditText(content.getContext());
        reply.setHint("Reply privately");
        reply.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        content.addView(ChatFormDialog.outlined(reply));
        addAction(content, "Send reply", () -> {
            if (!active(family, uid) || !store.canUse(direct)) return;
            String message = reply.getText().toString().trim();
            if (message.isEmpty()) return;
            store.send(direct, message, () -> notice("Unable to send reply"));
            reply.setText("");
        });
    }

    private android.view.View addAction(LinearLayout row, String title, Runnable action) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(
                row.getContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button);
        return button;
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
